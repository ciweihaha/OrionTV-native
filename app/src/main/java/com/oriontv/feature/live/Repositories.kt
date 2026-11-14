package com.oriontv.feature.live

import com.oriontv.data.api.ApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3U 仓库（plan §5.3）：
 * 直播源地址是用户在设置里填的 m3uUrl（默认空串），代码里不写死地址；
 * 拉取 15 秒超时、体积上限 5MB，失败返回空列表（不抛错）。
 * 缓存：TTL 7 天、JSON 超 3MB 不落盘（plan §5.4）；缓存秒显 + 后台刷新：
 * 缓存新鲜直接用，过期才拉网，拉网失败回落旧缓存。
 */
@Singleton
class M3uRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cacheStore: LiveCacheStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 缓存优先加载：缓存新鲜直接返回；过期拉网，成功落盘，失败回落旧缓存。 */
    suspend fun loadChannels(m3uUrl: String, nowMs: Long = System.currentTimeMillis()): List<M3uParser.Channel> =
        withContext(ioDispatcher) {
            if (m3uUrl.isBlank()) return@withContext emptyList()
            val cached = cacheStore.readM3uCache(m3uUrl)
            if (cached != null && nowMs - cached.ts <= LiveController.M3U_CACHE_TTL_MS) {
                return@withContext cached.channels
            }
            val fresh = fetchChannels(m3uUrl)
            if (fresh.isNotEmpty()) {
                cacheStore.saveM3uCache(m3uUrl, fresh)
                return@withContext fresh
            }
            // 拉网失败：回落旧缓存（不断流），没有才返回空列表
            cached?.channels.orEmpty()
        }

    /** 纯拉网（15 秒超时、5MB 上限，失败返回空列表，不抛错）。 */
    suspend fun fetchChannels(m3uUrl: String): List<M3uParser.Channel> = withContext(ioDispatcher) {
        if (m3uUrl.isBlank()) return@withContext emptyList()
        runCatching {
            val client = okHttpClient.newBuilder()
                .readTimeout(ApiClient.PLAYLIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(m3uUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                if (body.toByteArray().size > ApiClient.MAX_M3U_BYTES) return@withContext emptyList()
                M3uParser.parse(body)
            }
        }.getOrDefault(emptyList())
    }
}

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
    companion object {
        const val EPG_URL = "https://ysp.ciweihaha.win/epg.xml"
    }

    /**
     * 缓存优先加载：缓存新鲜（TTL 内且仍有正在播的节目）直接返回；
     * 否则拉网，成功落盘，失败回落旧缓存（哪怕已过期，也比无节目单强）。
     */
    suspend fun loadEpg(nowMs: Long = System.currentTimeMillis()): EpgParser.EpgData? =
        withContext(ioDispatcher) {
            val cached = cacheStore.readEpgCache()
            if (cached != null && EpgParser.isCacheFresh(cached.data, cached.ts, nowMs)) {
                return@withContext cached.data
            }
            val fresh = fetchEpg()
            if (fresh != null) {
                cacheStore.saveEpgCache(fresh)
                return@withContext fresh
            }
            cached?.data
        }

    /** 纯拉网（15 秒超时、3MB 上限，失败返回 null，不抛错）。 */
    suspend fun fetchEpg(): EpgParser.EpgData? = withContext(ioDispatcher) {
        runCatching {
            val client = okHttpClient.newBuilder()
                .readTimeout(ApiClient.PLAYLIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(EPG_URL).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.toByteArray().size > ApiClient.MAX_CACHE_BYTES) return@withContext null
                EpgParser.parse(body)
            }
        }.getOrNull()
    }
}
