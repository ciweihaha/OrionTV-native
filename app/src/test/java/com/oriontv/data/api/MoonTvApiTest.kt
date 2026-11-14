package com.oriontv.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * MoonTvApi 重试 / 401 上限 / 键格式 / 逐条容错测试（第 4 轮 P0-2/P1-1/P1-8/P2-10/P2-11）。
 * 用拦截器假客户端，不走真网；退避注入 0，不真睡。
 */
class MoonTvApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun responseFor(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

    private fun api(client: OkHttpClient): MoonTvApi =
        MoonTvApi(client, json, Dispatchers.Unconfined, retryBackoffMs = 0L)

    private fun countingClient(handler: (Request) -> Response): Triple<OkHttpClient, AtomicInteger, AtomicReference<String>> {
        val calls = AtomicInteger(0)
        val lastUrl = AtomicReference("")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            lastUrl.set(chain.request().url.toString())
            handler(chain.request())
        }.build()
        return Triple(client, calls, lastUrl)
    }

    @Test
    fun `generateKey escapes segments and keeps separator`() {
        assertEquals("agg+123", MoonTvApi.generateKey("agg", "123"))
        assertEquals("a%25b+c%2Bd", MoonTvApi.generateKey("a%b", "c+d"))
        assertEquals("a%2Bb+c%25d", MoonTvApi.generateKey("a+b", "c%d"))
        assertEquals("agg+", MoonTvApi.generateKey("agg", ""))
    }

    @Test
    fun `4xx is not retried and throws IOException`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 404, "") }
        val thrown = assertThrows(IOException::class.java) {
            runBlocking { api(client).getFavorites("https://example.com") }
        }
        assertTrue(thrown.message!!.contains("404"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `5xx is retried three times then throws`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 500, "") }
        assertThrows(IOException::class.java) {
            runBlocking { api(client).getServerConfig("https://example.com") }
        }
        assertEquals(4, calls.get())
    }

    @Test
    fun `401 refresh is attempted at most once per call`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 401, "") }
        val moonTvApi = api(client)
        var refreshCount = 0
        moonTvApi.onUnauthorized = {
            refreshCount++
            true
        }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { moonTvApi.getServerConfig("https://example.com") }
        }
        // 第 1 次 401 续期后重试 1 次，第 2 次 401 直接抛，不再续期。
        assertEquals(2, calls.get())
        assertEquals(1, refreshCount)
    }

    @Test
    fun `searchVideo returns empty for blank query without network`() {
        val (client, calls, _) = countingClient { _ ->
            throw AssertionError("must not send a request for a blank query")
        }
        val result = runBlocking { api(client).searchVideo("https://example.com", "   ", "r1") }
        assertEquals(emptyList<SearchResult>(), result)
        assertEquals(0, calls.get())
    }

    @Test
    fun `getResources keeps parseable sites when one entry is bad`() {
        val body = """[{"key":"a","api":"http://a","name":"A"},"bad-item",
            {"key":"b","api":"http://b","name":"B"}]"""
        val (client, _, _) = countingClient { request -> responseFor(request, 200, body) }
        val sites = runBlocking { api(client).getResources("https://example.com") }
        assertEquals(listOf("a", "b"), sites.map { it.key })
    }

    @Test
    fun `getFavorites keeps parseable entries when one entry is bad`() {
        val body = """{"agg+1": {"cover":"","title":"好片","source_name":"源","total_episodes":10,"search_title":"好片"},
            "bad": "not-an-object",
            "agg+2": {"cover":"","title":"好片2","source_name":"源","total_episodes":5,"search_title":"好片2"}}"""
        val (client, _, _) = countingClient { request -> responseFor(request, 200, body) }
        val favs = runBlocking { api(client).getFavorites("https://example.com") }
        assertEquals(2, favs.size)
        assertEquals("好片", favs["agg+1"]?.title)
        assertEquals("好片2", favs["agg+2"]?.title)
    }

    @Test
    fun `getFavorites falls back to a single favorite object`() {
        val body = """{"cover":"","title":"单片","source_name":"源","total_episodes":3,"search_title":"单片"}"""
        val (client, _, _) = countingClient { request -> responseFor(request, 200, body) }
        val favs = runBlocking { api(client).getFavorites("https://example.com") }
        assertEquals(1, favs.size)
        assertEquals("单片", favs.values.first().title)
    }

    @Test
    fun `login returns false when response body is not parseable`() {
        val (client, _, _) = countingClient { request -> responseFor(request, 200, "not-json") }
        val ok = runBlocking { api(client).login("https://example.com", "u", "p") }
        assertFalse(ok)
    }

    @Test
    fun `getDoubanData encodes type parameter`() {
        val (client, _, lastUrl) = countingClient { request ->
            responseFor(request, 200, """{"code":1,"list":[]}""")
        }
        runBlocking { api(client).getDoubanData("https://example.com", "电影", "动作") }
        assertTrue(lastUrl.get().contains("type=%E7%94%B5%E5%BD%B1"))
        assertFalse(lastUrl.get().contains("type=电影"))
    }
}
