package com.oriontv.data.api

import com.oriontv.core.util.VersionUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * agg 接口（点播分类 + 版本检查）：https://ysp.ciweihaha.win/agg，硬编码直连，无登录。
 * 超时/重试照抄 plan §5.1：单次 15 秒；超时/网络错/5xx 最多 3 次，退避 1s/2s/3s 递增；
 * 4xx、结构异常、JSON 损坏、体超时熔断开启时不重试。
 *
 * 熔断器（第 4 轮 P0-3）：videoList 与 version 各用一个，互不影响；
 * 只在“请求最终失败且原因为读超时（SocketTimeoutException）”时计数一次
 * （按请求计数，不按每次重试尝试计数）；4xx/5xx 不计数。
 */
@Singleton
class AggApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 重试退避（毫秒），默认 [ApiClient.backoffMs]；单测可注入 0 以免真睡。 */
    private val retryBackoffMs: (attempt: Int) -> Long = { ApiClient.backoffMs(it) },
) {
    companion object {
        const val BASE_URL = "https://ysp.ciweihaha.win/agg"

        /** 是否体超时：OkHttp 读/连接超时抛 SocketTimeoutException，才记熔断（第 6 轮 fa348ad）。 */
        internal fun isBodyTimeout(e: IOException): Boolean =
            e is java.net.SocketTimeoutException
    }

    /** videoList 专用的体超时熔断器（与 version 分开）。 */
    val videoListCircuitBreaker = CircuitBreaker()

    /** version 专用的体超时熔断器（与 videoList 分开）。 */
    val versionCircuitBreaker = CircuitBreaker()

    /** B4-P2-1：按读超时分档缓存的 OkHttpClient（见 executeWithRetry）。 */
    private val clientsByTimeout = ConcurrentHashMap<Long, OkHttpClient>()

    /**
     * 分类列表。`code==1` 且 `list` 为数组才算成功，否则抛 [AggInvalidResponseException]（不重试）。
     * 翻页坑：pagecount 筛选后恒为 100，不可作为“还有多少页”的依据，终止条件是“本页空列表”。
     * 单条脏数据不拖垮整页：整表解码失败时逐条容错，保留可解析条目并记日志（P1-8）。
     */
    suspend fun videoList(type: Int, page: Int, area: String? = null, year: String? = null): AggListResponse =
        withContext(ioDispatcher) {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("ac", "videolist")
                .addQueryParameter("t", type.toString())
                .addQueryParameter("pg", page.toString())
                .apply {
                    area?.let { addQueryParameter("area", it) }
                    year?.let { addQueryParameter("year", it) }
                }
                .build()
            val response = executeWithRetry(
                Request.Builder().url(url).get().build(),
                circuitBreaker = videoListCircuitBreaker,
            )
            val body = readBodyLimited(response, videoListCircuitBreaker)
            val parsed = try {
                json.decodeFromString<AggListResponse>(body)
            } catch (e: Exception) {
                lenientAggList(body) ?: throw AggInvalidResponseException("agg 返回 JSON 损坏", e)
            }
            if (parsed.code != 1 || parsed.list == null) {
                throw AggInvalidResponseException("agg 返回结构异常：code=${parsed.code}")
            }
            parsed
        }

    /**
     * 版本检查：只取 {version} 一个字段；版本号先过正则。
     * 主端点 `agg?ac=oriontv-version` 失败后调备用端点 `agg/oriontv-version.json`，
     * 备用版本同样过 [VersionUtils.isValidVersion]，两者都失败才报错（P1-2）。
     * 解码/校验异常包成 [AggInvalidResponseException]，与 videoList 一致（P1-7）。
     */
    suspend fun version(): String = withContext(ioDispatcher) {
        val primaryUrl = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("ac", "oriontv-version")
            .build()
        val fallbackUrl = "$BASE_URL/oriontv-version.json".toHttpUrl()
        try {
            fetchVersion(primaryUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 主端点失败（网络/5xx/结构异常）才走备用；两者都失败时抛备用端的错。
            fetchVersion(fallbackUrl)
        }
    }

    /** 单次版本拉取：执行 + 体上限 + 解码 + 正则校验，任一步失败即抛。 */
    private suspend fun fetchVersion(url: HttpUrl): String {
        val response = executeWithRetry(
            Request.Builder().url(url).get().build(),
            timeoutSeconds = ApiClient.VERSION_CHECK_TIMEOUT_SECONDS,
            circuitBreaker = versionCircuitBreaker,
        )
        val body = readBodyLimited(response, versionCircuitBreaker)
        val parsed = try {
            json.decodeFromString<VersionResponse>(body)
        } catch (e: Exception) {
            throw AggInvalidResponseException("版本接口返回 JSON 损坏", e)
        }
        if (!VersionUtils.isValidVersion(parsed.version)) {
            throw AggInvalidResponseException("版本号格式非法：${parsed.version}")
        }
        return parsed.version
    }

    /**
     * 整表解码失败时的逐条容错：根节点不是对象、或缺 code/list 时返回 null
     * （调用方按“JSON 损坏”处理）；code != 1 时直接报结构异常。
     */
    private fun lenientAggList(body: String): AggListResponse? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: return null
        if (code != 1) throw AggInvalidResponseException("agg 返回结构异常：code=$code")
        val rawList = root["list"] as? JsonArray ?: return null
        val items = LenientDecode.decodeList(json, rawList, AggVideoItem.serializer())
        return AggListResponse(
            code = code,
            msg = root["msg"]?.jsonPrimitive?.contentOrNull,
            page = root["page"]?.jsonPrimitive?.intOrNull,
            pagecount = root["pagecount"]?.jsonPrimitive?.intOrNull,
            limit = root["limit"]?.jsonPrimitive?.intOrNull,
            total = root["total"]?.jsonPrimitive?.intOrNull,
            list = items,
        )
    }

    /**
     * 读响应体并做 2MB 上限校验（Content-Length 先行拒绝 + 事后字节校验，复用 MoonTvApi 的思路）。
     * 体读取超时也要记一次熔断（第 10 轮）：熔断器的判据就是“体超时”，而 body.string()
     * 发生在 executeWithRetry 返回之后，不包进来熔断就永远看不到真正的体超时；
     * 记在本次请求的熔断器上（与 executeWithRetry 的按请求计数一致，不重复记）。
     */
    private fun readBodyLimited(response: okhttp3.Response, circuitBreaker: CircuitBreaker): String {
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > ApiClient.MAX_BODY_BYTES) {
            throw AggInvalidResponseException("agg 响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        val body = try {
            response.body?.string().orEmpty()
        } catch (e: java.net.SocketTimeoutException) {
            circuitBreaker.recordFailure()
            throw e
        }
        if (body.toByteArray(Charsets.UTF_8).size > ApiClient.MAX_BODY_BYTES) {
            throw AggInvalidResponseException("agg 响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        return body
    }

    /**
     * 带重试的执行：超时/网络错/5xx 最多 3 次，退避 1s/2s/3s 递增；熔断开启时直接拒绝。
     *
     * 4xx 抛 [NonRetryableHttpException]，由专用 catch 原样转成 IOException 抛出，
     * 不会被下面的 `catch (e: IOException)` 接住重跑（P0-2；第 6/7 轮约定）；熔断只在请求最终失败且原因为
     * 读超时时计数一次，不按每次重试尝试计数，4xx/5xx 不计数（P0-3）。
     * 协程取消原样抛出，不重试、不计数（P2）。
     */
    private suspend fun executeWithRetry(
        request: Request,
        timeoutSeconds: Long = ApiClient.DEFAULT_TIMEOUT_SECONDS,
        circuitBreaker: CircuitBreaker,
    ): okhttp3.Response {
        if (circuitBreaker.isOpen()) {
            throw IOException("agg 熔断开启，60 秒内不再发请求")
        }
        // B4-P2-1：按超时分档缓存 client——每次 newBuilder().build() 都会新建
        // 连接池与线程池；超时档位有限，缓存复用。
        val client = clientsByTimeout.getOrPut(timeoutSeconds) {
            okHttpClient.newBuilder()
                .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
        var lastError: Exception? = null
        repeat(ApiClient.MAX_RETRIES + 1) { attempt ->
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    circuitBreaker.recordSuccess()
                    return response
                }
                response.close()
                if (!ApiClient.shouldRetryOnStatus(response.code)) {
                    // 4xx 不重试：必须用独立异常类型抛出，否则会被下面的 IOException
                    // catch 吞掉重跑（与文档“4xx 不重试”相悖；第 6/7 轮约定）。
                    throw NonRetryableHttpException(response.code)
                }
                lastError = IOException("HTTP ${response.code}")
            } catch (e: NonRetryableHttpException) {
                throw IOException("HTTP ${e.statusCode}，不重试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < ApiClient.MAX_RETRIES) {
                delay(retryBackoffMs(attempt + 1))
            }
        }
        // 只在“请求最终失败且原因为读超时”时计数一次（按请求，不按每次重试尝试；
        // 第 6 轮 fa348ad 只改了判据、仍按尝试计数，一次超时请求会记 4 次直接击穿阈值 3——这里按请求记一次）。
        if (lastError is IOException && isBodyTimeout(lastError)) {
            circuitBreaker.recordFailure()
        }
        throw lastError ?: IOException("请求失败")
    }
}

class AggInvalidResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** 不可重试的 HTTP 状态（4xx）：仅用于跳出各 Api execute 的 IOException catch。 */
internal class NonRetryableHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode")
