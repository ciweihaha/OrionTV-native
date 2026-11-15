package com.oriontv.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

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

    /** 读到一半永久停顿的响应体（模拟弱网停顿）：读阻塞在 latch 上，关流时放行并抛错。 */
    private class StalledBody(private val bytes: ByteArray) : ResponseBody() {
        override fun contentLength() = bytes.size.toLong()
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
        private val releaseLatch = CountDownLatch(1)
        override fun source(): BufferedSource {
            val buffer = Buffer().write(bytes)
            return object : ForwardingSource(buffer) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    // 停满 30 秒才放行（模拟 60 秒读超时内的停顿；无修复时取消
                    // 要等它放行才停，测试断言 10 秒内停下以区分）。
                    if (!releaseLatch.await(30, TimeUnit.SECONDS)) {
                        throw SocketTimeoutException("stall")
                    }
                    throw IOException("stream closed")
                }

                override fun close() {
                    releaseLatch.countDown()
                    super.close()
                }
            }.buffer()
        }
    }

    @Test
    fun `cancelling download interrupts stalled body read`() {
        // B14-P2-1：取消下载后最长 60 秒才停——体读取阶段取消只靠每 64KB
        // 读完后的 ensureActive() 生效，阻塞读本身停满读超时；修法在拷贝循环
        // 外挂 invokeOnCancellation 关响应体流，中断 socket 读。
        val dir = Files.createTempDirectory("oriontv-cancel-test").toFile()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", full.size.toString())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                } else {
                    responseFor(request, 200, StalledBody(full))
                }
            }.build()
            val downloader = UpdateDownloader(client)
            val dest = File(dir, "OrionTV_v2.0.1.apk")
            val elapsedMs = runBlocking {
                val deferred = async(Dispatchers.IO) {
                    downloader.downloadApk("https://example.com/orionTV.apk", dest)
                }
                delay(500) // 让下载进入阻塞读
                val start = System.currentTimeMillis()
                deferred.cancel()
                val thrown = runCatching { deferred.await() }.exceptionOrNull()
                assertTrue(thrown is CancellationException)
                System.currentTimeMillis() - start
            }
            assertTrue("取消后 ${elapsedMs}ms 才停", elapsedMs < 10_000)
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

    /**
     * 服务端中途停顿、read() 一直阻塞的响应体（模拟弱网下未到 60 秒读超时
     * 的停顿）。体被关掉时放行阻塞的读并抛错——与真实 OkHttp 关响应体断
     * socket 的行为同款。source() 只建一个实例（真实 ResponseBody 的
     * source() 是同一源）：关体关的必须是读正在用的那个源。
     *
     * [stallTimeoutMs] 是停顿兜底：无修复时取消递送不到阻塞的读，读会等满
     * 它后返回 -1、下载“正常”下完（0 字节）。取消测试用 60 秒（与真实读
     * 超时一致）：之前用 10 秒时，CI 负载下测试线程唤醒延迟，10 秒兜底
     * 可能在 5 秒 withTimeout 之前先到期、下载先“正常”完成，join 正常
     * 返回、thrown 为空——不是取消机制的问题，是测试自身的竞态；60 秒
     * 让 withTimeout 永远先到，下载不可能在取消前“正常”完成。
     */
    private class StallingBody(private val stallTimeoutMs: Long = 10_000L) : ResponseBody() {
        override fun contentLength() = 1024L
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

        /** 第一次 read() 已进入阻塞（测试据此再取消）。 */
        val readStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)

        /** read() 已返回（抛错或回 -1），即停顿的体读已停下。 */
        val readFinished = CountDownLatch(1)

        @Volatile
        var closed = false

        /** 诊断计数：读进了几次、回 -1 还是抛错、关了几次。 */
        val readCalls = AtomicInteger(0)
        val readReturnedMinusOne = AtomicBoolean(false)
        val readThrew = AtomicBoolean(false)
        val closeCalls = AtomicInteger(0)

        private var cachedSource: BufferedSource? = null

        override fun source(): BufferedSource {
            return cachedSource ?: object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readCalls.incrementAndGet()
                    readStarted.countDown()
                    try {
                        // 有修复时关体立即放行，远不到兜底；无修复时等满兜底后
                        // 返回 -1，外面的 withTimeout(15 秒) 先抛超时取消异常。
                        release.await(stallTimeoutMs, TimeUnit.MILLISECONDS)
                        if (closed) {
                            readThrew.set(true)
                            throw IOException("body closed")
                        }
                        readReturnedMinusOne.set(true)
                        return -1
                    } finally {
                        readFinished.countDown()
                    }
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() {
                    closeCalls.incrementAndGet()
                    closed = true
                    release.countDown()
                }
            }.buffer().also { cachedSource = it }
        }
    }

    @Test
    fun `stalled body read blocks until body is closed`() {
        // 诊断探针（不取消）：只确认停顿的 read() 真的会堵住下载。若 read()
        // 立即回 -1，下载会立刻“成功”，取消测试的失败就与取消机制无关。
        val dir = Files.createTempDirectory("oriontv-cancel-test").toFile()
        try {
            val body = StallingBody()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", "1024")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                } else {
                    responseFor(request, 200, body)
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.IO)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            var stillRunning = false
            runBlocking {
                val job = async(Dispatchers.IO) {
                    downloader.downloadApk("https://example.com/orionTV.apk", dest)
                }
                assertTrue(body.readStarted.await(10, TimeUnit.SECONDS))
                delay(3_000)
                stillRunning = job.isActive
                job.cancel()
                // 收尾：取消后最多等 15 秒让任务收尾（10 秒停顿兜底），
                // 不让 runBlocking 挂住整个测试任务。
                withTimeoutOrNull(15_000) { job.join() }
            }
            assertTrue("停顿的体读应堵住下载（3 秒后仍在跑）", stillRunning)
            assertTrue(dest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancel during stalled body read stops promptly`() {
        // D8-P2-2：服务端停顿、read() 阻塞时取消下载，必须立即停（关体断读），
        // 不等 60 秒读超时；取消后残文件保留，下次可断点续传。
        // 注意：下载器用 Dispatchers.IO 构造（与生产侧 AppModule 一致）——
        // Dispatchers.Unconfined 下取消递送语义不同，不是生产形态，不测它。
        val dir = Files.createTempDirectory("oriontv-cancel-test").toFile()
        try {
            // 停顿兜底 60 秒（与真实读超时一致）：见 StallingBody 注释，
            // 10 秒兜底会在 CI 线程延迟下先于 15 秒 withTimeout 到期、
            // 让下载“正常”完成，是测试竞态而非取消机制问题。
            val body = StallingBody(stallTimeoutMs = 60_000L)
            val getCalls = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Length", "1024")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                } else {
                    getCalls.incrementAndGet()
                    responseFor(request, 200, body)
                }
            }.build()
            val downloader = UpdateDownloader(client, Dispatchers.IO)
            val dest = File(dir, "OrionTV_v2.0.0.apk")
            // 断言一律放在 runBlocking 外面、各自独立一行：挂起 lambda 里的
            // 断言失败时堆栈只报 runBlocking 行，分不清是哪一环；放外面后
            // 失败行号直接指明卡点。joinMs 的分段：<20 秒且 thrown 为空
            // = 读立即回 -1（假体问题）；>=20 秒 = 停顿兜底先到期
            //（取消未递送或测试线程被饿死）；15 秒左右且为超时异常 = 取消
            // 未递送到阻塞的读。20 秒上限的构成：体读停下信号最多等 15 秒
            //（见下），再给 await() 收尾留 5 秒余量；仍远小于 60 秒停顿兜底。
            val readEntered: Boolean
            val readStopped: Boolean
            val thrown: CancellationException?
            var joinMs = -1L
            // 诊断时序：cancel() 调用点、调用后 Job 是否已取消/已完成。
            var cancelAt = 0L
            var cancelledImmediately = false
            var completedImmediately = false
            runBlocking {
                // 下载跑在 IO 线程：阻塞的 read() 占一条线程，主线程仍可取消。
                val job = async(Dispatchers.IO) {
                    downloader.downloadApk("https://example.com/orionTV.apk", dest)
                }
                readEntered = body.readStarted.await(10, TimeUnit.SECONDS)
                cancelAt = System.nanoTime()
                job.cancel()
                // 诊断：cancel() 之后 Job 必须已取消；若 isCancelled 为 false
                // 且 isCompleted 为 true，说明下载在取消前已完成、cancel 是
                // 空操作——外层复核自然看不到已取消的 Job。
                cancelledImmediately = job.isCancelled
                completedImmediately = job.isCompleted
                // D8-P2-2 跟进（第 13 轮复测确诊）：join() 永远拿不到取消异常——
                // Job.join() 只等任务结束，已取消任务的 join() 正常返回、不重抛
                // CancellationException，所以 thrown 恒为 null，前面三次生产侧
                // 修复（watcher 关体、withContext 外复核）都是对的、测法是错的。
                // 同 ApiClientTest 的 D7-P2-4 写法：对 Deferred 用 await() 才
                // 能拿到下载自身的 CancellationException；若取消递送不到，
                // withTimeout 先抛 TimeoutCancellationException。
                // D8-P2-2 跟进（第 8 轮跟进，main 上累计红 4 次）：旧写法在
                // withTimeout(5 秒) 里等 job.await()，CI 调度负载下监听协程
                // 常常 5 秒内排不上队关体，await 先超时、断言在读还没停下时
                // 就跑，446 行 readThrew 恒假。改确定性等待：先等体读本身
                // 停下的信号（readFinished，15 秒有界），再等下载协程收尾；
                // 15 秒是失败上限、不是期望耗时——健康时读在 1 秒内就停。
                // 若取消根本没递送，readFinished 15 秒不到、直接判失败。
                readStopped = body.readFinished.await(15, TimeUnit.SECONDS)
                thrown = try {
                    withTimeout(15_000) { job.await() }
                    null
                } catch (e: CancellationException) {
                    e
                }
                joinMs = (System.nanoTime() - cancelAt) / 1_000_000
            }
            assertTrue("下载应在 10 秒内进入体读", readEntered)
            // 确定性等待的断言先行：体读 15 秒内没停下，说明关体根本没到达，
            // 后面的读行为断言必然也挂、在这里先报出真正的卡点。
            assertTrue("取消后 15 秒内停顿的体读应停下（关体未到达）", readStopped)
            // 诊断（先于行为断言）：cancel() 必须真的取消了 Job，否则下载在
            // 取消前已完成、cancel 是空操作。（关体/读抛错相对 cancelAt 的时序
            // 断言已删——随 CI 线程调度抖动，第 13 轮复测与合并轮同代码一过一挂。）
            assertTrue("cancel() 后 Job 应已取消（否则下载在取消前已完成，completed=$completedImmediately）", cancelledImmediately)
            // 先断言读的行为本身（失败行号直接说明读干了什么）：
            // 正常取消路径：读进 1 次、因关体抛错、不回 -1。
            assertEquals("读应只进 1 次（无重试）", 1, body.readCalls.get())
            assertTrue("读应因关体抛错", body.readThrew.get())
            assertTrue("读不应回 -1（回 -1 即下载‘正常’完成）", !body.readReturnedMinusOne.get())
            // GET 只发 1 次：若发了 2 次，说明读的异常到了 attempt 的 catch
            // 但 Job 未取消、走了 3 秒退避 + 重试（取消未递送到下载协程）；
            // 若只 1 次且后面 assertNotNull 失败，说明异常在
            // suspendCancellableCoroutine/withContext 里被吞、下载直接
            // “正常”返回。
            assertEquals("GET 应只发 1 次（无重试）", 1, getCalls.get())
            assertTrue("join 应在 20 秒内返回（否则是停顿兜底 60 秒先到期）", joinMs < 20_000)
            assertNotNull("取消的下载应抛 CancellationException", thrown)
            assertTrue(
                "取消应在 15 秒内生效（不应是 withTimeout 的超时取消异常）",
                thrown !is TimeoutCancellationException,
            )
            assertTrue(dest.exists())
        } finally {
            dir.deleteRecursively()
        }
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
