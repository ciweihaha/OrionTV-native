package com.oriontv.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import kotlin.coroutines.cancellation.CancellationException

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
    /** 重试退避（毫秒），默认 1 秒固定；单测可注入 0 以免真睡。 */
    private val retryBackoffMs: Long = ApiClient.RETRY_BACKOFF_MS,
) {
    /**
     * 401 续期回调：由 Auth 层注入（用已保存凭据重登）。
     * 返回 true 表示续期成功，可对幂等请求重试一次。
     */
    var onUnauthorized: (suspend () -> Boolean)? = null

    /**
     * 播放记录/收藏键在 URL 里的转义：`%`→`%25`、`+`→`%2B`（plan §5.2）。
     * 旧整键转义（会误转分隔符 `+`），已由伴生对象 [generateKey] 取代，仅保留兼容；
     * 请求方法不再对整键调用它，只做一次 URLEncoder.encode。
     */
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
                // 响应体解不出来时不算登录成功（默认 false；之前默认 true 会误认登录成功）。
                runCatching { json.decodeFromString<LoginResponse>(text).ok }.getOrDefault(false)
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
        val url = "${baseUrl.trimEnd('/')}/api/douban?type=${URLEncoder.encode(type, "UTF-8")}" +
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
            decodeSearchResults(body)
        }

    /**
     * 单源搜索：客户端再做子串过滤（归一化去空白小写），只留标题含关键词的结果。
     * 空响应体/缺 results 字段时当空结果。
     */
    suspend fun searchVideo(baseUrl: String, query: String, resourceId: String): List<SearchResult> =
        withContext(ioDispatcher) {
            // 空查询不过滤会把所有标题都含空串的结果全放回来：直接返回空列表，不发请求。
            if (query.isBlank()) return@withContext emptyList()
            val url = "${baseUrl.trimEnd('/')}/api/search/one?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&resourceId=${URLEncoder.encode(resourceId, "UTF-8")}"
            val body = execute(Request.Builder().url(url).get().build())
            val list = decodeSearchResults(body)
            val normalizedQuery = normalizeTitle(query)
            list.filter { it.title.isNotBlank() && normalizeTitle(it.title).contains(normalizedQuery) }
        }

    /**
     * 视频源列表。外层仅超时重试 1 次（第 9 轮）：4xx、体超限等确定性失败
     * 在 execute 里已 fail fast，这里不能再兜回来多发一次；空体直接返回空列表，
     * 整表解码失败时逐条容错保留可解析条目（P1-8）。
     */
    suspend fun getResources(baseUrl: String): List<ApiSite> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/search/resources"
        val request = Request.Builder().url(url).get().build()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val body = execute(request)
                if (body.isBlank()) return@withContext emptyList()
                return@withContext try {
                    json.decodeFromString<List<ApiSite>>(body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 整表解码失败时保留可解析条目；连 JSON 数组都不是才原样抛错。
                    val raw = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                        ?: throw e
                    LenientDecode.decodeList(json, raw, ApiSite.serializer())
                }
            } catch (e: IOException) {
                // 仅超时重试 1 次：4xx、体超限等确定性失败立即抛
                //（第 6/7/8 轮已让它们 fail fast，这里不能再兜回来多发一次）。
                if (!AggApi.isBodyTimeout(e)) throw e
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
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            // 服务端可能返回整表 Record，也可能返回单条 Favorite。
            // 整表解码失败时保留可解析条目（P1-8），而不是整个变空；
            // 整表逐条解出来是空时，再看对象里有没有 Favorite 的字段名，
            // 有才按单条解（Favorite 全字段有默认值，直接试单条会把整表误认成单条）。
            runCatching { json.decodeFromString<Map<String, Favorite>>(body) }.getOrElse {
                val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                if (raw == null) {
                    runCatching {
                        val single = json.decodeFromString<Favorite>(body)
                        mapOf((key ?: "") to single)
                    }.getOrDefault(emptyMap())
                } else {
                    val lenient = LenientDecode.decodeMap(json, raw, Favorite.serializer())
                    if (lenient.isNotEmpty()) {
                        lenient
                    } else if (raw.keys.any { it in FAVORITE_KEYS }) {
                        runCatching {
                            val single = json.decodeFromString<Favorite>(body)
                            mapOf((key ?: "") to single)
                        }.getOrDefault(emptyMap())
                    } else {
                        emptyMap()
                    }
                }
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
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(true)
        }

    // ---- 播放记录（服务端模式） ----

    suspend fun getPlayRecords(baseUrl: String): Map<String, PlayRecord> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords"
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            // 整表解码失败时保留可解析条目（P1-8），而不是整个变空。
            runCatching { json.decodeFromString<Map<String, PlayRecord>>(body) }.getOrElse {
                val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                raw?.let { LenientDecode.decodeMap(json, it, PlayRecord.serializer()) }
                    ?: emptyMap()
            }
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
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
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
     *
     * 4xx 抛 [NonRetryableHttpException]、响应体超限抛 [ResponseTooLargeException]，
     * 两者都由专用 catch 原样抛出，不会被下面的 `catch (e: IOException)` 接住重跑
     * （P0-2；第 6/7/8 轮约定）。401 续期每趟 execute 最多一次，
     * 否则服务端持续 401 时会连登 4 次（P2-10）。协程取消原样抛出，不重试（P2）。
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
        var refreshAttempted = false
        repeat(ApiClient.MAX_RETRIES + 1) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        if (!refreshAttempted) {
                            refreshAttempted = true
                            val refreshed = onUnauthorized?.invoke() ?: false
                            if (refreshed && ApiClient.isIdempotent(request.method)) {
                                return@repeat // 外层循环再发一次（幂等重试）
                            }
                        }
                        throw UnauthorizedException("登录已过期，请重新登录")
                    }
                    if (!response.isSuccessful) {
                        if (!ApiClient.shouldRetryOnStatus(response.code)) {
                            // 4xx 不重试：必须用独立异常类型抛出，否则会被下面的 IOException
                            // catch 吞掉并继续重试（与文档“4xx 不重试”相悖；同 AggApi 第 6 轮）。
                            throw NonRetryableHttpException(response.code)
                        }
                        throw IOException("HTTP ${response.code}")
                    }
                    return readBodyLimited(response)
                }
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: NonRetryableHttpException) {
                throw IOException("HTTP ${e.statusCode}，不重试")
            } catch (e: ResponseTooLargeException) {
                // 响应体超限是确定性失败：重发只会再下载一遍同样的超大体，不重试（第 8 轮）
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                // 4xx 已在上面直接抛；这里只剩超时/网络错/5xx，可重试
                if (attempt < ApiClient.MAX_RETRIES) delay(retryBackoffMs)
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    /** 搜索结果解码：空体/缺 results 字段时当空结果；单条脏数据只丢该条，不丢整表。 */
    private fun decodeSearchResults(body: String): List<SearchResult> {
        if (body.isBlank()) return emptyList()
        val response = runCatching { json.decodeFromString<SearchResponse>(body) }.getOrNull()
        if (response != null) return response.results ?: emptyList()
        val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val results = raw["results"] as? JsonArray ?: return emptyList()
        return LenientDecode.decodeList(json, results, SearchResult.serializer())
    }

    /** 读响应体并做 2MB 上限校验（Content-Length 先行拒绝 + 事后字节校验）。 */
    private fun readBodyLimited(response: okhttp3.Response): String {
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > ApiClient.MAX_BODY_BYTES) {
            throw ResponseTooLargeException("响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        val body = response.body?.string().orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > ApiClient.MAX_BODY_BYTES) {
            throw ResponseTooLargeException("响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        return body
    }

    companion object {
        /** 单条 Favorite 的字段名（整表单条兜底时用来认形状）。 */
        private val FAVORITE_KEYS =
            setOf("cover", "title", "source_name", "total_episodes", "search_title")

        /** 标题归一化：去空白、小写（搜索子串过滤与精确标题匹配共用）。 */
        fun normalizeTitle(title: String): String =
            title.lowercase().replace(Regex("\\s+"), "")

        /**
         * 服务端键（数据层 P1-1 约定，与 RN 一致）：分段转义内部 %/+ 后用 + 连接；
         * 拆键时按最后一个 + 切分（见 PlayViewModel.retry）。
         * 数据层代理合入同名实现时以该实现为准，两者语义一致，不冲突。
         */
        fun generateKey(source: String, id: String): String =
            escapeKeySegment(source) + "+" + escapeKeySegment(id)

        /** 键段转义：%→%25、+→%2B（与 [MoonTvApi.escapeKey] 同款规则，按段做）。 */
        private fun escapeKeySegment(segment: String): String =
            segment.replace("%", "%25").replace("+", "%2B")
    }
}

class UnauthorizedException(message: String) : Exception(message)

/** 响应体超限（>2MB）：确定性失败，不重试（仍是 IOException，调用方照常捕获）。 */
internal class ResponseTooLargeException(message: String) : IOException(message)
