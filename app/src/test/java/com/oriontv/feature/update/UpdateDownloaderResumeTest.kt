package com.oriontv.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** downloadApk 断点续传测试（D-P1-5）：中途停顿从断点续下，不从零重下。 */
class UpdateDownloaderResumeTest {

    private val full = ByteArray(1000) { (it % 251).toByte() }

    /** 读满 [failAfter] 字节后抛 SocketTimeoutException 的响应体（模拟中途停顿）。 */
    private class FlakyBody(private val bytes: ByteArray, private val failAfter: Long) : ResponseBody() {
        override fun contentLength() = bytes.size.toLong()
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
        override fun source(): BufferedSource {
            val buffer = Buffer().write(bytes)
            var readSoFar = 0L
            return object : ForwardingSource(buffer) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (readSoFar >= failAfter) throw SocketTimeoutException("stall")
                    val allowed = minOf(byteCount, failAfter - readSoFar)
                    val n = super.read(sink, allowed)
                    if (n > 0) readSoFar += n
                    return n
                }
            }.buffer()
        }
    }

    private fun responseFor(request: Request, code: Int, body: ResponseBody, contentRange: String? = null): Response {
        val builder = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body)
        contentRange?.let { builder.header("Content-Range", it) }
        return builder.build()
    }

    @Test
    fun `resumes from breakpoint when server supports Range`() {
        val dir = Files.createTempDirectory("oriontv-resume-test").toFile()
        try {
            val getCalls = AtomicInteger(0)
            val rangeHeaders = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.method == "HEAD" -> Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                    else -> {
                        getCalls.incrementAndGet()
                        val range = request.header("Range")
                        rangeHeaders.add(range)
                        if (range == null) {
                            // 第一次：下到 400 字节后停顿。
                            responseFor(request, 200, FlakyBody(full, failAfter = 400))
                        } else {
                            // 第二次：带 Range 续下，服务端回 206。
                            assertEquals("bytes=400-", range)
                            val rest = full.copyOfRange(400, full.size)
                            responseFor(
                                request, 206,
                                rest.toResponseBody("application/octet-stream".toMediaTypeOrNull()),
                                contentRange = "bytes 400-999/1000",
                            )
                        }
                    }
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.Unconfined)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            val result = runBlocking { downloader.downloadApk("https://example.com/orionTV.apk", dest) }
            assertEquals(dest, result)
            assertEquals(2, getCalls.get())
            assertEquals(listOf(null, "bytes=400-"), rangeHeaders)
            assertTrue(dest.readBytes().contentEquals(full))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `treats 416 as complete when local file already matches declared size`() {
        // 第 6 轮 P2-2：上次已下完、未及校验就中断时，下次带 Range 起点恰在
        // EOF，服务端回 416——旧行为删整包重下（110MB 弱网下等于白下一次）；
        // 本地字节数已与 HEAD 声明体积相等时直接当下完（走校验），不删包。
        val dir = Files.createTempDirectory("oriontv-416-test").toFile()
        try {
            val getCalls = AtomicInteger(0)
            val rangeHeaders = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.method == "HEAD" -> Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                    else -> {
                        getCalls.incrementAndGet()
                        rangeHeaders.add(request.header("Range"))
                        responseFor(request, 416, ByteArray(0).toResponseBody(null))
                    }
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.Unconfined)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            dest.writeBytes(full)
            val result = runBlocking { downloader.downloadApk("https://example.com/orionTV.apk", dest) }
            assertEquals(dest, result)
            assertEquals(1, getCalls.get())
            assertEquals(listOf("bytes=1000-"), rangeHeaders)
            // 文件没被删：仍是完整的 1000 字节。
            assertTrue(dest.readBytes().contentEquals(full))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `deletes partial file and restarts from zero on 416 when sizes differ`() {
        // 第 6 轮 P2-2 的另一半：本地只有 400 字节、服务端声明 1000 字节时
        // 416 说明服务端文件已换，仍删残文件重下（旧行为保留）。
        val dir = Files.createTempDirectory("oriontv-416-test").toFile()
        try {
            val getCalls = AtomicInteger(0)
            val rangeHeaders = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.method == "HEAD" -> Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                    getCalls.get() == 0 -> {
                        getCalls.incrementAndGet()
                        rangeHeaders.add(request.header("Range"))
                        responseFor(request, 416, ByteArray(0).toResponseBody(null))
                    }
                    else -> {
                        getCalls.incrementAndGet()
                        rangeHeaders.add(request.header("Range"))
                        responseFor(request, 200, full.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                    }
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.Unconfined)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            dest.writeBytes(full.copyOfRange(0, 400))
            val result = runBlocking { downloader.downloadApk("https://example.com/orionTV.apk", dest) }
            assertEquals(dest, result)
            assertEquals(2, getCalls.get())
            assertEquals(listOf("bytes=400-", null), rangeHeaders)
            assertTrue(dest.readBytes().contentEquals(full))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `416 as complete needs declared size and full local file`() {
        // 第 6 轮 P2-2：shouldTreat416AsComplete 的纯逻辑边界——拿不到声明
        // 体积时不认、本地未下满时不认。
        assertTrue(shouldTreat416AsComplete(1000, 1000))
        assertTrue(shouldTreat416AsComplete(1200, 1000))
        assertFalse(shouldTreat416AsComplete(400, 1000))
        assertFalse(shouldTreat416AsComplete(1000, null))
        assertFalse(shouldTreat416AsComplete(0, 1000))
    }

    @Test
    fun `restarts from zero when 206 response lacks Content-Range`() {
        // B8-P2-9：206 缺 Content-Range 头时旧实现直接当续传成功追加，文件写坏、
        // 要到 SHA 校验才暴露；现在删残文件、从头重下。
        val dir = Files.createTempDirectory("oriontv-resume-test").toFile()
        try {
            val getCalls = AtomicInteger(0)
            val rangeHeaders = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.method == "HEAD" -> Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                    else -> {
                        getCalls.incrementAndGet()
                        val range = request.header("Range")
                        rangeHeaders.add(range)
                        when (getCalls.get()) {
                            1 -> {
                                // 第一次：下到 400 字节后停顿。
                                responseFor(request, 200, FlakyBody(full, failAfter = 400))
                            }
                            2 -> {
                                // 第二次：带 Range 续下，服务端回 206 但缺 Content-Range 头。
                                assertEquals("bytes=400-", range)
                                val rest = full.copyOfRange(400, full.size)
                                responseFor(
                                    request, 206,
                                    rest.toResponseBody("application/octet-stream".toMediaTypeOrNull()),
                                )
                            }
                            else -> {
                                // 第三次：残文件已删，从头下整包。
                                assertEquals(null, range)
                                responseFor(request, 200, full.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                            }
                        }
                    }
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.Unconfined)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            val result = runBlocking { downloader.downloadApk("https://example.com/orionTV.apk", dest) }
            assertEquals(dest, result)
            assertEquals(3, getCalls.get())
            assertEquals(listOf(null, "bytes=400-", null), rangeHeaders)
            assertTrue(dest.readBytes().contentEquals(full))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test

    @Test
    fun `restarts from zero when server ignores Range`() {
        val dir = Files.createTempDirectory("oriontv-resume-test").toFile()
        try {
            val getCalls = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.method == "HEAD" -> Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                    getCalls.get() == 0 -> {
                        getCalls.incrementAndGet()
                        responseFor(request, 200, FlakyBody(full, failAfter = 400))
                    }
                    else -> {
                        // 服务端不支持 Range：无视 Range 头回 200 全量。
                        getCalls.incrementAndGet()
                        responseFor(request, 200, full.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                    }
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.Unconfined)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            runBlocking { downloader.downloadApk("https://example.com/orionTV.apk", dest) }
            assertEquals(2, getCalls.get())
            // 截断重下：文件是完整的 1000 字节，不是 400 + 1000 的拼接。
            val bytes = dest.readBytes()
            assertEquals(1000, bytes.size)
            assertTrue(bytes.contentEquals(full))
        } finally {
            dir.deleteRecursively()
        }
    }
}
