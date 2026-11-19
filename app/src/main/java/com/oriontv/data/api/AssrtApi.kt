package com.oriontv.data.api

import com.oriontv.feature.play.isSubtitleZip
import com.oriontv.feature.play.isSupportedSubtitleFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Assrt 在线搜字幕的一条搜索结果（搜索接口返回的 sub）。 */
data class AssrtSubtitle(
    /** 字幕组 id（查详情用）。 */
    val id: Int,
    /** 展示名（native_name / title / filename / videoname 依次取首个非空）。 */
    val name: String,
    /** 语言说明（lang 是字符串直接取，是对象取 desc；都没有为空串）。 */
    val lang: String,
)

/** Assrt 详情里可下载的一个字幕文件（filelist 的一项，或 sub 自身的 url）。 */
data class AssrtSubtitleFile(
    /** 所属字幕组 id。 */
    val id: Int,
    /** 文件名（f / filename / name，缺失时回落到组名）。 */
    val name: String,
    /** 下载地址（可能是 .zip 或直传字幕文件）。 */
    val url: String,
    /** 语言说明。 */
    val lang: String,
)

/**
 * Assrt 在线字幕（第 76 轮 v2.0.15）：射手网字幕搜索/详情/下载。
 * 接口与 FongMi SubtitleApi 同款：
 * - 搜索 `GET https://api.assrt.net/v1/sub/search?q={关键词}&cnt=15&pos=0&filelist=1&token={token}`
 * - 详情 `GET https://api.assrt.net/v1/sub/detail?id={id}&token={token}`
 * - 响应 `{"status":0,"sub":{"subs":[...]}}`；status 非 0 时 errmsg/message 是错误。
 * - **token 必填**：无 token 搜返回 `{"status":20001,"errmsg":"invalid token"}`。
 *   令牌存在设置里（SettingsDataStore.assrtToken），用户去 assrt.net 免费申请。
 * - 下载地址可能是 .zip（解压后挑第一个支持的字幕文件）或直传字幕文件。
 * 网络层只做单次请求（用户主动搜，失败直接报，不静默重试）；解析是纯函数，可单测。
 */
@Singleton
class AssrtApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val SEARCH_URL = "https://api.assrt.net/v1/sub/search"
        const val DETAIL_URL = "https://api.assrt.net/v1/sub/detail"
        const val SEARCH_COUNT = 15
        /** 单次请求超时（毫秒）；在线搜字幕是用户主动操作，15 秒够用。 */
        const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * 搜索响应解析（可单测）：status 非 0 抛 IOException（errmsg/message）；
         * 只留 id>0 且有文件（无 filelist 时认为有文件，有 filelist 时至少一项
         * 是支持的字幕）；展示名按 native_name/title/filename/videoname 取首个非空。
         */
        fun parseSearchResponse(json: Json, text: String): List<AssrtSubtitle> {
            val root = json.parseToJsonElement(text).jsonObject
            checkStatus(root)
            val subs = root["sub"]?.jsonObject?.get("subs")?.jsonArray ?: return emptyList()
            return subs.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
                if (id <= 0) return@mapNotNull null
                val files = obj["filelist"]?.jsonArray
                if (files != null && files.none { file ->
                        isSupportedSubtitleFile(file.jsonObject["f"]?.jsonPrimitive?.contentOrNull.orEmpty()) ||
                            isSupportedSubtitleFile(file.jsonObject["filename"]?.jsonPrimitive?.contentOrNull.orEmpty()) ||
                            isSupportedSubtitleFile(file.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }) {
                    return@mapNotNull null
                }
                AssrtSubtitle(
                    id = id,
                    name = firstNonBlank(
                        obj["native_name"]?.jsonPrimitive?.contentOrNull,
                        obj["title"]?.jsonPrimitive?.contentOrNull,
                        obj["filename"]?.jsonPrimitive?.contentOrNull,
                        obj["videoname"]?.jsonPrimitive?.contentOrNull,
                    ),
                    lang = parseLang(obj["lang"]),
                )
            }
        }

        /**
         * 详情响应解析（可单测）：每个 sub 的 filelist 逐项成文件（只留支持的
         * 字幕扩展名）；无 filelist 时回落到 sub 自身的 url（支持才留）。
         * 空结果抛 IOException（“没有可下载的字幕文件”）。
         */
        fun parseDetailResponse(json: Json, text: String): List<AssrtSubtitleFile> {
            val root = json.parseToJsonElement(text).jsonObject
            checkStatus(root)
            val subs = root["sub"]?.jsonObject?.get("subs")?.jsonArray ?: return emptyList()
            val items = mutableListOf<AssrtSubtitleFile>()
            for (element in subs) {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
                val lang = parseLang(obj["lang"])
                val fallbackName = firstNonBlank(
                    obj["filename"]?.jsonPrimitive?.contentOrNull,
                    obj["native_name"]?.jsonPrimitive?.contentOrNull,
                    obj["title"]?.jsonPrimitive?.contentOrNull,
                    obj["videoname"]?.jsonPrimitive?.contentOrNull,
                )
                val files = obj["filelist"]?.jsonArray
                if (files != null) {
                    for (fileElement in files) {
                        val fileObj = fileElement.jsonObject
                        val name = firstNonBlank(
                            fileObj["f"]?.jsonPrimitive?.contentOrNull,
                            fileObj["filename"]?.jsonPrimitive?.contentOrNull,
                            fileObj["name"]?.jsonPrimitive?.contentOrNull,
                            fallbackName,
                        )
                        val url = fileObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isNotBlank() && isSupportedSubtitleFile(name)) {
                            items.add(AssrtSubtitleFile(id, name, url, lang))
                        }
                    }
                }
                if (files == null) {
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isNotBlank() && isSupportedSubtitleFile(fallbackName)) {
                        items.add(AssrtSubtitleFile(id, fallbackName, url, lang))
                    }
                }
            }
            if (items.isEmpty()) throw IOException("没有可下载的字幕文件")
            return items
        }

        /** status 非 0 抛错（errmsg/message 为空时用 status 码）。 */
        private fun checkStatus(root: JsonObject) {
            val status = root["status"]?.jsonPrimitive?.intOrNull ?: -1
            if (status != 0) {
                val message = root["errmsg"]?.jsonPrimitive?.contentOrNull
                    ?: root["message"]?.jsonPrimitive?.contentOrNull
                throw IOException(message?.trim()?.takeIf { it.isNotEmpty() } ?: "字幕服务错误（$status）")
            }
        }

        /** lang 可能是字符串或对象（取 desc）；都没有为空串。 */
        private fun parseLang(element: kotlinx.serialization.json.JsonElement?): String {
            if (element == null) return ""
            return try {
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive ->
                        element.contentOrNull?.trim().orEmpty()
                    is JsonObject ->
                        element["desc"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    else -> ""
                }
            } catch (_: Exception) {
                ""
            }
        }

        private fun firstNonBlank(vararg values: String?): String {
            for (value in values) {
                if (!value.isNullOrBlank()) return value.trim()
            }
            return ""
        }
    }

    /** 在线搜字幕（关键词→结果列表）；token 为空时直接抛错，不发请求。 */
    suspend fun search(keyword: String, token: String): List<AssrtSubtitle> {
        if (token.isBlank()) throw IOException("请先在字幕设置里填写 Assrt 令牌（assrt.net 免费申请）")
        val url = "$SEARCH_URL?q=${java.net.URLEncoder.encode(keyword, "UTF-8")}" +
            "&cnt=$SEARCH_COUNT&pos=0&filelist=1&token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val text = getText(url)
        return parseSearchResponse(json, text)
    }

    /** 查详情（字幕组 id → 可下载的文件列表）。 */
    suspend fun detail(id: Int, token: String): List<AssrtSubtitleFile> {
        if (token.isBlank()) throw IOException("请先在字幕设置里填写 Assrt 令牌（assrt.net 免费申请）")
        val url = "$DETAIL_URL?id=$id&token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val text = getText(url)
        return parseDetailResponse(json, text)
    }

    /**
     * 下载字幕文件到 [destDir]：直传字幕文件直接落盘；.zip 解压后挑第一个支持的
     * 字幕文件落盘（其余丢弃）。返回落盘的字幕文件。下载失败/zip 里无字幕抛 IOException。
     */
    suspend fun downloadToFile(file: AssrtSubtitleFile, destDir: File): File =
        withContext(ioDispatcher) {
            if (!destDir.exists()) destDir.mkdirs()
            val request = Request.Builder().url(file.url).build()
            val client = okHttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("字幕下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("字幕下载失败（空响应）")
            val safeName = file.name.substringAfterLast('/').substringAfterLast('\\')
                .takeIf { it.isNotBlank() } ?: "subtitle.srt"
            val destFile = File(destDir, safeName)
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            if (isSubtitleZip(destFile.name)) {
                val extracted = unzipFirstSubtitle(destFile, destDir)
                destFile.delete()
                extracted ?: throw IOException("压缩包里没有可用的字幕文件")
            } else {
                destFile
            }
        }

    /** 解压 zip，返回第一个支持的字幕文件（按扩展名）；没有返回 null。 */
    private fun unzipFirstSubtitle(zipFile: File, destDir: File): File? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (isSupportedSubtitleFile(name)) {
                        val outFile = File(destDir, name)
                        FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                        return outFile
                    }
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private suspend fun getText(url: String): String = withContext(ioDispatcher) {
        val request = Request.Builder().url(url).build()
        val client = okHttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("字幕服务请求失败（HTTP ${response.code}）")
        }
        response.body?.string() ?: throw IOException("字幕服务返回空响应")
    }
}
