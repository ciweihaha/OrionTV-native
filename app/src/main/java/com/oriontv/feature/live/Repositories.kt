package com.oriontv.feature.live

import com.oriontv.core.util.AppLog
import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.BodyTooLargeException
import com.oriontv.data.api.executeCancellable
import com.oriontv.data.api.readStreamCapped
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
 * 直播源地址是设置里的 m3uUrl，未配过时默认 https://live.ciweihaha.win/live.m3u
 * （见 SettingsDataStore.DEFAULT_M3U_URL）；用户可在设置里改，清空后即无直播源。
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
            // D8-P2-8：空地址不算一次加载——复位失败信号再返回，否则一次
            // 失败后用户清空 M3U 地址，loadFailed 仍滞留 true，后续调用方
            // 会误报“刷新失败”。（现调用方均先判空，不可达，仅作防御。）
            if (m3uUrl.isBlank()) {
                val decision = decideEmptyAddressLoad()
                _loadFailed.value = decision.loadFailed
                return@withContext decision.channels
            }
            val cached = cacheStore.readM3uCache(m3uUrl)
            if (!forceRefresh && cached != null && nowMs - cached.ts <= LiveController.M3U_CACHE_TTL_MS) {
                _loadFailed.value = false
                return@withContext cached.channels
            }
            val fresh = fetchChannels(m3uUrl)
            val decision = decideLoad(cached?.channels, fresh)
            _loadFailed.value = decision.loadFailed
            // D9-P2-8：只有成功且非空才落盘——合法空源不覆盖旧缓存（下次
            // 重拉即回），拉取失败更不覆盖。
            if (fresh is LiveFetchResult.Success && fresh.value.isNotEmpty()) {
                cacheStore.saveM3uCache(m3uUrl, fresh.value)
            }
            // 拉网失败：回落旧缓存（不断流），没有才返回空列表
            decision.channels
        }

    companion object {
        /**
         * 第 9 轮 D9-P2-7：空地址加载判定（纯函数，可单测）——空地址不算
         * 一次加载，返回空表且 loadFailed 复位为 false；[loadChannels]
         * 的空地址分支直接用它，复位逻辑不再只活在不可达的分支里。
         */
        fun decideEmptyAddressLoad(): M3uLoadDecision =
            M3uLoadDecision(emptyList(), loadFailed = false)

        /**
         * 第 6 轮 D6-P2-13：加载结果判定（纯函数，可单测）——fresh 成功
         * 即用 fresh、失败标记 false；拉取失败则回落旧缓存并标记失败。
         * 第 9 轮 D9-P2-8：成功但空表（源本身合法 0 台）不再恒报失败——
         * 返回空表、loadFailed=false；只有 [LiveFetchResult.Failed]
         * 才回落旧缓存并标记失败。
         */
        fun decideLoad(
            cachedChannels: List<M3uParser.Channel>?,
            fresh: LiveFetchResult<List<M3uParser.Channel>>,
        ): M3uLoadDecision =
            when (fresh) {
                is LiveFetchResult.Success -> M3uLoadDecision(fresh.value, loadFailed = false)
                LiveFetchResult.Failed -> M3uLoadDecision(cachedChannels.orEmpty(), loadFailed = true)
            }
    }

    /**
     * 纯拉网（15 秒超时、5MB 上限；协程取消原样抛出）。
     * 第 9 轮 D9-P2-8：返回密封结果区分“拉取失败”与“成功但空表”——
     * HTTP 失败/体超限/解析抛错 → Failed；HTTP 200 且解析完成 → Success
     * （哪怕 0 台，源本身合法 0 台时不再恒报“刷新失败”）。
     */
    suspend fun fetchChannels(m3uUrl: String): LiveFetchResult<List<M3uParser.Channel>> = withContext(ioDispatcher) {
        if (m3uUrl.isBlank()) return@withContext LiveFetchResult.Success(emptyList())
        try {
            val request = Request.Builder().url(m3uUrl).get().build()
            playlistClient.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return@withContext LiveFetchResult.Failed
                // A4-P2-2：流式限量读，超 5MB 即停，不先整读 body 再判上限。
                val body = readBodyCapped(response, ApiClient.MAX_M3U_BYTES)
                    ?: return@withContext LiveFetchResult.Failed
                LiveFetchResult.Success(M3uParser.parse(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LiveFetchResult.Failed
        }
    }
}

/**
 * 第 9 轮 D9-P2-8：直播源拉取结果（密封，区分“拉取失败”与“成功但空表”）——
 * 旧实现里 fetch 成功但解析出 0 台/0 节目与拉取失败都用“空”表示，decideLoad
 * 一律报 loadFailed=true，合法空源每次刷新都提示“刷新失败，仍用旧数据”，
 * 文案与事实不符；现在只有 [Failed] 才回落旧缓存并标记失败。
 */
sealed interface LiveFetchResult<out T> {
    /** 拉取成功（含合法空表：源本身 0 台/0 节目）。 */
    data class Success<T>(val value: T) : LiveFetchResult<T>

    /** 拉取失败（HTTP 失败/体超限/解析抛错）。 */
    data object Failed : LiveFetchResult<Nothing>
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
         * 第 9 轮 D9-P1-1：只判非空不够——实测 epg.xml 仍只覆盖昨天（2026-11-14），
         * 拉回的过期文件节目非空，旧实现当成功落盘、全天无节目单且无任何失败
         * 信号；现在还要有未播完的节目（[EpgParser.hasFutureProgramme]）才算
         * 新鲜可落盘，否则按失败处理（不落盘、loadFailed=true），UI 才能提示。
         * 注意：这修的是客户端检测与失败信号，服务端发布节奏问题仍在服务端。
         */
        fun shouldSaveFreshEpg(fresh: EpgParser.EpgData?, nowMs: Long = System.currentTimeMillis()): Boolean =
            fresh != null && fresh.programmesByChannel.isNotEmpty() &&
                EpgParser.hasFutureProgramme(fresh, nowMs)

        /**
         * 第 7 轮 D7-P2-2：EPG 加载结果判定（纯函数，可单测；与
         * [M3uRepository.decideLoad] 同款）——fresh 成功即用 fresh、失败
         * 标记 false；拉取失败则回落旧缓存并标记失败。
         * 第 9 轮 D9-P1-1：fresh 非空但全部过期（没有任何 endAt > now 的节目）
         * 不当成功——不落盘、回落旧缓存、loadFailed=true。
         * 第 9 轮 D9-P2-8：fresh 成功但空表（源本身合法 0 节目）按成功处理——
         * 返回空表、loadFailed=false，不恒报“刷新失败”。
         */
        fun decideLoad(
            cachedData: EpgParser.EpgData?,
            fresh: LiveFetchResult<EpgParser.EpgData>,
            nowMs: Long = System.currentTimeMillis(),
        ): EpgLoadDecision =
            when (fresh) {
                is LiveFetchResult.Success -> {
                    val data = fresh.value
                    if (data.programmesByChannel.isEmpty()) {
                        // D9-P2-8：合法空源，按成功处理（不落盘，旧缓存留着）。
                        // 第 10 轮 D10-P2-1：返回数据时优先用仍新鲜的旧缓存——
                        // 服务端偶尔吐一次 0 节目空文件时，旧节目单比“当次无节目单”
                        // 强（P1-5 同款原则）；旧缓存本身也已过期时无可用的节目
                        // 可回落，返回空表（与之前一致）。loadFailed 仍为 false。
                        val fallback = cachedData?.takeIf { EpgParser.hasFutureProgramme(it, nowMs) }
                        EpgLoadDecision(fallback ?: data, loadFailed = false)
                    } else if (shouldSaveFreshEpg(data, nowMs)) {
                        EpgLoadDecision(data, loadFailed = false)
                    } else {
                        // D9-P1-1：非空但全部过期，不当成功。
                        EpgLoadDecision(cachedData, loadFailed = true)
                    }
                }
                LiveFetchResult.Failed -> EpgLoadDecision(cachedData, loadFailed = true)
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
            val decision = decideLoad(cached?.data, fresh, nowMs)
            _loadFailed.value = decision.loadFailed
            // 空 EPG 不覆盖好缓存：服务端偶尔吐空表时，旧缓存比“无节目单”强（P1-5；M3U 早有同款守卫）。
            // D9-P1-1：过期数据同样不落盘（shouldSaveFreshEpg 已含时效判据）。
            if (fresh is LiveFetchResult.Success && shouldSaveFreshEpg(fresh.value, nowMs)) {
                cacheStore.saveEpgCache(fresh.value)
            }
            decision.data
        }

    /**
     * 纯拉网（15 秒超时、3MB 上限；协程取消原样抛出）。
     * 第 9 轮 D9-P2-8：返回密封结果区分“拉取失败”与“成功但空表”（同 fetchChannels）。
     * 第 74 轮：失败不再静默——之前这里 catch 后直接返回 Failed，线上出问题
     * 时 logcat 里没有任何 EPG 失败记录，无法区分 HTTP 失败、体超限、解析
     * 抛错（v2.0.9 的节目单故障就是这么漏过去的）。现在分别记：HTTP 状态码、
     * 响应体问题、异常类型与消息；成功记 body 大小与解析出的台数/节目数。
     */
    suspend fun fetchEpg(): LiveFetchResult<EpgParser.EpgData> = withContext(ioDispatcher) {
        try {
            val request = Request.Builder().url(EPG_URL).get().build()
            playlistClient.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w("EpgRepository", "EPG 拉取失败：HTTP ${response.code}")
                    return@withContext LiveFetchResult.Failed
                }
                // A4-P2-2：流式限量读，超 3MB 即停，不先整读 body 再判上限。
                val body = readBodyCapped(response, ApiClient.MAX_CACHE_BYTES)
                    ?: run {
                        AppLog.w(
                            "EpgRepository",
                            "EPG 拉取失败：响应体为空或超 ${ApiClient.MAX_CACHE_BYTES} 字节上限",
                        )
                        return@withContext LiveFetchResult.Failed
                    }
                val data = EpgParser.parse(body)
                val totalProgrammes = data.programmesByChannel.values.sumOf { it.size }
                AppLog.i(
                    "EpgRepository",
                    "EPG 拉取成功：${body.length} 字节，${data.programmesByChannel.size} 台，" +
                        "$totalProgrammes 条节目",
                )
                LiveFetchResult.Success(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("EpgRepository", "EPG 拉取失败：${e::class.java.simpleName}：${e.message}")
            LiveFetchResult.Failed
        }
    }
}

/**
 * 流式限量读响应体（A4-P2-2）：Content-Length 先行拒绝，之后按 64KB 块流式读，
 * 累计超上限即停并返回 null，不把超大体整读进内存再判。
 * 第 7 轮 D7-P2-4：改 suspend + 可取消读（与 ApiClient.readBodyBytesLimited
 * 同款，取消时关响应体、中断阻塞的 socket 读）；超限时 readStreamCapped
 * 抛 BodyTooLargeException，这里按旧语义返回 null。
 */
internal suspend fun readBodyCapped(response: okhttp3.Response, maxBytes: Long): String? {
    val declared = response.header("Content-Length")?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val body = response.body ?: return null
    val bytes = try {
        body.byteStream().use { input ->
            readStreamCapped(input, maxBytes) { runCatching { body.close() } }
        }
    } catch (e: BodyTooLargeException) {
        return null
    }
    return decodeBodyBytes(bytes, response.header("Content-Type"))
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
