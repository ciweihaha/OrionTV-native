package com.oriontv.feature.update

import com.oriontv.data.api.ApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
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
            okHttpClient.newCall(request).execute().use { response ->
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
        var lastError: Exception? = null
        // MAX_RETRIES 是“重试次数（不含首次）”，共 1 + 3 = 4 次尝试（之前 repeat(MAX_RETRIES)
        // 只有 3 次尝试、实际只重试 2 次，文案却写“已重试 3 次”，P2-1）。
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                destFile.parentFile?.mkdirs()
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("下载失败：响应体为空")
                    val total = body.contentLength()
                    var written = 0L
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
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
                }
                return@withContext destFile
            } catch (e: UpdateDownloadException) {
                // 超限不重试（再下也一样大）。
                destFile.delete()
                throw e
            } catch (e: CancellationException) {
                // 取消不重试；删掉未下完的残文件。
                destFile.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                destFile.delete()
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
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
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

    /** 清理旧 APK，只保留最新 [KEEP_LATEST_APKS] 个（按文件名时间戳倒序）。 */
    fun cleanOldApkFiles(dir: File, keep: Int = KEEP_LATEST_APKS) {
        try {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("OrionTV_v") && f.name.endsWith(".apk")
            }?.sortedByDescending { it.name }.orEmpty()
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
