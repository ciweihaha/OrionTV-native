package com.oriontv.feature.live

import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.executeCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * M3U 仓库（plan §5.3）：
 * 直播源地址是用户在设置里填的 m3uUrl（默认空串），代码里不写死地址；
 * 拉取 15 秒超时、体积上限 5MB，失败返回空列表（不抛错）。
 * 缓存：TTL 7 天、M3U JSON 上限 5MB（与拉取上限对齐，3–5MB 的源也能离线）；缓存秒显 + 后台刷新：
 * 缓存新鲜直接用，过期才拉网，拉网失败回落旧缓存。
 */
@Singleton
class M3uRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cacheStore: LiveCacheStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** B4-P2-1：播放列表专用 client（15 秒读超时）只建一次，不再每次拉网新建。 */
    private val playlistClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(ApiClient.PLAYLIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 第 6 轮 D6-P2-13：最近一次加载是否拉网失败（与 FavoritesRepository.loadFailed
     * 同款）：拉网失败/解析出 0 台时置 true（即使回落了旧缓存），成功时置 false；
     * 调用方（“刷新直播源”入口）据此提示“刷新失败，仍用旧数据”，不再把回落的
     * 旧缓存误当成“已刷新”。
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** 缓存优先加载：缓存新鲜直接返回；过期拉网，成功落盘，失败回落旧缓存。 */
    suspend fun loadChannels(
        m3uUrl: String,
        nowMs: Long = System.currentTimeMillis(),
        /** A4-P2-3：强制刷新时跳过缓存直接拉网（面板“刷新直播源”入口用）。 */
        forceRefresh: Boolean = false,
    ): List<M3uParser.Channel> =
        withContext(ioDispatcher) {
            if (m3uUrl.isBlank()) return@withContext emptyList()
            val cached = cacheStore.readM3uCache(m3uUrl)
            if (!forceRefresh && cached != null && nowMs - cached.ts <= LiveController.M3U_CACHE_TTL_MS) {
                _loadFailed.value = false
                return@withContext cached.channels
            }
            val fresh = fetchChannels(m3uUrl)
            val decision = decideLoad(cached?.channels, fresh)
            _loadFailed.value = decision.loadFailed
            if (fresh.isNotEmpty()) {
                cacheStore.saveM3uCache(m3uUrl, fresh)
            }
            // 拉网失败：回落旧缓存（不断流），没有才返回空列表
            decision.channels
        }

    companion object {
        /**
         * 第 6 轮 D6-P2-13：加载结果判定（纯函数，可单测）——fresh 非空即用 fresh、
         * 失败标记 false；fresh 为空（拉网失败或解析出 0 台）则回落旧缓存并标记失败。
         */
        fun decideLoad(
            cachedChannels: List<M3uParser.Channel>?,
            freshChannels: List<M3uParser.Channel>,
        ): M3uLoadDecision =
            if (freshChannels.isNotEmpty()) {
                M3uLoadDecision(freshChannels, loadFailed = false)
            } else {
                M3uLoadDecision(cachedChannels.orEmpty(), loadFailed = true)
            }
    }

    /** 纯拉网（15 秒超时、5MB 上限，失败返回空列表，不抛错；协程取消原样抛出）。 */
    suspend fun fetchChannels(m3uUrl: String): List<M3uParser.Channel> = withContext(ioDispatcher) {
        if (m3uUrl.isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder().url(m3uUrl).get().build()
            playlistClient.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                // A4-P2-2：流式限量读，超 5MB 即停，不先整读 body 再判上限。
                val body = readBodyCapped(response, ApiClient.MAX_M3U_BYTES)
                    ?: return@withContext emptyList()
                M3uParser.parse(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** 第 6 轮 D6-P2-13：[M3uRepository.decideLoad] 的判定结果。 */
data class M3uLoadDecision(
    val channels: List<M3uParser.Channel>,
    val loadFailed: Boolean,
)

/** 第 7 轮 D7-P2-2：[EpgRepository.decideLoad] 的判定结果（与 M3uLoadDecision 同款）。 */
data class EpgLoadDecision(
    val data: EpgParser.EpgData?,
    val loadFailed: Boolean,
)

/**
 * EPG 仓库（plan §5.3）：
 * 地址硬编码 https://ysp.ciweihaha.win/epg.xml；拉取 15 秒超时；
 * 失败返回 null，不覆盖旧缓存。缓存 TTL 6 小时 + “仍有正在播的节目”判据
 *（天光 v2.2.20 教训：只看日期会在凌晨拉到昨天数据后整天无节目）。
 */
@Singleton
class EpgRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cacheStore: LiveCacheStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** B4-P2-1：EPG 专用 client（15 秒读超时）只建一次，不再每次拉网新建。 */
    private val playlistClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(ApiClient.PLAYLIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 第 7 轮 D7-P2-2：最近一次加载是否拉网失败（与 [M3uRepository.loadFailed]
     * 同款，D6-P2-13 的 EPG 孪生项）：拉网失败/解析出空表时置 true（即使回落
     * 了旧缓存），成功或缓存新鲜时置 false；由 [loadEpg] 同步置位，调用方
     * 读到信号时本次刷新已结束，无竞态。注意：本信号目前只有数据层一侧，
     * LiveViewModel 的接线（10 分钟 ticker 刷新失败时轻提示）不在本轮做，
     * 由总协调在播放器分支基线具备信号后另行接线。
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    companion object {
        const val EPG_URL = "https://ysp.ciweihaha.win/epg.xml"

        /**
         * 空 EPG 不落盘：只有 programmes 非空时才保存（P1-5；M3U 早有同款守卫）。
         * 纯函数，可单测（MockK 已随依赖升级移出，仓库级测试不再用 mock）。
         */
        fun shouldSaveFreshEpg(fresh: EpgParser.EpgData?): Boolean =
            fresh != null && fresh.programmesByChannel.isNotEmpty()

        /**
         * 第 7 轮 D7-P2-2：EPG 加载结果判定（纯函数，可单测；与
         * [M3uRepository.decideLoad] 同款）——fresh 非空即用 fresh、失败
         * 标记 false；fresh 为空（拉网失败或解析出空表）则回落旧缓存并标记失败。
         */
        fun decideLoad(
            cachedData: EpgParser.EpgData?,
            freshData: EpgParser.EpgData?,
        ): EpgLoadDecision =
            if (freshData != null && shouldSaveFreshEpg(freshData)) {
                EpgLoadDecision(freshData, loadFailed = false)
            } else {
                EpgLoadDecision(cachedData, loadFailed = true)
            }
    }

    /**
     * 缓存优先加载：缓存新鲜（TTL 内且仍有正在播的节目）直接返回；
     * 否则拉网，成功落盘，失败回落旧缓存（哪怕已过期，也比无节目单强）。
     */
    suspend fun loadEpg(nowMs: Long = System.currentTimeMillis()): EpgParser.EpgData? =
        withContext(ioDispatcher) {
            val cached = cacheStore.readEpgCache()
            if (cached != null && EpgParser.isCacheFresh(cached.data, cached.ts, nowMs)) {
                _loadFailed.value = false
                return@withContext cached.data
            }
            val fresh = fetchEpg()
            val decision = decideLoad(cached?.data, fresh)
            _loadFailed.value = decision.loadFailed
            // 空 EPG 不覆盖好缓存：服务端偶尔吐空表时，旧缓存比“无节目单”强（P1-5；M3U 早有同款守卫）。
            if (fresh != null && shouldSaveFreshEpg(fresh)) {
                cacheStore.saveEpgCache(fresh)
            }
            decision.data
        }

    /** 纯拉网（15 秒超时、3MB 上限，失败返回 null，不抛错；协程取消原样抛出）。 */
    suspend fun fetchEpg(): EpgParser.EpgData? = withContext(ioDispatcher) {
        try {
            val request = Request.Builder().url(EPG_URL).get().build()
            playlistClient.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return@withContext null
                // A4-P2-2：流式限量读，超 3MB 即停，不先整读 body 再判上限。
                val body = readBodyCapped(response, ApiClient.MAX_CACHE_BYTES)
                    ?: return@withContext null
                EpgParser.parse(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 流式限量读响应体（A4-P2-2）：Content-Length 先行拒绝，之后按 64KB 块流式读，
 * 累计超上限即停并返回 null，不把超大体整读进内存再判。
 */
internal fun readBodyCapped(response: okhttp3.Response, maxBytes: Long): String? {
    val declared = response.header("Content-Length")?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val body = response.body ?: return null
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    body.byteStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }
    return decodeBodyBytes(out.toByteArray(), response.header("Content-Type"))
}

/**
 * 响应体解码（A7-P2-3）：M3U/EPG 源常有 GBK 编码，旧实现硬解码 UTF-8——
 * String(bytes, UTF_8) 永不抛错，只会把 GBK 台名解成乱码（核实结论：是真
 * 缺陷，国内 IPTV 源 GBK 很常见；EPG 的 StringReader 还会忽略 XML 声明
 * 里的 encoding，同样中招）。顺序：BOM → Content-Type charset → XML 声明
 * encoding → 严格 UTF-8 → 回退 GBK。纯函数，可单测。
 */
internal fun decodeBodyBytes(bytes: ByteArray, contentType: String?): String {
    // BOM：UTF-8 / UTF-16LE / UTF-16BE。
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    // Content-Type 里声明的 charset。
    val headerCharset = contentType
        ?.split(";")
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter("=", "")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotBlank() }
    if (headerCharset != null) {
        try {
            return String(bytes, java.nio.charset.Charset.forName(headerCharset))
        } catch (_: Exception) {
            // 不支持/非法的 charset 声明，继续往下判断。
        }
    }
    // XML 声明里的 encoding（如 <?xml version="1.0" encoding="GBK"?>）：
    // 按 Latin-1 读前 512 字节找声明（字节与字符 1:1，不会误切多字节字符）。
    val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
    val xmlEncoding = Regex("""<\?xml[^>]*encoding=["']([^"']+)["']""")
        .find(head)?.groupValues?.getOrNull(1)
    if (xmlEncoding != null) {
        try {
            return String(bytes, java.nio.charset.Charset.forName(xmlEncoding))
        } catch (_: Exception) {
            // 非法声明，继续往下判断。
        }
    }
    // 严格 UTF-8 解码，失败回退 GBK（GBK 字节几乎不可能是合法 UTF-8）。
    return try {
        java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(bytes, java.nio.charset.Charset.forName("GBK"))
    }
}
