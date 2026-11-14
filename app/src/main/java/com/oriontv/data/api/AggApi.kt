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
import java.net.SocketTimeoutException
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
    }

    /** videoList 专用的体超时熔断器（与 version 分开）。 */
    val videoListCircuitBreaker = CircuitBreaker()

    /** version 专用的体超时熔断器（与 videoList 分开）。 */
    val versionCircuitBreaker = CircuitBreaker()

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
            val body = readBodyLimited(response)
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
        val body = readBodyLimited(response)
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

    /** 读响应体并做 2MB 上限校验（Content-Length 先行拒绝 + 事后字节校验，复用 MoonTvApi 的思路）。 */
    private fun readBodyLimited(response: okhttp3.Response): String {
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > ApiClient.MAX_BODY_BYTES) {
            throw AggInvalidResponseException("agg 响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        val body = response.body?.string().orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > ApiClient.MAX_BODY_BYTES) {
            throw AggInvalidResponseException("agg 响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        }
        return body
    }

    /**
     * 带重试的执行：超时/网络错/5xx 最多 3 次，退避 1s/2s/3s 递增；熔断开启时直接拒绝。
     *
     * 4xx 抛 [NonRetryableException]——它不继承 IOException，所以不会被下面的
     * `catch (e: IOException)` 接住重跑（P0-2）；熔断只在请求最终失败且原因为
     * 读超时时计数一次，不按每次重试尝试计数，4xx/5xx 不计数（P0-3）。
     * 协程取消原样抛出，不重试、不计数（P2）。
     */
    private suspend fun executeWithRetry(
        request: Request,
        timeoutSeconds: Long = ApiClient.DEFAULT_TIMEOUT_SECONDS,
        circuitBreaker: CircuitBreaker,
    ): okhttp3.Response {
        if (circuitBreaker.isOpen()) {
            throw NonRetryableException("agg 熔断开启，60 秒内不再发请求")
        }
        val client = okHttpClient.newBuilder()
            .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .build()
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
                    throw NonRetryableException("HTTP ${response.code}，不重试")
                }
                lastError = IOException("HTTP ${response.code}")
            } catch (e: NonRetryableException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < ApiClient.MAX_RETRIES) {
                delay(retryBackoffMs(attempt + 1))
            }
        }
        // 只在“请求最终失败且原因为读超时”时计数一次。
        if (lastError is SocketTimeoutException) {
            circuitBreaker.recordFailure()
        }
        throw lastError ?: IOException("请求失败")
    }
}

class AggInvalidResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
