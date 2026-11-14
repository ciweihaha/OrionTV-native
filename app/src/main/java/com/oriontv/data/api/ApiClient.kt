package com.oriontv.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 熔断器（plan §5.5：agg 分类体超时连续 3 次后 60 秒内不再发）。
 * 纯逻辑类，可单测；时间由调用方注入，便于测试。
 * 线程安全：AggApi 的 videoList/version 在 IO 线程并发调用，计数用 @Synchronized 保护。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var consecutiveFailures = 0
    private var openedAtMs: Long = -1L

    /**
     * 半开试探租约（B5-P2-4）：冷却期满后只放行一个试探请求，其余继续拒绝。
     * 探针完成前 [probeInFlight] 为 true；探针挂死时租约过期才允许下一个试探。
     */
    private var probeInFlight = false
    private var probeStartedAtMs: Long = -1L

    /** 探针租约时长：覆盖一次完整重试周期（版本检查 4 次 × 20 秒 + 退避约 86 秒）。 */
    private val probeLeaseMs: Long = 120_000L

    /** 熔断是否开启（开启期间拒绝发请求）。 */
    @Synchronized
    fun isOpen(): Boolean {
        if (openedAtMs < 0) return false
        if (nowMs() - openedAtMs < openDurationMs) {
            return true
        }
        // 冷却期满：半开。第一个调用方拿到试探租约，其余继续拒绝；
        // 租约过期（探针挂死）时允许下一个调用方重新试探。
        if (!probeInFlight) {
            probeInFlight = true
            probeStartedAtMs = nowMs()
            return false
        }
        if (nowMs() - probeStartedAtMs >= probeLeaseMs) {
            probeStartedAtMs = nowMs()
            return false
        }
        return true
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMs = -1L
        probeInFlight = false
    }

    @Synchronized
    fun recordFailure() {
        // 探针失败立即重开：冷却期满时 consecutiveFailures 仍保留着开闸前的
        // 计数值（isOpen 不再清零），一次探针失败即回到阈值以上。
        probeInFlight = false
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openedAtMs = nowMs()
        }
    }

    /**
     * 释放试探租约（不记失败）：探针请求以非超时原因终态失败时调用——
     * 熔断器的判据只看体超时，这类失败不计数，但也不能占着租约
     * 120 秒才放下一个试探。
     */
    @Synchronized
    fun releaseProbe() {
        probeInFlight = false
    }
}

/**
 * OkHttp 客户端骨架（plan §5.5 超时体系）：
 * - 默认请求 15 秒；搜索 60 秒；版本检查 20 秒；m3u/EPG 拉取 15 秒。
 * - 超时/网络错/5xx 最多 3 次，退避 1 秒；4xx、结构异常、JSON 损坏、熔断开启时不重试。
 * - 302 默认跟随跳转（与 RN fetch 一致，不要把 302 当错误）。
 * - 401 时用已保存凭据重登后重试一次（第 6 轮 D6-P2-8：401 即请求未执行，
 *   重发对 POST 也安全，不再只限幂等方法）。
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

    /** APK 下载读超时放宽到 60 秒（D-P1-5）：15 秒在抖动 WiFi 下一次停顿就丢掉全部进度。 */
    const val DOWNLOAD_TIMEOUT_SECONDS = 60L

    /** 幂等方法：401 续期后可重试一次。 */
    fun isIdempotent(method: String): Boolean =
        method.equals("GET", ignoreCase = true) ||
            method.equals("DELETE", ignoreCase = true) ||
            method.equals("HEAD", ignoreCase = true)

    /** 是否值得重试：超时/网络错（IOException）/5xx 才重试；4xx 不重试。 */
    fun shouldRetryOnStatus(statusCode: Int): Boolean = statusCode >= 500

    /** 线性退避：baseMs × 次数（默认 1s/2s/3s；版本检查 2s × 次数；APK 下载 3s × 次数）。 */
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

/** 响应体超限（>limit）：确定性失败，调用方按各自异常类型转译后不重试。 */
class BodyTooLargeException(message: String) : IOException(message)

/**
 * 流式限量读响应体（D-P2-9）：声明 Content-Length 先行拒绝；再流式读，
 * 累计超限立即停（最多缓冲 limit 字节），不整包进内存（服务端谎报小体积时
 * `body.string()` 会把未封顶的体读进内存）。
 * 体读取超时（SocketTimeoutException）原样抛出，由调用方记熔断/做重试判定。
 * 第 7 轮 D7-P2-4：改 suspend + 可取消读（见 [readStreamCapped]）——
 * 之前是阻塞式 `InputStream.read()`，`withTimeout` 的取消在体读返回前
 * 递送不到协程，version() 的 60 秒总预算可超支约一个读超时（20 秒）。
 */
internal suspend fun readBodyBytesLimited(response: okhttp3.Response, limitBytes: Long): ByteArray {
    val declared = response.header("Content-Length")?.toLongOrNull()
    if (declared != null && declared > limitBytes) {
        throw BodyTooLargeException("响应体超限（>$limitBytes 字节）")
    }
    val body = response.body ?: return ByteArray(0)
    return body.byteStream().use { input ->
        readStreamCapped(input, limitBytes) { runCatching { body.close() } }
    }
}

/**
 * 可取消的流式限量读（第 7 轮 D7-P2-4）：阻塞式 `InputStream.read()` 本身
 * 不响应协程取消——取消到达时由 `invokeOnCancellation` 关流（调用方传
 * 关响应体的动作），中断 socket 读，体读立即以 [CancellationException]
 * 收尾；调用方仍在 IO 线程阻塞读（与之前一致），只是多了一条取消时
 * 中断读的路径。超限仍抛 [BodyTooLargeException]；取消与超限竞态时
 * 以取消为准（已取消的 continuation 不再恢复超限结果）。
 */
internal suspend fun readStreamCapped(
    input: java.io.InputStream,
    limitBytes: Long,
    closeOnCancel: () -> Unit,
): ByteArray = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { closeOnCancel() } }
    try {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            if (out.size() + read > limitBytes) {
                throw BodyTooLargeException("响应体超限（>$limitBytes 字节）")
            }
            out.write(buf, 0, read)
        }
        if (cont.isActive) {
            cont.resume(out.toByteArray())
        }
    } catch (e: Exception) {
        if (cont.isCancelled) {
            cont.resumeWithException(
                e as? CancellationException
                    ?: CancellationException("响应体读取已取消").apply { initCause(e) },
            )
        } else {
            cont.resumeWithException(e)
        }
    }
}

/**
 * 可取消的同步执行（D-P2-2）：协程取消时调 `Call.cancel()`，在途请求立即停，
 * 不等到 15 秒 socket 超时；取消导致的 IOException 转成 CancellationException，
 * 不被各 execute 的 IOException 重试分支当成可重试网络错。
 * 调用方须在 IO 线程调用（各 Api 已在 `withContext(ioDispatcher)` 内）。
 */
internal suspend fun okhttp3.Call.executeCancellable(): okhttp3.Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        try {
            val response = execute()
            if (cont.isActive) {
                cont.resume(response)
            } else {
                // 取消与成功返回竞态：resume 会被忽略，顺手关掉 Response，不漏连接。
                response.close()
            }
        } catch (e: Exception) {
            if (cont.isCancelled) {
                cont.resumeWithException(
                    e as? CancellationException
                        ?: CancellationException("请求已取消").apply { initCause(e) },
                )
            } else {
                cont.resumeWithException(e)
            }
        }
    }
