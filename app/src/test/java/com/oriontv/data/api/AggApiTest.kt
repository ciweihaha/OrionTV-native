package com.oriontv.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

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

}
