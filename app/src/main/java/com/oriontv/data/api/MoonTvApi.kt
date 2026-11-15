package com.oriontv.data.api

import com.oriontv.core.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 登录会话口（D-P1-4）：AuthRepository 只依赖登录/登出/401 续期三个成员，
 * 单测可用内存假实现，不用 MockK、不用真网。
 * getFavorites 供 B-P2-16 冷启动轻量会话校验用（只判 401/网络错，不读内容）。
 */
interface AuthApi {
    var onUnauthorized: (suspend () -> Boolean)?
    /** B4-P1-2：分型返回（见 [MoonTvApi.LoginResult]），只有 WrongCredentials 才算密码错。 */
    suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult
    suspend fun logout(baseUrl: String)
    suspend fun getFavorites(baseUrl: String, key: String? = null): Map<String, Favorite>
}

/**
 * 用户 MoonTV 服务（{baseURL}，设置里填，默认空串）。接口路径与 plan §5.2 原样一致。
 * 除注明外 GET、15 秒超时、响应体 2MB 上限；401 时用已保存凭据重登后重试一次
 * （第 6 轮 D6-P2-8：401 是鉴权前置拒绝、请求未被执行，重发对 POST 也安全，
 * 不再只限幂等方法）；超时/网络错/5xx 最多重试 3 次，退避 1 秒。
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
 ) : AuthApi {
    /** B4-P2-1：按超时分档缓存的 OkHttpClient（见 execute）。 */
    private val clientsByTimeout = ConcurrentHashMap<Long, OkHttpClient>()
    /**
     * 401 续期回调：由 Auth 层注入（用已保存凭据重登）。
     * 返回 true 表示续期成功，可对幂等请求重试一次。
     */
    override var onUnauthorized: (suspend () -> Boolean)? = null

    // 播放记录/收藏键的转义只在 [generateKey] 里按段做；整键转义 escapeKey
    // 已删除（D-P2-12）：零调用方，留着只会让后来人误用、把 P1-1
    // （整键转义误转分隔符 `+`）重新引入。

    // ---- 登录 / 配置 ----

    /**
     * B4-P1-2：登录结果分型返回——只有 401（或 2xx 但 ok=false）才算密码错；
     * 5xx/超时/网络错是 Transient，可重试，不再一律报“账号或密码错误”。
     */
    sealed interface LoginResult {
        /** 登录成功。 */
        data object Success : LoginResult

        /** 账号或密码错误（401；或 2xx 但 ok=false/响应体解不出来）。 */
        data object WrongCredentials : LoginResult

        /** 服务端/网络问题（5xx、超时、连接失败、其它 4xx），可重试。 */
        data class Transient(val cause: Throwable) : LoginResult
    }

    override suspend fun login(baseUrl: String, username: String?, password: String?): LoginResult =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/login"
            val body = buildJsonObject {
                username?.let { put("username", JsonPrimitive(it)) }
                password?.let { put("password", JsonPrimitive(it)) }
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            // 超时/5xx 做一次重试（401 与 ok=false 不重试，那是密码错）。
            // D-P2-2：可取消执行，协程取消时取消底层 Call（不是裸 execute）。
            var lastError: Throwable? = null
            repeat(2) { attempt ->
                try {
                    okHttpClient.newCall(request).executeCancellable().use { response ->
                        when {
                            response.isSuccessful -> {
                                val text = readBodyLimited(response)
                                // 响应体解不出来时不算登录成功（默认 false；之前默认 true 会误认登录成功）。
                                val ok = runCatching {
                                    json.decodeFromString<LoginResponse>(text).ok
                                }.getOrDefault(false)
                                return@withContext if (ok) LoginResult.Success
                                else LoginResult.WrongCredentials
                            }
                            response.code == 401 -> return@withContext LoginResult.WrongCredentials
                            response.code in 500..599 ->
                                lastError = IOException("服务器错误（${response.code}）")
                            else -> return@withContext LoginResult.Transient(
                                IOException("登录失败：HTTP ${response.code}"),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ResponseTooLargeException) {
                    // B10-P2-2：响应体超限是确定性失败——重发只会再下载一遍
                    // 同样的超大体；它是 IOException 子类，不先拦会在下面被
                    // 当可重试网络错再跑一次（execute() 内已有同款专用 catch
                    // 原样抛出不重试，login 没走 execute()，漏了）。
                    return@withContext LoginResult.Transient(e)
                } catch (e: IOException) {
                    lastError = e
                }
                if (attempt == 0) delay(retryBackoffMs)
            }
            return@withContext LoginResult.Transient(lastError ?: IOException("登录失败"))
        }

    override suspend fun logout(baseUrl: String) {
        withContext(ioDispatcher) {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/logout")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            // D6-P2-7：登出是 best-effort，网络错照常吞掉，但 CancellationException
            // 必须原样抛出——runCatching 会连取消一起吞，违反仓库内“取消原样抛出”的约定。
            try {
                okHttpClient.newCall(request).executeCancellable().close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 登出失败不阻断调用方收尾（AuthRepository.logout 的 finally 三清照常执行）。
            }
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
        val parsed = json.decodeFromString<DoubanResponse>(body)
        // B14-P2-4：按 agg 同款校验成功 code——服务端 /api/douban 成功时
        // code 恒为 200（服务端源码 route.ts 全部 6 处 code: 均为 200；
        // 豆瓣侧失败时服务端走兜底链，仍回 HTTP 200 + code 200 + 空 list，
        // 或 HTTP 4xx/5xx；非 200 即抛错走占位行，不当“没内容”静默丢行。
        if (parsed.code != 200) throw IOException("豆瓣返回异常：code=${parsed.code}")
        parsed
    }

    // ---- 搜索 ----

    /** 全源搜索：超时放宽到 60 秒；服务端缺 results 字段时当空结果。 */
    suspend fun searchVideos(baseUrl: String, query: String): List<SearchResult> =
        withContext(ioDispatcher) {
            // B4-P2-15：单次搜索总超时 150 秒——服务端多源串行时最坏 ~4 分钟，
            // 无总超时/取消入口会把调用方钉死。
            withTimeout(150_000) {
                val url = "${baseUrl.trimEnd('/')}/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
                val body = execute(
                    Request.Builder().url(url).get().build(),
                    timeoutSeconds = ApiClient.SEARCH_TIMEOUT_SECONDS,
                )
                decodeSearchResults(body)
            }
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
     * 视频源列表。单次 execute 已对超时/网络错/5xx 重试 3 次（第 3 轮收尾 B-P2-13：
     * 外层 repeat(2) 是双重重试，一次超时最多发 8 次请求，已去掉，只留 execute 内重试）；
     * 4xx、体超限等确定性失败在 execute 里已 fail fast；空体直接返回空列表，
     * 整表解码失败时逐条容错保留可解析条目（P1-8）。
     */
    suspend fun getResources(baseUrl: String): List<ApiSite> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/search/resources"
        val request = Request.Builder().url(url).get().build()
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
    }

    // ---- 收藏（服务端模式） ----

    override suspend fun getFavorites(baseUrl: String, key: String?): Map<String, Favorite> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites" +
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            // D8-P1-1：带 key 的单条查询，服务端“未收藏”时合法回 JSON null
            // （moontv-my favorites/route.ts：`Favorite | null`，getFavorite
            // 无行时 return null）——“不存在”是合法答案，不是错误，按空表
            // 处理，不得抛错；否则 favoriteState() 误判 UNKNOWN（详情页收藏
            // 按钮置灰）、toggle() 的 existsCheck 抛错，服务端模式下收藏加不上。
            // 仅限单条查询：整表查询服务端恒回对象表（空为 `{}`），null 体仍
            // 走下面的 B8-P2-8 抛错路径，保留旧表。
            if (key != null && body.trim() == "null") return@withContext emptyMap()
            // 服务端可能返回整表 Record，也可能返回单条 Favorite。
            // 整表解码失败时保留可解析条目（P1-8），而不是整个变空；
            // 整表逐条解出来是空时，再看对象里有没有 Favorite 的字段名，
            // 有才按单条解（Favorite 全字段有默认值，直接试单条会把整表误认成单条）。
            // B8-P2-8：非预期 shape 不再静默返回空表——调用方（Repositories）
            // 会无条件拿空表覆盖 lastGood* 并清 loadFailed，等于悄悄清空
            // 用户的收藏/记录/历史；现在抛错，让调用方保留旧展示 + 置失败标记。
            runCatching { json.decodeFromString<Map<String, Favorite>>(body) }.getOrElse {
                val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                if (raw == null) {
                    if (runCatching { json.parseToJsonElement(body) }.getOrNull() is JsonArray) {
                        AppLog.w("MoonTvApi", "getFavorites 收到 JSON 数组体（期望对象表），抛错保留旧表")
                        throw IOException("收藏返回格式异常：收到 JSON 数组，期望对象表")
                    }
                    val single = runCatching { json.decodeFromString<Favorite>(body) }.getOrElse { e ->
                        throw IOException("收藏返回格式异常：无法解析：${e.message}")
                    }
                    mapOf(singleFavoriteKey(key, single) to single)
                } else {
                    val lenient = LenientDecode.decodeMap(json, raw, Favorite.serializer())
                    if (lenient.isNotEmpty()) {
                        lenient
                    } else if (raw.keys.any { it in FAVORITE_KEYS }) {
                        val single = runCatching { json.decodeFromString<Favorite>(body) }.getOrElse { e ->
                            throw IOException("收藏返回格式异常：无法解析：${e.message}")
                        }
                        mapOf(singleFavoriteKey(key, single) to single)
                    } else {
                        AppLog.w("MoonTvApi", "getFavorites 收到非预期对象体（无收藏字段），抛错保留旧表")
                        throw IOException("收藏返回格式异常：对象体无收藏字段")
                    }
                }
            }
        }

    /**
     * 单条 Favorite 对象的记录键（B8-P2-12）：调用方带 key 时用它；整表拉取
     * （key==null）时按影片自身的 source_name + 标题派生（recordKey 的
     * 空 id 兜底），不用 "" 作键——"" 键会混进收藏表、删/跳收藏时误伤。
     * 来源与标题都空白时无法归属，抛错让调用方保留旧表。
     */
    private fun singleFavoriteKey(key: String?, favorite: Favorite): String {
        key?.let { return it }
        if (favorite.source_name.isBlank() && favorite.title.isBlank()) {
            throw IOException("收藏返回格式异常：单条收藏无来源与标题，无法归属")
        }
        return recordKey(favorite.source_name, "", favorite.title)
    }

    suspend fun addFavorite(baseUrl: String, key: String, favorite: Favorite): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites"
            val bodyJson = buildJsonObject {
                put("key", JsonPrimitive(key))
                put("favorite", json.encodeToJsonElement(Favorite.serializer(), favorite))
            }.toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(false)
        }

    suspend fun deleteFavorite(baseUrl: String, key: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/favorites" +
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(false)
        }

    // ---- 播放记录（服务端模式） ----

    /**
     * 第 7 轮 D7-P2-7：单条查询（`?key=`，与 [getFavorites] 同款）——
     * 断点续播查一条记录不再全表拉取；服务端若不支持该参数会忽略它回整表，
     * 客户端按 key 取值结果仍正确（只是省不下流量）。
     *
     * 第 8 轮 D8-P2-5：对服务端行为的实测修正（moontv-my HEAD bff2f79
     * `playrecords/route.ts:14-28`）——GET 完全忽略 `searchParams`、恒回
     * 整表，永不回单对象、永不 400：`?key=` 的省流量优化是零收益，单对象
     * 分支只是“服务端将来 honours `?key=`”的防御性代码，保留但不再声称
     * 服务端会回单条；整表查询（key==null）遇单对象时与 [getFavorites]
     * 统一口径，按派生键包成单条表（见 [singlePlayRecordKey]）。
     *
     * 第 8 轮 D8-P1-2：服务端 `index` 是 1 基，本地模型是 0 基——返回的
     * 记录统一减 1（见 [toLocalRecordIndex]），写侧见 [savePlayRecord]。
     * 整表查询（key==null）保持 B8-P2-8 行为：对象体逐条解出空仍按空表。
     */
    suspend fun getPlayRecords(baseUrl: String, key: String? = null): Map<String, PlayRecord> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords" +
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).get().build())
            if (body.isBlank()) return@withContext emptyMap()
            // 整表解码失败时保留可解析条目（P1-8），而不是整个变空。
            // B8-P2-8：体不是对象时不再静默返回空表（调用方会拿空表覆盖
            // lastGoodRecords 并清 loadFailed）；对象体逐条解出来是空时
            // （例如 {} 或全是坏条目）仍按空表——那是合法的空记录。
            val records = runCatching { json.decodeFromString<Map<String, PlayRecord>>(body) }.getOrElse { e ->
                val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                if (raw == null) {
                    AppLog.w("MoonTvApi", "getPlayRecords 收到非对象体，抛错保留旧表")
                    throw IOException("播放记录返回格式异常：期望对象表：${e.message}")
                }
                if (raw.keys.any { it in PLAY_RECORD_KEYS }) {
                    val single = runCatching { json.decodeFromString<PlayRecord>(body) }.getOrElse { decodeError ->
                        throw IOException("播放记录返回格式异常：无法解析：${decodeError.message}")
                    }
                    mapOf(singlePlayRecordKey(key, single) to single)
                } else {
                    LenientDecode.decodeMap(json, raw, PlayRecord.serializer())
                }
            }
            // D8-P1-2：读侧统一把服务端 1 基 index 换算成本地 0 基。
            records.mapValues { (_, record) -> record.copy(index = toLocalRecordIndex(record.index)) }
        }

    /**
     * 单条 PlayRecord 对象的记录键（D8-P2-5，与 [singleFavoriteKey] 同款口径）：
     * 调用方带 key 时用它；整表拉取（key==null）时按影片自身的 source_name +
     * 标题派生（recordKey 的空 id 兜底），不用 "" 作键；来源与标题都空白时
     * 无法归属，抛错让调用方保留旧表。
     */
    private fun singlePlayRecordKey(key: String?, record: PlayRecord): String {
        key?.let { return it }
        if (record.source_name.isBlank() && record.title.isBlank()) {
            throw IOException("播放记录返回格式异常：单条记录无来源与标题，无法归属")
        }
        return recordKey(record.source_name, "", record.title)
    }

    suspend fun savePlayRecord(baseUrl: String, key: String, record: PlayRecord): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords"
            // D8-P1-2：服务端 index 是 1 基（POST 校验 `index < 1` 即 400；
            // 网页端 play/page.tsx 与旧 RN 端 playerStore.ts 均写 `当前集 + 1`），
            // 本地模型是 0 基，写侧统一加 1（见 [toServerRecordIndex]）——
            // 否则第 1 集（index=0）永远 400 存不上，其余集存的也是错位值。
            val wireRecord = record.copy(index = toServerRecordIndex(record.index))
            val bodyJson = buildJsonObject {
                put("key", JsonPrimitive(key))
                put("record", json.encodeToJsonElement(PlayRecord.serializer(), wireRecord))
            }.toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(false)
        }

    suspend fun deletePlayRecord(baseUrl: String, key: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/playrecords" +
                (key?.let { "?key=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(false)
        }

    // ---- 搜索历史（服务端模式） ----

    suspend fun getSearchHistory(baseUrl: String): List<String> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/api/searchhistory"
        val body = execute(Request.Builder().url(url).get().build())
        if (body.isBlank()) return@withContext emptyList()
        // B8-P2-8/B8-P2-11：解码失败不再静默返回空列表、无日志——调用方会拿
        // 空表覆盖 lastGoodHistory，用户历史悄悄清空；现在抛错，让调用方
        // 保留旧历史（addSearchHistory 同款处理，见 B7-P2-5）。
        try {
            json.decodeFromString<List<String>>(body)
        } catch (e: Exception) {
            AppLog.w("MoonTvApi", "getSearchHistory 返回格式异常：${e.message}")
            throw IOException("搜索历史返回格式异常：${e.message}")
        }
    }

    suspend fun addSearchHistory(baseUrl: String, keyword: String): List<String> =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/searchhistory"
            val bodyJson = buildJsonObject { put("keyword", JsonPrimitive(keyword)) }
                .toString().toRequestBody("application/json".toMediaType())
            val body = execute(Request.Builder().url(url).post(bodyJson).build())
            // B7-P2-5：服务端 200 但体非数组时，旧实现解码失败默认返回成功
            //（listOf(keyword)），历史静默没写上、调用方还以为写成功了；
            // 解码失败即抛错，让 serverWrite 走重试/失败路径（返回 false，
            // 搜索页给“保存失败”提示）。
            try {
                json.decodeFromString<List<String>>(body)
            } catch (e: Exception) {
                throw IOException("搜索历史返回格式异常：${e.message}")
            }
        }

    suspend fun deleteSearchHistory(baseUrl: String, keyword: String? = null): Boolean =
        withContext(ioDispatcher) {
            val url = "${baseUrl.trimEnd('/')}/api/searchhistory" +
                (keyword?.let { "?keyword=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val body = execute(Request.Builder().url(url).delete().build())
            runCatching { json.decodeFromString<SuccessResponse>(body).success }.getOrDefault(false)
        }

    // ---- 执行层 ----

    /**
     * 带重试 + 401 续期的执行：超时/网络错/5xx 最多重试 3 次，退避 1 秒；
     * 4xx 不重试；401 时调 onUnauthorized 重登，成功则重试一次
     * （第 6 轮 D6-P2-8：不限方法，401 即请求未执行，重发安全）；
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
        // B4-P2-1：按超时分档缓存 client——每次 newBuilder().build() 都会新建
        // 连接池与线程池；超时档位有限（默认 15s / 搜索 60s / 版本检查 20s），缓存复用。
        val client = clientsByTimeout.getOrPut(timeoutSeconds) {
            okHttpClient.newBuilder()
                .connectTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
        var lastError: Exception? = null
        var refreshAttempted = false
        // B4-P2-15：同 AggApi——repeat 的 lambda 里 coroutineContext 不可见，先捕获。
        val context = coroutineContext
        repeat(ApiClient.MAX_RETRIES + 1) { attempt ->
            try {
                client.newCall(request).executeCancellable().use { response ->
                    if (response.code == 401) {
                        if (!refreshAttempted) {
                            refreshAttempted = true
                            val refreshed = onUnauthorized?.invoke() ?: false
                            if (refreshed) {
                                // D6-P2-8：续期成功后重发一次，不再只限幂等方法——
                                // 401 是鉴权中间件在业务处理前拒绝的，请求一定
                                // 未被执行，重发不会 double-apply；本 execute 只
                                // 服务按键 upsert/删除类调用（收藏/记录/搜索历史），
                                // 语义上也可重放。续期失败仍抛 UnauthorizedException。
                                return@repeat // 外层循环再发一次
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
                // B4-P2-15：取消已触发时（call.cancel() 让 execute 抛
                // IOException），在这里重抛 CE，不再重试。
                context.ensureActive()
                // 4xx 已在上面直接抛；这里只剩超时/网络错/5xx，可重试
                if (attempt < ApiClient.MAX_RETRIES) delay(retryBackoffMs)
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    /**
     * 搜索结果解码：空体/缺 results 字段时当空结果；单条脏数据只丢该条，不丢整表。
     * 第 3 轮收尾 B-P2-9：JSON 损坏（非 JSON 对象、results 字段非数组）时抛
     * [SearchResponseException]，与“无结果”区分，由 SearchError 分型提示“数据异常请重试”。
     */
    private fun decodeSearchResults(body: String): List<SearchResult> {
        if (body.isBlank()) return emptyList()
        val response = runCatching { json.decodeFromString<SearchResponse>(body) }.getOrNull()
        if (response != null) return response.results ?: emptyList()
        val raw = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw SearchResponseException("搜索结果解析失败：响应不是合法 JSON 对象")
        val resultsElement = raw["results"] ?: return emptyList()
        val results = resultsElement as? JsonArray
            ?: throw SearchResponseException("搜索结果解析失败：results 字段不是数组")
        return LenientDecode.decodeList(json, results, SearchResult.serializer())
    }

    /** 读响应体并做 2MB 上限校验（流式限量读，见 [readBodyBytesLimited]，D-P2-9）。第 7 轮 D7-P2-4：改 suspend，体读可取消（取消时关体中断 socket 读）。 */
    private suspend fun readBodyLimited(response: okhttp3.Response): String {
        val bytes = try {
            readBodyBytesLimited(response, ApiClient.MAX_BODY_BYTES)
        } catch (e: BodyTooLargeException) {
            throw ResponseTooLargeException("响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        /** 单条 Favorite 的字段名（整表单条兜底时用来认形状）。 */
        private val FAVORITE_KEYS =
            setOf("cover", "title", "source_name", "total_episodes", "search_title")

        /** 单条 PlayRecord 的字段名（第 7 轮 D7-P2-7：单条查询的单对象兜底认形状，同款）。 */
        private val PLAY_RECORD_KEYS =
            setOf("title", "source_name", "cover", "index", "total_episodes", "play_time", "total_time", "save_time")

        /** 标题归一化：去空白、小写（搜索子串过滤与精确标题匹配共用）。 */
        fun normalizeTitle(title: String): String =
            title.lowercase().replace(Regex("\\s+"), "")

        /**
         * 服务端键（数据层 P1-1 约定，与 RN 一致）：分段转义内部 %/+ 后用 + 连接；
         * 拆键时按最后一个 + 切分（见 [splitKey]）。
         * 数据层代理合入同名实现时以该实现为准，两者语义一致，不冲突。
         *
         * 第 8 轮 D8-P2-3（已知跨端契约缺口，单边改不动）：服务端与网页端用
         * 原始拼接 `${source}+${id}`、不转义，服务端路由按第一个 `+` 切分
         * （moontv-my favorites/playrecords route.ts 的 `key.split('+')`）；
         * 原生端按段转义 + 按最后一个 `+` 切分。单端内自洽（单条 `?key=` 与
         * 整表读写走的都是同一套转义键），但网页端建的、source/id 含 `+`/`%`
         * 的记录，原生按转义键查不到，反之亦然。根治需服务端改切分（按最后
         * 一个 `+` 切 + 反转义）或两端统一转义规则，属跨仓库工作——这里只
         * 记契约，不改转义（单边去掉转义会先把原生自己的 `+` 源名写坏）。
         */
        fun generateKey(source: String, id: String): String =
            escapeKeySegment(source) + "+" + escapeKeySegment(id)

        /**
         * B4-P1-1：收藏/断点统一键——全链路（DetailViewModel.init/
         * toggleFavorite、PlayViewModel.init/startEpisode）只用它。
         * id 为空时用标题兜底（`source+title:<归一化标题>`），避免所有空 id
         * 项共用 `source+` 空键互相覆盖；`title:` 前缀使空 id 键与真实 id
         * 键永不重合。归一化标题去空白小写并去掉 `+`/`%`，保证经
         * FavoritesScreen/HomeModels 的 `substringBefore/After("+")` 拆分
         * 再重拼后键不变（拆回的 id 仍是 `title:xxx`，重拼得同一键）。
         * 兼容：旧数据里已存的 `source+` 空键记录无法反歧义，不删用户数据；
         * 新键查不到旧记录，空 id 项表现为未收藏、从头播。
         */
        fun recordKey(source: String, id: String, title: String): String =
            if (id.isNotBlank()) generateKey(source, id)
            else generateKey(source, "title:" + normalizeTitleForKey(title))

        /** 键用归一化标题：去空白小写，再去掉 +/%（防拆分歧义）；标题为空兜底 unknown。 */
        fun normalizeTitleForKey(title: String): String =
            title.lowercase().replace(Regex("\\s+"), "").replace("+", "").replace("%", "")
                .ifBlank { "unknown" }

        /** 键段转义：%→%25、+→%2B（与 [unescapeKeySegment] 配对，按段做）。 */
        private fun escapeKeySegment(segment: String): String =
            segment.replace("%", "%25").replace("+", "%2B")

        /** 键段反转义：%2B→+、%25→%（顺序不能反，见 RN storage.ts 的 unescapeKeyPart）。 */
        fun unescapeKeySegment(segment: String): String =
            segment.replace("%2B", "+").replace("%25", "%")

        /**
         * 第 8 轮 D8-P1-2：播放记录 `index` 的基数换算——服务端是 1 基（第 1 集 = 1，
         * POST 校验 `index < 1` 即 400；网页端 play/page.tsx 与旧 RN 端
         * playerStore.ts 均写 `当前集 + 1`），本地模型（PlayViewModel.episodeIndex、
         * Room episodeIndex）是 0 基。只在服务端边界换算（[savePlayRecord] 写侧、
         * [getPlayRecords] 读侧），本地 Room 分支不动；PlayViewModel 与
         * HomeModels 不动，收到的已是 0 基记录。
         * 脏数据兜底：服务端下发的 0/负 index（网页端亦有脏记录）读侧钳到 0；
         * 写侧钳到 ≥1，不让 400 漏网。
         */
        fun toServerRecordIndex(localIndex: Int): Int = (localIndex + 1).coerceAtLeast(1)

        fun toLocalRecordIndex(serverIndex: Int): Int = (serverIndex - 1).coerceAtLeast(0)

        /**
         * 拆记录键（D-P2-3）：按最后一个 `+` 切分后反转义，与 [generateKey] 配对；
         * 之前 FavoritesScreen/HomeModels 用第一个 `+` 拆，与文档约定漂移。
         * 返回 (source, id)；无分隔符时整个键当 source、id 为空。
         */
        fun splitKey(key: String): Pair<String, String> {
            val sep = key.lastIndexOf("+")
            if (sep < 0) return unescapeKeySegment(key) to ""
            return unescapeKeySegment(key.substring(0, sep)) to
                unescapeKeySegment(key.substring(sep + 1))
        }
    }
}

class UnauthorizedException(message: String) : Exception(message)

/** 搜索响应 JSON 损坏（仍是 IOException，调用方照常捕获；SearchError 分型为“数据异常”）。 */
class SearchResponseException(message: String) : IOException(message)

/** 响应体超限（>2MB）：确定性失败，不重试（仍是 IOException，调用方照常捕获）。 */
internal class ResponseTooLargeException(message: String) : IOException(message)
