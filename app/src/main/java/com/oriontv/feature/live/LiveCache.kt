package com.oriontv.feature.live

import android.content.Context
import com.oriontv.data.api.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** M3U 落盘缓存（plan §5.4：`mytv_m3u_cache_<m3uUrl>`，TTL 7 天；M3U JSON 上限 5MB，与拉取上限对齐）。 */
@Serializable
data class M3uCache(
    val ts: Long,
    val channels: List<M3uParser.Channel>,
)

/** EPG 落盘缓存（plan §5.4：`mytv_epg_cache_<epgUrl>`，TTL 6 小时 + “仍有正在播节目”判据，3MB 上限）。 */
@Serializable
data class EpgCache(
    val ts: Long,
    val data: EpgParser.EpgData,
)

/** 缓存编解码（纯函数，可单测）。 */
object LiveCacheCodec {
    /**
     * M3U 缓存上限 5MB，与拉取上限（ApiClient.MAX_M3U_BYTES）对齐：
     * 之前拉取放 5MB、缓存只放 3MB，3–5MB 的源每次都重下、永无离线。
     * EPG 仍用 3MB（拉取也是 3MB，两处本来就一致）。
     */
    const val MAX_M3U_CACHE_BYTES = ApiClient.MAX_M3U_BYTES

    fun encodeM3u(cache: M3uCache, json: Json): String? {
        val text = json.encodeToString(cache)
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_M3U_CACHE_BYTES }
    }

    fun decodeM3u(text: String, json: Json): M3uCache? =
        runCatching { json.decodeFromString<M3uCache>(text) }.getOrNull()
            // 脏缓存（urls:[] 的频道）解码成功后 Channel.url 会崩溃：
            // 解码时直接滤掉 urls 为空的频道（url 本身也已改 firstOrNull 兜底）。
            ?.let { cache -> cache.copy(channels = cache.channels.filter { it.urls.isNotEmpty() }) }

    fun encodeEpg(cache: EpgCache, json: Json): String? {
        val text = json.encodeToString(cache)
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= ApiClient.MAX_CACHE_BYTES }
    }

    fun decodeEpg(text: String, json: Json): EpgCache? =
        runCatching { json.decodeFromString<EpgCache>(text) }.getOrNull()
}

/**
 * 直播缓存落盘（Context.filesDir 私有文件，与 plan §5.4 的键名一一对应，
 * 只是载体从 AsyncStorage 换成文件 + DataStore 混用会让键分散，故 M3U/EPG 这两个
 * 大 JSON 走文件；上次频道/收藏仍走 SettingsDataStore 的同名键）。
 */
@Singleton
class LiveCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private fun m3uFile(m3uUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(m3uUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(context.filesDir, "m3u_cache_$digest.json")
    }

    private fun epgFile(): File = File(context.filesDir, "epg_cache.json")

    fun readM3uCache(m3uUrl: String): M3uCache? {
        val file = m3uFile(m3uUrl)
        if (!file.exists()) return null
        return runCatching { LiveCacheCodec.decodeM3u(file.readText(), json) }.getOrNull()
    }

    fun saveM3uCache(m3uUrl: String, channels: List<M3uParser.Channel>) {
        val encoded = LiveCacheCodec.encodeM3u(
            M3uCache(ts = System.currentTimeMillis(), channels = channels),
            json,
        ) ?: return // 超 5MB 不落盘（与拉取上限对齐）
        val file = m3uFile(m3uUrl)
        runCatching { file.writeText(encoded) }
        // D-P2-14：换 m3u 地址后删掉旧地址的孤儿缓存文件（只留当前这一个）。
        runCatching { pruneStaleM3uCacheFiles(context.filesDir, keepFileName = file.name) }
    }

    fun readEpgCache(): EpgCache? {
        val file = epgFile()
        if (!file.exists()) return null
        return runCatching { LiveCacheCodec.decodeEpg(file.readText(), json) }.getOrNull()
    }

    fun saveEpgCache(data: EpgParser.EpgData) {
        val encoded = LiveCacheCodec.encodeEpg(
            EpgCache(ts = System.currentTimeMillis(), data = data),
            json,
        ) ?: return // 超 3MB 不落盘
        runCatching { epgFile().writeText(encoded) }
    }
}

/**
 * 删掉除当前文件外的 `m3u_cache_*.json`（D-P2-14：换 m3u 地址后旧缓存文件
 * 永久残留）。纯函数逻辑，可单测；其他文件不动。
 */
internal fun pruneStaleM3uCacheFiles(dir: File, keepFileName: String) {
    dir.listFiles { f ->
        f.isFile && f.name.startsWith("m3u_cache_") && f.name.endsWith(".json") &&
            f.name != keepFileName
    }?.forEach { runCatching { it.delete() } }
}
