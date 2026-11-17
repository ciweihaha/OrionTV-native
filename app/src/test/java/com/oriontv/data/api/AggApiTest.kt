package com.oriontv.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * AggApi 重试 / 熔断 / 备用端点 / 逐条容错测试（第 4 轮 P0-2/P0-3/P1-2/P1-7/P1-8）。
 * 用拦截器假客户端，不走真网；退避注入 0，不真睡。
 */
class AggApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun responseFor(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

    private fun api(client: OkHttpClient): AggApi =
        AggApi(client, json, Dispatchers.Unconfined, retryBackoffMs = { 0L })

    private fun countingClient(handler: (Request, AtomicInteger) -> Response): Pair<OkHttpClient, AtomicInteger> {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            handler(chain.request(), calls)
        }.build()
        return client to calls
    }

    @Test
    fun `4xx is not retried and throws IOException`() {
        val (client, calls) = countingClient { request, _ ->
            responseFor(request, 404, "")
        }
        val thrown = assertThrows(IOException::class.java) {
            runBlocking { api(client).videoList(1, 1) }
        }
        assertTrue(thrown.message!!.contains("404"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `5xx is retried three times then throws`() {
        val (client, calls) = countingClient { request, _ ->
            responseFor(request, 500, "")
        }
        assertThrows(IOException::class.java) {
            runBlocking { api(client).videoList(1, 1) }
        }
        assertEquals(4, calls.get())
    }

    @Test
    fun `read timeout counts breaker once per request not per attempt`() {
        val (client, calls) = countingClient { _, _ ->
            throw SocketTimeoutException("timeout")
        }
        val agg = api(client)
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { agg.videoList(1, 1) }
        }
        assertEquals(4, calls.get())
        // 一次失败请求只计 1 次，未到阈值 3，熔断不开。
        assertFalse(agg.videoListCircuitBreaker.isOpen())
        repeat(2) {
            assertThrows(SocketTimeoutException::class.java) {
                runBlocking { agg.videoList(1, 1) }
            }
        }
        assertEquals(12, calls.get())
        // 三次失败请求各计 1 次，累计 3 次，熔断开。
        assertTrue(agg.videoListCircuitBreaker.isOpen())
    }

    @Test
    fun `version falls back to json endpoint when primary fails`() {
        val (client, calls) = countingClient { request, _ ->
            val url = request.url.toString()
            if (url.contains("oriontv-version.json")) {
                responseFor(request, 200, """{"version": "1.7.2"}""")
            } else {
                responseFor(request, 500, "")
            }
        }
        val agg = api(client)
        val version = runBlocking { agg.version() }
        assertEquals("1.7.2", version)
        // 主端点重试 4 次 + 备用 1 次。
        assertEquals(5, calls.get())
    }

    @Test
    fun `version strips v prefix before validating`() {
        // B4-P2-13：服务端带 v 返回版本号时不再直接抛错，去前缀后返回纯版本号。
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, """{"version": "v2.0.1"}""")
        }
        val version = runBlocking { api(client).version() }
        assertEquals("2.0.1", version)
    }

    @Test
    fun `version validates the fallback version too`() {
        val (client, _) = countingClient { request, _ ->
            val url = request.url.toString()
            if (url.contains("oriontv-version.json")) {
                responseFor(request, 200, """{"version": "bogus"}""")
            } else {
                responseFor(request, 500, "")
            }
        }
        assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).version() }
        }
    }

    @Test
    fun `version wraps bad json as AggInvalidResponseException`() {
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, "not-json")
        }
        assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).version() }
        }
    }

    @Test
    fun `videoList keeps parseable items when one item is bad`() {
        val body = """{"code":1,"page":1,"pagecount":100,"limit":20,"total":3,"list":[
            {"vod_id":"1","vod_name":"好片","vod_pic":""},
            "bad-item",
            {"vod_id":"2","vod_name":"好片2","vod_pic":""}
        ]}"""
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, body)
        }
        val parsed = runBlocking { api(client).videoList(1, 1) }
        assertEquals(2, parsed.list?.size)
        assertEquals(listOf("好片", "好片2"), parsed.list?.map { it.vod_name })
    }

    @Test
    fun `videoList accepts string code 1`() {
        // D9-P2-9：服务端把 code 下成字符串 "1" 时，旧实现误报“JSON 损坏”——
        // 字符串数字与数字等价，应正常解析。
        val body = """{"code":"1","page":1,"pagecount":1,"limit":20,"total":1,"list":[
            {"vod_id":"1","vod_name":"好片","vod_pic":""}
        ]}"""
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, body)
        }
        val parsed = runBlocking { api(client).videoList(1, 1) }
        assertEquals(1, parsed.code)
        assertEquals(1, parsed.list?.size)
        assertEquals("好片", parsed.list?.first()?.vod_name)
    }

    @Test
    fun `videoList accepts string code 1 with null msg and null paging fields`() {
        // B58-P2-2：严格解码失败（如 code 是字符串 "1"）且 msg/分页字段为 null
        // 时，lenientAggList 不得在 jsonPrimitive 上抛 IllegalArgumentException，
        // 整页应正常救回、可空字段读成 null。
        val body = """{"code":"1","msg":null,"page":null,"pagecount":null,"limit":null,"total":null,"list":[
            {"vod_id":"1","vod_name":"好片","vod_pic":""}
        ]}"""
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, body)
        }
        val parsed = runBlocking { api(client).videoList(1, 1) }
        assertEquals(1, parsed.code)
        assertEquals(null, parsed.msg)
        assertEquals(null, parsed.page)
        assertEquals(null, parsed.pagecount)
        assertEquals(null, parsed.limit)
        assertEquals(null, parsed.total)
        assertEquals(1, parsed.list?.size)
    }

    @Test
    fun `videoList tolerates object valued msg and paging fields in lenient path`() {
        // B58-P2-2 的另一半：msg/分页字段为对象/数组等非标量时同样不得抛，
        // 读成 null、整页正常返回。
        val body = """{"code":"1","msg":{},"page":[],"pagecount":{},"limit":[],"total":{},"list":[
            {"vod_id":"1","vod_name":"好片","vod_pic":""}
        ]}"""
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, body)
        }
        val parsed = runBlocking { api(client).videoList(1, 1) }
        assertEquals(1, parsed.code)
        assertEquals(null, parsed.msg)
        assertEquals(null, parsed.page)
        assertEquals(1, parsed.list?.size)
    }

    @Test
    fun `videoList with string code 0 reports structure error not corrupt json`() {
        // D9-P2-9：code "0" 是结构异常（code != 1），不是 JSON 损坏——报错文案要准。
        val body = """{"code":"0","list":[]}"""
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, body)
        }
        val thrown = assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).videoList(1, 1) }
        }
        assertTrue(thrown.message!!.contains("结构异常"))
        assertFalse(thrown.message!!.contains("JSON 损坏"))
    }

    @Test
    fun `version with missing version field reports invalid format not corrupt json`() {
        // D9-P2-9（D6-P2-16 闭环）：VersionResponse.version 给默认值 "" 后，
        // 缺字段不再解码失败报“JSON 损坏”，而由 isValidVersion 报“版本号格式非法”。
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, "{}")
        }
        val thrown = assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).version() }
        }
        assertTrue(thrown.message!!.contains("版本号格式非法"))
        assertFalse(thrown.message!!.contains("JSON 损坏"))
    }

    @Test
    fun `oversize body is rejected before decode`() {
        val bigBody = "x".repeat(2 * 1024 * 1024 + 1)
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, bigBody)
        }
        assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).version() }
        }
    }

    @Test
    fun `declared content length over limit is rejected`() {
        val (client, _) = countingClient { request, _ ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .header("Content-Length", "3000000")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).version() }
        }
    }
    @Test
    fun `only timeout trips the breaker`() {
        // 体超时（OkHttp 读/连接超时）才记熔断；DNS 失败、其他 IO 错只重试（第 6 轮 fa348ad）
        assertTrue(AggApi.isBodyTimeout(SocketTimeoutException("timeout")))
        assertFalse(AggApi.isBodyTimeout(UnknownHostException("ysp.ciweihaha.win")))
        assertFalse(AggApi.isBodyTimeout(IOException("connection reset")))
    }

    /** 200 响应头已到、体读取才超时的响应体：read() 抛 SocketTimeoutException。 */
    private fun bodyTimeoutResponse(request: Request): Response {
        val body = object : ResponseBody() {
            override fun contentLength() = 100L
            override fun contentType() = "application/json".toMediaTypeOrNull()
            override fun source(): BufferedSource =
                object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long =
                        throw SocketTimeoutException("body timeout")
                }.buffer()
        }
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(body).build()
    }

    @Test
    fun `pure body timeouts trip the breaker after three requests`() {
        // D-P1-1：每次都是“200 响应头 + 体读取超时”，之前 recordSuccess 先清零，
        // 计数恒在 0→1 振荡、永远到不了阈值 3；现在成功只在体读完后记，3 次即开。
        val (client, calls) = countingClient { request, _ -> bodyTimeoutResponse(request) }
        val agg = api(client)
        repeat(3) {
            assertThrows(SocketTimeoutException::class.java) {
                runBlocking { agg.videoList(1, 1) }
            }
        }
        assertEquals(3, calls.get())
        assertTrue(agg.videoListCircuitBreaker.isOpen())
        // 熔断开启后不再发请求。
        assertThrows(IOException::class.java) {
            runBlocking { agg.videoList(1, 1) }
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `declared oversize closes the response`() {
        // D-P1-2：声明超限先抛那条路之前漏关 Response，5 次可占满每 host 连接池。
        var closed = false
        val (client, _) = countingClient { request, _ ->
            val body = object : ResponseBody() {
                override fun contentLength() = 2L
                override fun contentType() = "application/json".toMediaTypeOrNull()
                override fun source(): BufferedSource =
                    object : ForwardingSource(Buffer()) {
                        override fun close() {
                            closed = true
                            super.close()
                        }
                    }.buffer()
            }
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Length", "3000000")
                .body(body).build()
        }
        assertThrows(AggInvalidResponseException::class.java) {
            runBlocking { api(client).videoList(1, 1) }
        }
        assertTrue(closed)
    }

    @Test
    fun `version strips v prefix before validation`() {
        // D-P2-4：服务端带 v 返回时根治点在 AggApi，不再抛“版本号格式非法”。
        val (client, _) = countingClient { request, _ ->
            responseFor(request, 200, """{"version": "v2.0.0"}""")
        }
        val version = runBlocking { api(client).version() }
        assertEquals("2.0.0", version)
    }

    @Test
    fun `version falls back when primary hangs past its own budget`() {
        // D6-P2-1：主端点挂起时不再被 60 秒总超时卡住——25 秒预算一到即走备用。
        val (client, calls) = countingClient { request, _ ->
            val url = request.url.toString()
            if (url.contains("oriontv-version.json")) {
                responseFor(request, 200, """{"version": "1.7.2"}""")
            } else {
                Thread.sleep(2000) // 远超注入的 100ms 主端点预算
                responseFor(request, 200, """{"version": "9.9.9"}""")
            }
        }
        val agg = AggApi(
            client, json, Dispatchers.Unconfined,
            retryBackoffMs = { 0L },
            primaryVersionTimeoutMs = 100L,
            totalVersionTimeoutMs = 10_000L,
        )
        val version = runBlocking { agg.version() }
        assertEquals("1.7.2", version)
        assertEquals(2, calls.get()) // 主端点 1 次（超时取消）+ 备用 1 次
    }

    @Test
    fun `cancelled retry backoff releases probe lease`() {
        // D6-P2-2：退避 delay() 期间取消，探针租约必须释放——否则黑窗从 60 秒
        // 拖到约 120 秒（租约自然过期才放下一个探针）。
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        val (timeoutClient, _) = countingClient { _, _ -> throw SocketTimeoutException("timeout") }
        val agg = AggApi(
            timeoutClient, json, Dispatchers.Unconfined,
            retryBackoffMs = { 0L },
            videoListCircuitBreaker = breaker,
        )
        repeat(3) {
            assertThrows(SocketTimeoutException::class.java) {
                runBlocking { agg.videoList(1, 1) }
            }
        }
        assertTrue(breaker.isOpen())
        now += 61_000 // 越过 60 秒冷却期，进入半开
        // 半开试探：首试抛普通 IOException 后进入 5 秒退避，在退避中取消。
        val (ioClient, _) = countingClient { _, _ -> throw IOException("boom") }
        val aggSlow = AggApi(
            ioClient, json, Dispatchers.Unconfined,
            retryBackoffMs = { 5_000L },
            videoListCircuitBreaker = breaker,
        )
        runBlocking {
            // 确定性取消：100ms 后取消在途请求（此时必在 5 秒退避 delay 里），
            // 不用 launch + join 的手工时序——join 的取消语义在竞态下易误判。
            // 注：assertThrows 的 lambda 不是 suspend，不能直接包 withTimeout，
            // 这里用 try/catch 断言取消。
            try {
                withTimeout(100) { aggSlow.videoList(1, 1) }
                fail("expected CancellationException")
            } catch (_: CancellationException) {
                // 取消原样抛出，符合约定。
            }
        }
        // 租约已释放：下一次 isOpen 能重新拿到试探资格（返回 false），
        // 而不是继续以“探针在途”拒绝到 120 秒租约过期。
        assertFalse("probe lease should be released after cancel", breaker.isOpen())
    }

}
