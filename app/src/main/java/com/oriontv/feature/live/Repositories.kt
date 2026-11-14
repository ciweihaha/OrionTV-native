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
 * M3U 仓库骨架（plan §5.3）：
 * 直播源地址是用户在设置里填的 m3uUrl（默认空串），代码里不写死地址；
 * 拉取 15 秒超时、体积上限 5MB，失败返回空列表（不抛错）。
 * 缓存：TTL 7 天、JSON 超 3MB 不落盘（M2 接 DataStore/Room 落盘）。
 */
@Singleton
class M3uRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
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
 * EPG 仓库骨架（plan §5.3）：
 * 地址硬编码 https://ysp.ciweihaha.win/epg.xml；拉取 15 秒超时；
 * 失败返回 null，不覆盖旧缓存。缓存 TTL 6 小时 + “仍有正在播的节目”判据（M2 接落盘）。
 */
@Singleton
class EpgRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val EPG_URL = "https://ysp.ciweihaha.win/epg.xml"
    }

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
