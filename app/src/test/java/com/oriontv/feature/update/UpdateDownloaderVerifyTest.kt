package com.oriontv.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** downloadAndVerify 测试（第 4 轮 P1-9/P2-15）：先取哈希再下载，哈希失败不删旧文件。 */
class UpdateDownloaderVerifyTest {

    private val apkBytes = "fake-apk-bytes".toByteArray()

    private fun clientFor(
        sha256Status: Int,
        apkCalls: AtomicInteger,
        sha256Calls: AtomicInteger,
    ): OkHttpClient {
        val expected = UpdateService.sha256Hex(apkBytes)
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            when {
                url.contains(".sha256") -> {
                    sha256Calls.incrementAndGet()
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(sha256Status).message("test")
                        .body("$expected  orionTV.2.0.0.apk".toResponseBody("text/plain".toMediaTypeOrNull()))
                        .build()
                }
                request.method == "HEAD" -> {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("Content-Length", apkBytes.size.toString())
                        .body("".toResponseBody(null))
                        .build()
                }
                else -> {
                    apkCalls.incrementAndGet()
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(apkBytes.toResponseBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                }
            }
        }.build()
    }

    @Test
    fun `hash failure does not delete existing file and does not download`() {
        val dir = Files.createTempDirectory("oriontv-verify-test").toFile()
        try {
            val apkCalls = AtomicInteger(0)
            val sha256Calls = AtomicInteger(0)
            val downloader = UpdateDownloader(
                clientFor(sha256Status = 500, apkCalls, sha256Calls),
                Dispatchers.Unconfined,
            )
            val dest = File(dir, "OrionTV_v9999999999999.apk").apply { writeText("old-download") }
            assertThrows(UpdateDownloadException::class.java) {
                runBlocking { downloader.downloadAndVerify("2.0.0", dest) }
            }
            assertEquals(0, apkCalls.get())
            assertTrue(sha256Calls.get() >= 1)
            // 已下载文件还在（哈希失败不删）。
            assertTrue(dest.exists())
            assertEquals("old-download", dest.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hash first then download and old apks cleaned after success`() {
        val dir = Files.createTempDirectory("oriontv-verify-test").toFile()
        try {
            val apkCalls = AtomicInteger(0)
            val sha256Calls = AtomicInteger(0)
            val downloader = UpdateDownloader(
                clientFor(sha256Status = 200, apkCalls, sha256Calls),
                Dispatchers.Unconfined,
            )
            // 第 5 轮 CI 跟进：v1/v2 连续创建，在快机器上 lastModified 会打平
            //（毫秒精度），必须显式摆 mtime，否则清理结果取决于 listFiles()
            // 的未定义顺序、CI 飘红。
            val now = System.currentTimeMillis()
            File(dir, "OrionTV_v1.apk").apply {
                writeText("old1")
                setLastModified(now - 20_000L)
            }
            File(dir, "OrionTV_v2.apk").apply {
                writeText("old2")
                setLastModified(now - 10_000L)
            }
            val dest = File(dir, "OrionTV_v9999999999999.apk")
            val result = runBlocking { downloader.downloadAndVerify("2.0.0", dest) }
            assertEquals(dest, result)
            assertEquals(1, sha256Calls.get())
            assertEquals(1, apkCalls.get())
            assertTrue(dest.readBytes().contentEquals(apkBytes))
            // 校验通过后清理：新文件 + 最新 1 个旧包，v1 被删。
            assertTrue(File(dir, "OrionTV_v2.apk").exists())
            assertFalse(File(dir, "OrionTV_v1.apk").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
