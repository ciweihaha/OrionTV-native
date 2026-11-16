package com.oriontv.data.api

import com.oriontv.core.util.VersionUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * agg 接口（点播分类 + 版本检查）：https://ysp.ciweihaha.win/agg，硬编码直连，无登录。
 * 超时/重试照抄 plan §5.1：单次 15 秒；超时/网络错/5xx/429 最多 3 次，退避 1s/2s/3s 递增；
 * 其余 4xx、结构异常、JSON 损坏、体超时熔断开启时不重试。
 *
 * 熔断器（第 4 轮 P0-3，第 5 轮 D-P1-1 修订）：videoList 与 version 各用一个，互不影响；
 * 只在“请求最终失败且原因为读超时（SocketTimeoutException）”时计数一次
 * （按请求计数，不按每次重试尝试计数）；4xx/5xx 不计数。
 * 成功只在体完整读完后记（D-P1-1）：200 响应头不再先 `recordSuccess` 清零，
 * 否则纯体超时请求永远是“先清零、再记 1”，计数在 0→1 振荡、到不了阈值 3。
 */
@Singleton
class AggApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 重试退避（毫秒），默认 [ApiClient.backoffMs]；单测可注入 0 以免真睡。 */
    private val retryBackoffMs: (attempt: Int) -> Long = { ApiClient.backoffMs(it) },
    /** videoList 专用的体超时熔断器（与 version 分开）；单测可注入假时钟。 */
    val videoListCircuitBreaker: CircuitBreaker = CircuitBreaker(),
    /** version 专用的体超时熔断器（与 videoList 分开）；单测可注入假时钟。 */
    val versionCircuitBreaker: CircuitBreaker = CircuitBreaker(),
    /** 第 6 轮 D6-P2-1：主版本端点单独预算（毫秒）；单测可注入小值。 */
    private val primaryVersionTimeoutMs: Long = PRIMARY_VERSION_TIMEOUT_MS,
    /** 第 6 轮 D6-P2-1：版本检查总预算（毫秒），主备合计封顶。 */
    private val totalVersionTimeoutMs: Long = TOTAL_VERSION_TIMEOUT_MS,
) {
    companion object {
        const val BASE_URL = "https://ysp.ciweihaha.win/agg"

        /** 是否体超时：OkHttp 读/连接超时抛 SocketTimeoutException，才记熔断（第 6 轮 fa348ad）。 */
        internal fun isBodyTimeout(e: IOException): Boolean =
            e is java.net.SocketTimeoutException

        /**
         * 第 6 轮 D6-P2-1：主端点单独预算 25 秒——主端点挂起时 25 秒即走备用，
         * 不再被 60 秒总超时卡在第 3 次尝试中途、备用根本跑不到。
         */
        const val PRIMARY_VERSION_TIMEOUT_MS = 25_000L

        /** 版本检查总预算 60 秒（主备合计封顶，不变）。 */
        const val TOTAL_VERSION_TIMEOUT_MS = 60_000L
    }

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
            // D-P1-2：Response 必须关（声明超限先抛那条路之前漏关，5 次可占满每 host 连接池）；
            // D-P1-1：体完整读完才记成功，响应头 200 不先清零。
            val body = response.use {
                try {
                    val b = readBodyLimited(it, videoListCircuitBreaker)
                    videoListCircuitBreaker.recordSuccess()
                    b
                } catch (e: Exception) {
                    // B5-P2-4：体读失败且不是体超时（体超时已在 readBodyLimited
                    // 内记失败并清探针）时释放试探租约，免得确定性失败也占着探针。
                    videoListCircuitBreaker.releaseProbe()
                    throw e
                }
            }
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
     * D6-P2-1：主端点单独 25 秒预算——主端点挂起时 25 秒即走备用，总预算仍封顶 60 秒。
     */
    suspend fun version(): String = withContext(ioDispatcher) {
        // B4-P2-14：总超时 60 秒——主备两端点各最多重试 3 次、单次 20 秒，
        // 最坏 ~3 分钟；无取消入口时调用方（更新检查）会被钉死。
        withTimeout(totalVersionTimeoutMs) {
            val primaryUrl = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("ac", "oriontv-version")
                .build()
            val fallbackUrl = "$BASE_URL/oriontv-version.json".toHttpUrl()
            val primary = try {
                withTimeout(primaryVersionTimeoutMs) { fetchVersion(primaryUrl) }
            } catch (e: TimeoutCancellationException) {
                // 区分“主端点自己的 25 秒预算耗尽”与“调用方取消”：
                // 前者 job 仍活跃，落到备用；后者 ensureActive 重抛取消。
                coroutineContext.ensureActive()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 主端点失败（网络/5xx/结构异常）才走备用；两者都失败时抛备用端的错。
                null
            }
            if (primary != null) return@withTimeout primary
            fetchVersion(fallbackUrl)
        }
    }

    /** 单次版本拉取：执行 + 体上限 + 解码 + 正则校验，任一步失败即抛。版本号先去 "v" 前缀再校验（D-P2-4，根治点在 AggApi）。 */
    private suspend fun fetchVersion(url: HttpUrl): String {
        val response = executeWithRetry(
            Request.Builder().url(url).get().build(),
            timeoutSeconds = ApiClient.VERSION_CHECK_TIMEOUT_SECONDS,
            circuitBreaker = versionCircuitBreaker,
        )
        val body = response.use {
            try {
                val b = readBodyLimited(it, versionCircuitBreaker)
                versionCircuitBreaker.recordSuccess()
                b
            } catch (e: Exception) {
                // B5-P2-4：同 videoList，体读确定性失败时释放试探租约。
                versionCircuitBreaker.releaseProbe()
                throw e
            }
        }
        val parsed = try {
            json.decodeFromString<VersionResponse>(body)
        } catch (e: Exception) {
            throw AggInvalidResponseException("版本接口返回 JSON 损坏", e)
        }
        val version = VersionUtils.stripVersionPrefix(parsed.version)
        if (!VersionUtils.isValidVersion(version)) {
            throw AggInvalidResponseException("版本号格式非法：${parsed.version}")
        }
        return version
    }

    /**
     * 整表解码失败时的逐条容错：根节点不是对象、或缺 code/list 时返回 null
     * （调用方按“JSON 损坏”处理）；code != 1 时直接报结构异常。
     * 第 9 轮 D9-P2-9：code 下成字符串 "1" 时旧实现 `intOrNull ?: return null`
     * 直接误报“JSON 损坏”——code 接受字符串数字（content.toIntOrNull()
     * 兜底，数字 1 与 "1" 等价）；code 是对象/数组等非标量时同样回 null
     * （不再让 jsonPrimitive 抛 IllegalArgumentException 漏出去）。
     * D6-P2-16 闭环：coerceInputValues 只对有默认值的字段生效，触发条件
     * 已证实——Models 里 Favorite/PlayRecord/SearchResult/AggVideoItem
     * 全字段有默认值，已被覆盖；仅 AggListResponse.code、DoubanResponse.code、
     * VersionResponse.version 三个必填标量不受保护，残口即本项（agg 侧
     * 在此兜底，version 侧给默认值 "" 再由 isValidVersion 报错）。
     */
    private fun lenientAggList(body: String): AggListResponse? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
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
     * 读响应体并做 2MB 上限校验（流式限量读，见 [readBodyBytesLimited]，D-P2-9）。
     * 体读取超时也要记一次熔断（第 10 轮）：熔断器的判据就是“体超时”，而体读取
     * 发生在 executeWithRetry 返回之后，不包进来熔断就永远看不到真正的体超时；
     * 记在本次请求的熔断器上（与 executeWithRetry 的按请求计数一致，不重复记）。
     * 成功由调用方在体完整读完后记（D-P1-1），这里只记失败。
     */
    private suspend fun readBodyLimited(response: okhttp3.Response, circuitBreaker: CircuitBreaker): String {
        val bytes = try {
            readBodyBytesLimited(response, ApiClient.MAX_BODY_BYTES)
        } catch (e: BodyTooLargeException) {
            throw AggInvalidResponseException("agg 响应体超限（>${ApiClient.MAX_BODY_BYTES} 字节）")
        } catch (e: java.net.SocketTimeoutException) {
            circuitBreaker.recordFailure()
            throw e
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * 带重试的执行：超时/网络错/5xx/429 最多 3 次，退避 1s/2s/3s 递增；熔断开启时直接拒绝。
     *
     * 4xx（429 除外）抛 [NonRetryableHttpException]，由专用 catch 原样转成 IOException 抛出，
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
        // B4-P2-14：repeat 的 lambda 不是 suspend 上下文，coroutineContext
        // 在里面不可见，先在外面捕获。
        val context = coroutineContext
        // D6-P2-2：循环内 try/catch 盖不住的三条取消路径——重试退避 delay()、
        // catch 里的 ensureActive()、外层 withTimeout——都在这里统一释放试探
        // 租约再重抛；否则黑窗从 60 秒拖到约 120 秒（租约自然过期才放下一个探针）。
        // 内层 CancellationException catch 已释放过一次，releaseProbe 是幂等的。
        try {
            repeat(ApiClient.MAX_RETRIES + 1) { attempt ->
                try {
                    val response = client.newCall(request).executeCancellable()
                    if (response.isSuccessful) {
                        // D-P1-1：200 只表示响应头到了，不记成功；成功由调用方在体完整读完后记。
                        return response
                    }
                    response.close()
                    if (!ApiClient.shouldRetryOnStatus(response.code)) {
                        // 4xx（429 除外）不重试：必须用独立异常类型抛出，否则会被下面的 IOException
                        // catch 吞掉重跑（与文档“4xx 不重试”相悖；第 6/7 轮约定）。
                        throw NonRetryableHttpException(response.code)
                    }
                    lastError = IOException("HTTP ${response.code}")
                } catch (e: NonRetryableHttpException) {
                    // B5-P2-4：4xx 确定性失败不记熔断，但探针租约要释放，
                    // 否则这次试探占着租约 120 秒才放下一个。
                    circuitBreaker.releaseProbe()
                    throw IOException("HTTP ${e.statusCode}，不重试")
                } catch (e: CancellationException) {
                    // B5-P2-4：取消的探针同样释放租约。
                    circuitBreaker.releaseProbe()
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    // B4-P2-14：取消已触发时（call.cancel() 让 execute 抛
                    // IOException），在这里重抛 CE，不再重试。
                    context.ensureActive()
                }
                if (attempt < ApiClient.MAX_RETRIES) {
                    delay(retryBackoffMs(attempt + 1))
                }
            }
        } catch (e: CancellationException) {
            circuitBreaker.releaseProbe()
            throw e
        }
        // 只在“请求最终失败且原因为读超时”时计数一次（按请求，不按每次重试尝试；
        // 第 6 轮 fa348ad 只改了判据、仍按尝试计数，一次超时请求会记 4 次直接击穿阈值 3——这里按请求记一次）。
        // B5-P2-4：超时记失败（探针失败立即重开）；非超时终态失败只释放试探租约，不计数。
        if (lastError is IOException && isBodyTimeout(lastError)) {
            circuitBreaker.recordFailure()
        } else {
            circuitBreaker.releaseProbe()
        }
        throw lastError ?: IOException("请求失败")
    }
}

class AggInvalidResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** 不可重试的 HTTP 状态（4xx，429 除外）：仅用于跳出各 Api execute 的 IOException catch。 */
internal class NonRetryableHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode")
