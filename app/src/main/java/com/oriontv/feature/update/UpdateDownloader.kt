package com.oriontv.feature.update

import com.oriontv.core.util.VersionUtils
import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.executeCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
    ): File {
        // D8-P2-2：取消到达标志——取消挂钩在取消时置位（见下），外层凭它补抛
        // CE。不用 Job/ensureActive() 判断：实测 withContext 会吞掉内部
        // 抛出的 CE，且外层 ensureActive() 在 Job 已取消时仍不抛（单测时序
        // 诊断证实：cancel() 后 Job.isCancelled 为 true、关体与读抛错均在
        // 取消之后、无重试、join 快速返回，唯独 join 不抛 CE）；取消挂钩
        // provably 在取消时触发（关体发生在取消后），故以它为准。
        // copyDone 区分“取消时拷贝已正常完成”与“取消打断了拷贝”：拷贝正常
        // 完成后再取消，挂钩仍可能在 dispose 前的窗口里跑到，不能误标为取消。
        val cancelledDuringCopy = AtomicBoolean(false)
        val copyDone = AtomicBoolean(false)
        val downloaded = coroutineScope {
            async(ioDispatcher) {
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
                                        return@async destFile
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
                            body.byteStream().use { input ->
                                // D8-P2-2：第 7 轮 D7-P2-4 给 ApiClient 体读加了“取消时关流
                                // 断 socket”的 readStreamCapped，这里是同款缺口的下载器版：
                                // executeCancellable 的取消挂钩只在 execute() 调用期间
                                // 有效，响应头回来后挂钩即摘掉；拷贝循环只在每 64KB 块
                                // 之间查 ensureActive，取消到达时阻塞的 read() 不响应，
                                // 最坏要等 60 秒读超时。取消时由取消挂钩关响应体，中断
                                // socket 读；读抛错直接由外层 attempt 的 catch 分流。
                                // 注意不用 suspendCancellableCoroutine：隔离诊断已证实
                                // 吞没点是 withContext 本身（readStreamCapped 在
                                // withContext 里取消后照常返回、不抛 CE；不带
                                // withContext 时 CE 正常传出），三种收尾写法
                                // resumeWithException / 直接 throw / 不截获直抛都
                                // 走不通——故下载体改用 async + await()（见函数末尾
                                // 注释），不再用 withContext 包起来。
                                // 挂钩必须是 Job.invokeOnCancellation，在当前协程里同步
                                // 注册——不能用 launch 起一个监听协程等 awaitCancellation：
                                // launch 只把监听协程派发到 IO 池，取消若在它首次运行
                                // 之前到达，协程体（含 finally 里的关体）根本不会执行
                                // （Kotlin 官方文档 Cancellation and timeouts：
                                // “doesn't guarantee that they start running. If
                                // they're canceled first, nothing is printed”），关体
                                // 永远到不了、体读堵满 60 秒兜底——main 上累计红 5 次
                                // 的真因正是这个派发窗口（第 8 轮跟进 2）。invokeOnCompletion
                                // 同样不能用：它只在任务完成时跑，阻塞的读不返回、任务
                                // 完不成，挂钩永远等不到触发。invokeOnCancellation 在
                                // 取消到达时当场执行（取消线程上同步跑），无派发窗口；
                                // 也不能在块里调 currentCoroutineContext()（块不是
                                // suspend 上下文），context 先在外面捕获。
                                val context = currentCoroutineContext()
                                // D8-P2-2 跟进 2：取消挂钩同步注册在当前 Job 上——取消
                                // 到达时在取消线程上当场跑，先置标志再关体（读因关体抛错，
                                // 标志一定在读抛错之前已置位，外层复核读到它时无竞态）；
                                // 拷贝已正常完成时不记（copyDone 守卫）。拷贝结束（正常、
                                // 抛错、取消）即 dispose 挂钩，不留给下一次尝试。
                                // 注意：若取消在注册前已到达，invokeOnCancellation 会立即
                                // 执行挂钩——关体照常发生，不丢取消。
                                val cancelHandle = context[Job]?.invokeOnCancellation {
                                    if (!copyDone.get()) cancelledDuringCopy.set(true)
                                    runCatching { body.close() }
                                }
                                try {
                                    // 服务端不支持 Range（回 200）时截断重下；206 时追加续写。
                                    FileOutputStream(destFile, isResume).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            // D-P2-2：拷贝循环加挂起点，取消后不再把整个 APK 下完才停。
                                            context.ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) {
                                                copyDone.set(true)
                                                break
                                            }
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
                                } finally {
                                    cancelHandle?.dispose()
                                }
                            }
                        }
                        // D8-P2-2：成功返回前再查一次取消（辅助兜底——await() 才是
                        // 真正把 CE 传出的地方，见函数末尾；这里多查一次无害，
                        // 取消时尽早收尾）。
                        currentCoroutineContext().ensureActive()
                        return@async destFile
                    } catch (e: UpdateDownloadException) {
                        // 超限不重试（再下也一样大）。
                        destFile.delete()
                        throw e
                    } catch (e: CancellationException) {
                        // 取消不重试；残文件保留，下次下载从断点续下（D-P1-5）。
                        throw e
                    } catch (e: Exception) {
                        // D8-P2-2：取消到达时读抛的可能是普通 IOException（关体断读），
                        // 不是 CancellationException；先查取消：已取消即按取消收尾，
                        // 不重试、不包成下载失败（末次尝试无退避时尤其需要这行，
                        // 否则取消会被包成 UpdateDownloadException）。
                        currentCoroutineContext().ensureActive()
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
            }.await()
        }
        // D8-P2-2：取消复核。下载体改用 coroutineScope + async(IO) + await()
        // 而不用 withContext：隔离诊断证实 withContext 会吞掉内部抛出的 CE
        // （readStreamCapped 在 withContext 里取消后照常返回、不抛 CE；不带
        // withContext 时 CE 正常传出），且单测时序诊断证实外层 ensureActive()
        // 在 Job 已取消时仍不抛（cancel() 后 Job.isCancelled 为 true、关体与
        // 读抛错均在取消之后、无重试、join 快速返回，唯独 join 不抛 CE）。
        // await() 原样重抛 async 体内的异常（含 CE），取消能传出；线程仍在
        // IO 上，调用方（Main）不受影响。这里的 ensureActive() 与监听标志
        // 是辅助复核，已取消的下载绝不能当成成功返回。
        currentCoroutineContext().ensureActive()
        // D8-P2-2：取消挂钩置位的取消标志——取消到达时读被关体打断；
        // 若 await() 仍未把 CE 传出，这里凭标志补抛 CE。
        if (cancelledDuringCopy.get()) {
            throw CancellationException("下载已取消")
        }
        return downloaded
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
