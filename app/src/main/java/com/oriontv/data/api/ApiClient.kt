package com.oriontv.data.api

import java.util.concurrent.TimeUnit

/**
 * 熔断器（plan §5.5：agg 分类体超时连续 3 次后 60 秒内不再发）。
 * 纯逻辑类，可单测；时间由调用方注入，便于测试。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var consecutiveFailures = 0
    private var openedAtMs: Long = -1L

    /** 熔断是否开启（开启期间拒绝发请求）。 */
    fun isOpen(): Boolean {
        if (openedAtMs < 0) return false
        if (nowMs() - openedAtMs >= openDurationMs) {
            // 冷却期结束，半开：允许一次试探
            openedAtMs = -1L
            consecutiveFailures = 0
            return false
        }
        return true
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMs = -1L
    }

    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openedAtMs = nowMs()
        }
    }
}

/**
 * OkHttp 客户端骨架（plan §5.5 超时体系）：
 * - 默认请求 15 秒；搜索 60 秒；版本检查 20 秒；m3u/EPG 拉取 15 秒。
 * - 超时/网络错/5xx 最多 3 次，退避 1 秒；4xx、结构异常、JSON 损坏、熔断开启时不重试。
 * - 302 默认跟随跳转（与 RN fetch 一致，不要把 302 当错误）。
 * - 401 时用已保存凭据重登后，仅对幂等请求（GET/DELETE/HEAD）重试一次，POST 不自动重试。
 *
 * 真取消/真超时由 OkHttp 承担（不照搬 RN 的 Promise.race 体超时方案）。
 */
object ApiClient {
    /** 默认超时：连接/读/写各 15 秒。 */
    const val DEFAULT_TIMEOUT_SECONDS = 15L

    /** 搜索 /api/search 超时放宽到 60 秒。 */
    const val SEARCH_TIMEOUT_SECONDS = 60L

    /** 版本检查 20 秒。 */
    const val VERSION_CHECK_TIMEOUT_SECONDS = 20L

    /** m3u / EPG 拉取 15 秒。 */
    const val PLAYLIST_TIMEOUT_SECONDS = 15L

    /** 最多重试次数（不含首次）。 */
    const val MAX_RETRIES = 3

    /** 重试退避 1 秒；版本检查退避 2s × 次数；APK 下载退避 3s × 次数。 */
    const val RETRY_BACKOFF_MS = 1000L

    /** 响应体上限：MoonTV 接口 2MB，m3u 5MB，EPG/m3u 缓存落盘 3MB。 */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024L
    const val MAX_M3U_BYTES = 5 * 1024 * 1024L
    const val MAX_CACHE_BYTES = 3 * 1024 * 1024L

    /** APK 下载上限 200MB，失败重试 3 次；SHA256 失败不重试。 */
    const val MAX_APK_BYTES = 200 * 1024 * 1024L

    /** 幂等方法：401 续期后可重试一次。 */
    fun isIdempotent(method: String): Boolean =
        method.equals("GET", ignoreCase = true) ||
            method.equals("DELETE", ignoreCase = true) ||
            method.equals("HEAD", ignoreCase = true)

    /** 是否值得重试：超时/网络错（IOException）/5xx 才重试；4xx 不重试。 */
    fun shouldRetryOnStatus(statusCode: Int): Boolean = statusCode >= 500

    /** 指数退避（版本检查：2s × 次数；默认：1s 固定）。 */
    fun backoffMs(attempt: Int, baseMs: Long = RETRY_BACKOFF_MS): Long =
        baseMs * attempt.coerceAtLeast(1)

    fun newBuilder(): okhttp3.OkHttpClient.Builder = okhttp3.OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // 重试由上层按规则控制，不靠 OkHttp 盲重试
}
