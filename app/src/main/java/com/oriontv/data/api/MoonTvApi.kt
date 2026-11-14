package com.oriontv.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户 MoonTV 服务（{baseURL}，设置里填，默认空串）。接口路径与 plan §5.2 原样一致。
 * 除注明外 GET、15 秒超时、响应体 2MB 上限；401 时用已保存凭据重登后仅对幂等请求
 * （GET/DELETE/HEAD）重试一次，POST 不自动重试；超时/网络错/5xx 最多重试 3 次，退避 1 秒。
 *
 * 注意：真 cookie 在 OkHttp CookieJar 里（见 AppModule 的 InMemoryCookieJar），
 * 这里只处理登录标记位；GET /api/detail 是死代码（本 HEAD 无调用方），不实现。
 */
@Singleton
class MoonTvApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 401 续期回调：由 Auth 层注入（用已保存凭据重登）。
     * 返回 true 表示续期成功，可对幂等请求重试一次。
     */
    var onUnauthorized: (suspend () -> Boolean)? = null

    /** 播放记录/收藏键在 URL 里的转义：`%`→`%25`、`+`→`%2B`（plan §5.2）。 */
    fun escapeKey(key: String): String = key.replace("%", "%25").replace("+", "%2B")

    // ---- 登录 / 配置 ----

    suspend fun login(baseUrl: String, username: String?, password: String?): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/login"
            val body = buildJsonObject {
                username?.let { put("username", JsonPrimitive(it)) }
                password?.let { put("password", JsonPrimitive(it)) }
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val text = readBodyLimited(response)
                runCatching { json.decodeFromString<LoginResponse>(text).ok }.getOrDefault(true)
            }
        }

    suspend fun logout(baseUrl: String) {
        withContext(ioDispatcher) {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/logout")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            runCatching { okHttpClient.newCall(request).execute().close() }
            // finally 里无条件清登录标记（由调用方清 StorageKeys.AUTH_COOKIES）
        }
    }

    suspend fun getServerConfig(baseUrl: String): ServerConfig = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/server-config".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("API 地址非法：$baseUrl")
        val body = execute(Request.Builder().url(url).get().build())
        json.decodeFromString<ServerConfig>(body)
    }

    // ---- 豆瓣 ----

    suspend fun getDoubanData(
        baseUrl: String,
        type: String,
        tag: String,
        pageSize: Int = 16,
        pageStart: Int = 0,
    ): DoubanResponse = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/douban?type=$type" +
            "&tag=${URLEncoder.encode(tag, "UTF-8")}&pageSize=$pageSize&pageStart=$pageStart"
        val body = execute(Request.Builder().url(url).get().build())
        json.decodeFromString<DoubanResponse>(body)
    }

    // ---- 搜索 ----

    /** 全源搜索：超时放宽到 60 秒；服务端缺 results 字段时当空结果。 */
    suspend fun searchVideos(baseUrl: String, query: String): List<SearchResult> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
            val body = execute(
                Request.Builder().url(url).get().build(),
                timeoutSeconds = ApiClient.SEARCH_TIMEOUT_SECONDS,
            )
            runCatching { json.decodeFromString<SearchResponse>(body).results }.getOrNull()
                ?: emptyList()
        }

    /**
     * 单源搜索：客户端再做子串过滤（归一化去空白小写），只留标题含关键词的结果。
     * 空响应体/缺 results 字段时当空结果。
     */
    suspend fun searchVideo(baseUrl: String, query: String, resourceId: String): List<SearchResult> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/search/one?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&resourceId=${URLEncoder.encode(resourceId, "UTF-8")}"
            val body = execute(Request.Builder().url(url).get().build())
            val list = runCatching { json.decodeFromString<SearchResponse>(body).results }
                .getOrNull() ?: emptyList()
            val normalizedQuery = normalizeTitle(query)
            list.filter { it.title.isNotBlank() && normalizeTitle(it.title).contains(normalizedQuery) }
        }

    /** 资源列表：仅超时重试 1 次（plan §5.2）；空响应体时当空列表。 */
    suspend fun getResources(baseUrl: String): List<ApiSite> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/search/resources"
        val request = Request.Builder().url(url).get().build()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return@withContext json.decodeFromString<List<ApiSite>>(execute(request))
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) delay(ApiClient.RETRY_BACKOFF_MS)
            }
        }
        throw lastError ?: IOException("获取视频源失败")
    }

    // ---- 收藏（服务端模式） ----

    suspend fun getFavorites(baseUrl: String, key: String? = null): Map<String, Favorite> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites" +
                (key?.let { "?key=${URLEncoder.encode(escapeKey(it), "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            // 服务端可能返回整表 Record，也可能返回单条 Favorite。
            runCatching { json.decodeFromString<Map<String, Favorite>>(body) }.getOrElse {
                runCatching {
                    val single = json.decodeFromString<Favorite>(body)
                    mapOf((key ?: "") to single)
                }.getOrDefault(emptyMap())
            }
        }

    suspend fun addFavorite(baseUrl: String, key: String, favorite: Favorite): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites"
            val bodyJson = buildJsonObject {
                put("key", JsonPrimitive(key))
                put("favorite", json.encodeToJsonElement(Favorite.serializer(), favorite))
            }.toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    suspend fun deleteFavorite(baseUrl: String, key: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites" +
                (key?.let { "?key=${URLEncoder.encode(escapeKey(it), "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    // ---- 播放记录（服务端模式） ----

    suspend fun getPlayRecords(baseUrl: String): Map<String, PlayRecord> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords"
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            runCatching { json.decodeFromString<Map<String, PlayRecord>>(body) }
                .getOrDefault(emptyMap())
        }

    suspend fun savePlayRecord(baseUrl: String, key: String, record: PlayRecord): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords"
            val bodyJson = buildJsonObject {
                put("key", JsonPrimitive(key))
                put("record", json.encodeToJsonElement(PlayRecord.serializer(), record))
            }.toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    suspend fun deletePlayRecord(baseUrl: String, key: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords" +
                (key?.let { "?key=${URLEncoder.encode(escapeKey(it), "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    // ---- 搜索历史（服务端模式） ----

    suspend fun getSearchHistory(baseUrl: String): List<String> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/searchhistory"
        val body = execute(Request.Builder().url(url).get().build())
        if (body.isBlank()) return@withContext emptyList()
        runCatching { json.decodeFromString<List<String>>(body) }.getOrDefault(emptyList())
    }

    suspend fun addSearchHistory(baseUrl: String, keyword: String): List<String> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/searchhistory"
            val bodyJson = buildJsonObject { put("keyword", JsonPrimitive(keyword)) }
                .toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            runCatching { json.decodeFromString<List<String>>(body) }.getOrDefault(listOf(keyword))
        }

    suspend fun deleteSearchHistory(baseUrl: String, keyword: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/searchhistory" +
                (keyword?.let { "?keyword=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    // ---- 执行层 ----

    /**
     * 带重试 + 401 续期的执行：超时/网络错/5xx 最多重试 3 次，退避 1 秒；
     * 4xx 不重试；401 时调 onUnauthorized 重登，成功且请求幂等则重试一次；
     * 响应体超 2MB 拒绝。
     */
    private suspend fun execute(
        request: Request,
        timeoutSeconds: Long = ApiClient.DEFAULT_TIMEOUT_SECONDS,
    ): String {
        val client = okHttpClient.newBuilder()
            .connectTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        var lastError: Exception? = null
        repeat(ApiClient.MAX_RETRIES + 1) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        val refreshed = onUnauthorized?.invoke() ?: false
                        if (refreshed && ApiClient.isIdempotent(request.method)) {
                            return@repeat // 外层循环再发一次（幂等重试）
                        }
                        throw UnauthorizedException("登录已过期，请重新登录")
                    }
                    if (!response.isSuccessful) {
                        if (!ApiClient.shouldRetryOnStatus(response.code)) {
                            throw IOException("HTTP ${response.code}，不重试")
                        }
                        throw IOException("HTTP ${response.code}")
                    }
                    return readBodyLimited(response)
                }
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                // 4xx 已在上面直接抛；这里只剩超时/网络错/5xx，可重试
                if (attempt < ApiClient.MAX_RETRIES) delay(ApiClient.RETRY_BACKOFF_MS)
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    /** 读响应体并做 2MB 上限校验（Content-Length 先行拒绝 + 事后字节校验）。 */
    private fun readBodyLimited(response: okhttp3.Response): String {
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > ApiClient.MAX_BODY_BYTES) {
            throw IOException("响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        val body = response.body?.string().orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > ApiClient.MAX_BODY_BYTES) {
            throw IOException("响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        return body
    }

    companion object {
        /** 标题归一化：去空白、小写（搜索子串过滤与精确标题匹配共用）。 */
        fun normalizeTitle(title: String): String =
            title.lowercase().replace(Regex("\\s+"), "")
    }
}

class UnauthorizedException(message: String) : Exception(message)
