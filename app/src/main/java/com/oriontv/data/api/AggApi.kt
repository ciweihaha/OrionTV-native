package com.oriontv.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * agg 接口（点播分类 + 版本检查）：https://ysp.ciweihaha.win/agg，硬编码直连，无登录。
 * 超时/重试照抄 plan §5.1：单次 15 秒；超时/网络错/5xx 最多 3 次，退避 1 秒；
 * 4xx、结构异常、JSON 损坏、体超时熔断开启时不重试。
 */
@Singleton
class AggApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val BASE_URL = "https://ysp.ciweihaha.win/agg"
    }

    /** agg 体超时熔断：同类请求连续 3 次体超时后 60 秒内不再发。 */
    val circuitBreaker = CircuitBreaker()

    /**
     * 分类列表。`code==1` 且 `list` 为数组才算成功，否则抛 [AggInvalidResponseException]（不重试）。
     * 翻页坑：pagecount 筛选后恒为 100，不可作为“还有多少页”的依据，终止条件是“本页空列表”。
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
            val response = executeWithRetry(Request.Builder().url(url).get().build())
            val body = response.body?.string().orEmpty()
            val parsed = try {
                json.decodeFromString<AggListResponse>(body)
            } catch (e: Exception) {
                throw AggInvalidResponseException("agg 返回 JSON 损坏", e)
            }
            if (parsed.code != 1 || parsed.list == null) {
                throw AggInvalidResponseException("agg 返回结构异常：code=${parsed.code}")
            }
            parsed
        }

    /** 版本检查：只取 {version} 一个字段；版本号先过正则。 */
    suspend fun version(): String = withContext(ioDispatcher) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("ac", "oriontv-version")
            .build()
        val response = executeWithRetry(
            Request.Builder().url(url).get().build(),
            timeoutSeconds = ApiClient.VERSION_CHECK_TIMEOUT_SECONDS,
        )
        val body = response.body?.string().orEmpty()
        val parsed = json.decodeFromString<VersionResponse>(body)
        require(com.oriontv.core.util.VersionUtils.isValidVersion(parsed.version)) {
            "版本号格式非法：${parsed.version}"
        }
        parsed.version
    }

    /**
     * 带重试的执行：超时/网络错/5xx 最多 3 次，退避 1 秒；熔断开启时直接拒绝。
     * TODO（M1）：把熔断判据从“请求失败”细化到“体超时”；把 401 续期接进来。
     */
    private fun executeWithRetry(
        request: Request,
        timeoutSeconds: Long = ApiClient.DEFAULT_TIMEOUT_SECONDS,
    ): okhttp3.Response {
        if (circuitBreaker.isOpen()) {
            throw IOException("agg 熔断开启，60 秒内不再发请求")
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
                    throw IOException("HTTP ${response.code}，不重试")
                }
                lastError = IOException("HTTP ${response.code}")
            } catch (e: IOException) {
                lastError = e
                circuitBreaker.recordFailure()
            }
            if (attempt < ApiClient.MAX_RETRIES) {
                Thread.sleep(ApiClient.backoffMs(attempt + 1))
            }
        }
        throw lastError ?: IOException("请求失败")
    }
}

class AggInvalidResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
