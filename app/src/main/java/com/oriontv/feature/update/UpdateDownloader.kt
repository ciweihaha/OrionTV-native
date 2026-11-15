package com.oriontv.feature.update

import com.oriontv.core.util.VersionUtils
import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.executeCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * APK 下载器（plan §5.1/§5.5，RN updateService.ts 对照）：
 * - 下载上限 200MB（HEAD 预检 + 下载中途流式计数双保险，超限立即停，不重试）；
 * - 失败重试 3 次，退避 3s × 次数；SHA256 校验失败不重试；
 * - 校验走 `<url>.sha256`（4KB 上限，支持 `hash  filename` 或纯 hash）；
 * - 旧 APK 只保留最新 2 个。
 * - 断点续传（D-P1-5）：读超时放宽到 60 秒；中途失败不删残文件，
 *   重试时带 `Range: bytes=<已下>-` 从断点续下（服务端返回 206）；
 *   服务端不支持 Range（回 200）时截断重下，416 时删残文件重下。
 *
 * 注意：[ioDispatcher] 有默认参数，Hilt 不会用默认值注入——
 * 由 AppModule 用 @Provides 显式构造（已知坑，见 CONTRACT §2.1 备注）。
 */
@Singleton
class UpdateDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        /** 下载上限 200MB（plan §5.1）。 */
        const val MAX_APK_BYTES = ApiClient.MAX_APK_BYTES

        /** .sha256 文件上限 4KB（RN 同款：正常只有一行约 100 字节）。 */
        const val MAX_SHA256_FILE_BYTES = 4 * 1024L

        /** 下载失败重试 3 次，退避 3s × 次数（plan §5.5）。 */
        const val MAX_RETRIES = 3

        /** 旧 APK 保留个数（plan §5.1）。 */
        const val KEEP_LATEST_APKS = 2
    }

    /** HEAD 预检声明体积：超限返回 false（直接拒绝）；拿不到/预检失败返回 null（放行，下载中兜底）。 */
    suspend fun headApkSizeBytes(url: String): Long? = withContext(ioDispatcher) {
        try {
            val request = Request.Builder().url(url).head().build()
            okHttpClient.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return@withContext null
                val len = response.header("Content-Length")?.toLongOrNull() ?: return@withContext null
                len
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 下载 APK 到 [destFile]，流式写盘并计数，超 200MB 立即停并删残文件。
     * 进度回调 [onProgress]（已写字节，总字节，-1 表示未知）。
     * 成功返回文件；失败抛 [UpdateDownloadException]（带是否可重试的 [UpdateError]）。
     *
     * 断点续传（D-P1-5）：下载专用客户端读超时 60 秒；中途失败/取消不删残文件，
     * 下一次尝试带 `Range` 从断点续下。服务端不支持 Range（回 200）时截断重下；
     * 416（断点失效，如服务端文件已换）删残文件重下。
     */
    suspend fun downloadApk(
        url: String,
        destFile: File,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): File = withContext(ioDispatcher) {
        val declared = headApkSizeBytes(url)
        if (declared != null && declared > MAX_APK_BYTES) {
            throw UpdateDownloadException(
                UpdateError.TooLarge,
                "安装包体积 ${formatSize(declared)}，超过 200MB 上限",
            )
        }
        // D-P1-5：下载专用客户端，读超时 60 秒（共享客户端 15 秒，抖动 WiFi 下一次
        // 停顿就丢掉全部进度）。
        val downloadClient = okHttpClient.newBuilder()
            .readTimeout(ApiClient.DOWNLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        var lastError: Exception? = null
        // MAX_RETRIES 是重试次数（不含首次），与 ApiClient 一致：1 次初试 + 3 次重试。
        // 原来 repeat(MAX_RETRIES) 只发 3 次（初试 + 2 次重试），与文档“失败重试 3 次”不符。
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                destFile.parentFile?.mkdirs()
                val resumedFrom = if (destFile.exists()) destFile.length() else 0L
                val requestBuilder = Request.Builder().url(url).get()
                if (resumedFrom > 0) {
                    requestBuilder.header("Range", "bytes=$resumedFrom-")
                }
                val request = requestBuilder.build()
                downloadClient.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 416 && resumedFrom > 0) {
                            // 第 6 轮 P2-2：416 未必是断点失效——本地文件已与服务端
                            // 声明体积相等时，很可能是上次已下完、未及校验就中断
                            //（或 Range 起点恰好在 EOF）；直接当下完走校验，不删
                            // 整包重下（110MB 弱网下等于白下一次）。体积对不上
                            //（服务端文件已换）才删残文件重下；拿不到声明体积时
                            // 沿用旧行为（删残文件重下）。
                            if (shouldTreat416AsComplete(resumedFrom, declared)) {
                                return@withContext destFile
                            }
                            // 断点失效（服务端文件已换）：删残文件，可重试重下。
                            destFile.delete()
                            throw IOException("下载失败：HTTP 416，断点失效，从头重下")
                        }
                        if (!ApiClient.shouldRetryOnStatus(response.code)) {
                            // 4xx 不重试：确定性失败，重发也一样（同第 6/7 轮模式）。
                            // UpdateDownloadException 有专用 catch，直接重抛不重试。
                            throw UpdateDownloadException(
                                UpdateError.Network,
                                "下载失败：HTTP ${response.code}，不重试",
                            )
                        }
                        throw IOException("下载失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("下载失败：响应体为空")
                    val isResume = response.code == 206
                    if (isResume) {
                        // 206 必须从断点处续上，否则追加会写坏文件：起点不对就删残文件重下。
                        // B8-P2-9：缺 Content-Range 头时旧实现直接当续传成功追加——
                        // 服务端没确认起点就追加，文件必然写坏、SHA 校验才暴露；
                        // 缺头与起点不一致同等处理：删残文件、抛错重下。
                        val rangeStart = response.header("Content-Range")
                            ?.substringAfter("bytes ", "")
                            ?.substringBefore("-")
                            ?.toLongOrNull()
                        if (rangeStart == null) {
                            destFile.delete()
                            throw IOException("下载失败：服务端 206 响应缺 Content-Range 头，无法确认续传起点，从头重下")
                        }
                        if (rangeStart != resumedFrom) {
                            destFile.delete()
                            throw IOException("下载失败：服务端续传起点 $rangeStart 与断点 $resumedFrom 不一致，从头重下")
                        }
                    }
                    val total = if (isResume) {
                        response.header("Content-Range")
                            ?.substringAfterLast("/", "")
                            ?.toLongOrNull()
                            ?: (resumedFrom + body.contentLength())
                    } else {
                        body.contentLength()
                    }
                    var written = if (isResume) resumedFrom else 0L
                    // B14-P2-1：体读取阶段的取消不能只靠每 64KB 读完后的 ensureActive()——
                    // 阻塞读本身可停满 60 秒读超时，期间取消到达、重下也被拦。
                    // 拷贝循环包进 suspendCancellableCoroutine，取消到达时由
                    // cont.invokeOnCancellation 关响应体流，中断 socket 读
                    // （与 readStreamCapped 同款；Job.invokeOnCompletion 的
                    // 处理器只在任务真正完成后才跑，正在阻塞读时根本到不了，
                    // 救不了这条路——实测 29.5 秒才停。executeCancellable 的
                    // Call.cancel() 钩子在拿到响应头、continuation 恢复后即解除，
                    // 体读取阶段再无中断路径）。关流引发的读错在下面按取消处理，
                    // 不当可重试失败；其余异常原样经 continuation 抛回外层。
                    // suspendCancellableCoroutine 的 block 不是协程体，调不了
                    // currentCoroutineContext()——先在外面取一次。
                    val copyContext = currentCoroutineContext()
                    suspendCancellableCoroutine { cont ->
                        cont.invokeOnCancellation { runCatching { body.close() } }
                        try {
                            body.byteStream().use { input ->
                                // 服务端不支持 Range（回 200）时截断重下；206 时追加续写。
                                FileOutputStream(destFile, isResume).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        // D-P2-2：拷贝循环加挂起点，取消后不再把整个 APK 下完才停。
                                        copyContext.ensureActive()
                                        val read = try {
                                            input.read(buffer)
                                        } catch (e: IOException) {
                                            // 关流中断的阻塞读：已取消按取消抛，不进重试；
                                            // 未取消的真 IO 错原样抛，走重试。
                                            copyContext.ensureActive()
                                            throw e
                                        }
                                        if (read < 0) break
                                        written += read
                                        if (written > MAX_APK_BYTES) {
                                            throw UpdateDownloadException(
                                                UpdateError.TooLarge,
                                                "安装包体积超过 200MB 上限，已停止下载",
                                            )
                                        }
                                        output.write(buffer, 0, read)
                                        onProgress?.invoke(written, total)
                                    }
                                }
                            }
                            if (cont.isActive) cont.resume(Unit)
                        } catch (e: Exception) {
                            if (cont.isCancelled) {
                                cont.resumeWithException(
                                    e as? CancellationException
                                        ?: CancellationException("下载已取消").apply { initCause(e) },
                                )
                            } else {
                                cont.resumeWithException(e)
                            }
                        }
                    }
                }
                return@withContext destFile
            } catch (e: UpdateDownloadException) {
                // 超限不重试（再下也一样大）。
                destFile.delete()
                throw e
            } catch (e: CancellationException) {
                // 取消不重试；残文件保留，下次下载从断点续下（D-P1-5）。
                throw e
            } catch (e: Exception) {
                lastError = e
                // B4-P2-9：可重试失败保留半截文件，下次尝试用 Range 续传；不再每次删。
                // 中途失败不删残文件，下一次尝试从断点续下（D-P1-5）。
                if (attempt < MAX_RETRIES) {
                    delay(ApiClient.backoffMs(attempt + 1, baseMs = 3_000L))
                }
            }
        }
        throw UpdateDownloadException(
            UpdateError.Network,
            "下载失败，已重试 $MAX_RETRIES 次：${lastError?.message ?: "网络错误"}",
            lastError,
        )
    }

    /**
     * 拉取 `<url>.sha256` 的期望值（4KB 上限，超限/失败返回 null）。
     * 网络/超时类失败重试 2 次（共 3 次尝试，P1-9）；4xx/超限等确定性失败不重试。
     */
    suspend fun fetchExpectedSha256(sha256Url: String): String? = withContext(ioDispatcher) {
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(sha256Url).get().build()
                okHttpClient.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) {
                        // B4-P2-10：5xx/429 是瞬时失败，按注释重试 2 次；4xx 确定性失败直接 null。
                        if (response.code == 429 || response.code in 500..599) {
                            throw IOException("SHA256 拉取失败：HTTP ${response.code}")
                        }
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    // 流式限量读：超 4KB 直接判无效，不整包进内存。
                    val bytes = body.byteStream().use { input ->
                        val buf = ByteArray((MAX_SHA256_FILE_BYTES + 1).toInt())
                        var read = 0
                        while (read < buf.size) {
                            val n = input.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        buf.copyOf(read)
                    }
                    if (bytes.size > MAX_SHA256_FILE_BYTES) return@withContext null
                    return@withContext UpdateService.parseSha256File(String(bytes, Charsets.UTF_8))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt < 2) delay(1_000L)
            }
        }
        null
    }

    /** 流式算文件 SHA-256（不整包进内存），与期望值比对；不一致返回 false。 */
    fun verifySha256(file: File, expectedHex: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex.trim(), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 下载 + 校验一步到位：先取哈希（带重试），再下载，最后校验。
     * 哈希拿不到就不下载；哈希失败不删已下载文件（P1-9——之前先下载后取哈希，
     * 取哈希失败还会把刚下好的文件删掉）。校验失败删文件并抛 [UpdateDownloadException]（不重试）。
     * 校验通过后才清理旧包：新文件计入保留数（P2-15——之前先清后下会多留一个旧包）。
     */
    suspend fun downloadAndVerify(
        version: String,
        destFile: File,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): File {
        val expected = fetchExpectedSha256(UpdateService.sha256Url(version))
            ?: throw UpdateDownloadException(
                UpdateError.VerifyFailed,
                "校验失败：拿不到官方 SHA256，拒绝安装",
            )
        val url = UpdateService.downloadUrl(version)
        downloadApk(url, destFile, onProgress)
        if (!verifySha256(destFile, expected)) {
            destFile.delete()
            throw UpdateDownloadException(
                UpdateError.VerifyFailed,
                "校验失败：下载文件 SHA256 与官方不一致，拒绝安装",
            )
        }
        destFile.parentFile?.let { cleanOldApkFiles(it) }
        return destFile
    }

    /** 清理旧 APK，只保留最新 [KEEP_LATEST_APKS] 个（按最后修改时间倒序）。 */
    fun cleanOldApkFiles(dir: File, keep: Int = KEEP_LATEST_APKS) {
        try {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("OrionTV_v") && f.name.endsWith(".apk")
                // B5-P1-4：按文件名排序会把 v2.0.10 排在 v2.0.9 之前（字典序
                // "1" < "9"），刚下好的新包反被当旧包删掉；改按最后修改时间倒序，
                // 与“只保留最新 N 个”的注释一致。
                // 第 5 轮 CI 跟进：lastModified 只有毫秒精度，快机器上连续创建的
                // 文件会打平——打平时结果取决于 listFiles() 的未定义顺序，
                // CI 两次因此红了、本地慢机器却全绿。
                // B8-P2-10：平局决胜不能按文件名倒序——那是 B5-P1-4 同款坑换了
                // 个位置复活（字典序 v2.0.10 < v2.0.9），打平时刚下好的包照
                // 样被当旧包删掉；改按版本号倒序（见 compareApkFilesForCleanup）。
            }?.sortedWith(Comparator(::compareApkFilesForCleanup)).orEmpty()
            if (files.size <= keep) return
            files.drop(keep).forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
            // 清理失败不阻塞更新
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
            bytes >= 1024 -> "%.1fKB".format(bytes / 1024f)
            else -> "${bytes}B"
        }
    }
}

/**
 * 第 6 轮 P2-2：HTTP 416 时本地残文件是否已可当作下完——本地已下字节数
 * 达到服务端声明体积时，不删包、直接走 SHA256 校验（校验不过再由
 * downloadAndVerify 删文件报失败）；体积对不上（服务端文件已换）或
 * 拿不到声明体积时，删残文件重下。纯逻辑，可单测。
 */
fun shouldTreat416AsComplete(resumedFrom: Long, declaredSizeBytes: Long?): Boolean =
    declaredSizeBytes != null && resumedFrom >= declaredSizeBytes

/**
 * 旧 APK 清理排序（可单测，B8-P2-10）：最后修改时间倒序为主；打平时按
 * 版本号倒序决胜，不按文件名倒序——字典序下 v2.0.10 < v2.0.9（B5-P1-4
 * 同款坑换了个位置复活），打平时刚下好的包会被当旧包删掉、该版本永远装不上。
 */
internal fun compareApkFilesForCleanup(a: File, b: File): Int {
    val byTime = b.lastModified().compareTo(a.lastModified())
    if (byTime != 0) return byTime
    return VersionUtils.compare(apkVersionOf(b), apkVersionOf(a))
}

/** 从 OrionTV_v<版本>.apk 文件名取版本号（去前后缀；取不到时原样返回，比较时按 0 处理）。 */
internal fun apkVersionOf(file: File): String =
    file.name.removePrefix("OrionTV_v").removeSuffix(".apk")

/** 更新流程错误分型（各步失败都要有明确提示，不静默失败）。 */
sealed interface UpdateError {
    /** 网络/服务器错误（版本检查、下载）。 */
    data object Network : UpdateError

    /** 版本号非法/响应结构异常（确定性错误，不重试）。 */
    data object InvalidResponse : UpdateError

    /** 已是最新版本（不是错误，用于手动检查的提示）。 */
    data object AlreadyLatest : UpdateError

    /** 该版本已被用户跳过。 */
    data object Skipped : UpdateError

    /** APK 超 200MB 上限。 */
    data object TooLarge : UpdateError

    /** SHA256 校验失败（或拿不到官方 SHA256）。 */
    data object VerifyFailed : UpdateError

    /** 调不起系统安装器。 */
    data object InstallFailed : UpdateError
}

class UpdateDownloadException(
    val error: UpdateError,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
