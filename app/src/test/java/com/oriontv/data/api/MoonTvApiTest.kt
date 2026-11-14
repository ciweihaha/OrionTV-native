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
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

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
    fun `login returns WrongCredentials when response body is not parseable`() {
        val (client, _, _) = countingClient { request -> responseFor(request, 200, "not-json") }
        val result = runBlocking { api(client).login("https://example.com", "u", "p") }
        assertEquals(MoonTvApi.LoginResult.WrongCredentials, result)
    }

    @Test
    fun `login returns WrongCredentials on 401 without retry`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 401, "") }
        val result = runBlocking { api(client).login("https://example.com", "u", "p") }
        assertEquals(MoonTvApi.LoginResult.WrongCredentials, result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `login retries once on 5xx and succeeds`() {
        val callCount = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val n = callCount.incrementAndGet()
            val request = chain.request()
            if (n == 1) responseFor(request, 500, "") else responseFor(request, 200, """{"ok":true}""")
        }.build()
        val result = runBlocking { api(client).login("https://example.com", "u", "p") }
        assertEquals(MoonTvApi.LoginResult.Success, result)
        assertEquals(2, callCount.get())
    }

    @Test
    fun `login returns Transient after two 5xx`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 500, "") }
        val result = runBlocking { api(client).login("https://example.com", "u", "p") }
        assertTrue(result is MoonTvApi.LoginResult.Transient)
        assertEquals(2, calls.get())
    }

    @Test
    fun `recordKey falls back to normalized title when id is blank`() {
        // 空 id 不再共用 `source+` 空键；标题归一化去空白小写并去掉 +/%。
        assertEquals("agg+title:abc", MoonTvApi.recordKey("agg", "", "A B C"))
        assertEquals("agg+title:ab", MoonTvApi.recordKey("agg", "", "a+b"))
        assertEquals("agg+title:ab", MoonTvApi.recordKey("agg", "", "a%b"))
        assertEquals("agg+title:unknown", MoonTvApi.recordKey("agg", "", "   "))
        // 有 id 时与原来一致。
        assertEquals("agg+123", MoonTvApi.recordKey("agg", "123", "片名"))
    }

    @Test
    fun `recordKey round-trips through split and rejoin`() {
        // FavoritesScreen/HomeModels 按第一个 + 拆键再重拼，键必须不变。
        val key = MoonTvApi.recordKey("agg", "", "好片 + 100%")
        val source = key.substringBefore("+", "")
        val id = key.substringAfter("+", "")
        assertEquals("agg", source)
        assertEquals(key, MoonTvApi.recordKey(source, id, "好片 + 100%"))
    }

    @Test
    fun `getFavorites returns empty map for json array body`() {
        // D-P2-15：数组体不再静默空表（现在还记一条可见日志，行为仍是返回空表）。
        val body = """[{"cover":"","title":"单片","source_name":"源","total_episodes":3,"search_title":"单片"}]"""
        val (client, _, _) = countingClient { request -> responseFor(request, 200, body) }
        val favs = runBlocking { api(client).getFavorites("https://example.com") }
        assertEquals(emptyMap<String, Favorite>(), favs)
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

    // 第 3 轮收尾 B-P2-8：服务端写操作解码失败默认 false，不再当成功。
    @Test
    fun `addFavorite decode failure defaults to false`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 200, "not-json{{{") }
        val ok = runBlocking { api(client).addFavorite("https://example.com", "k", Favorite()) }
        assertFalse(ok)
        assertEquals(1, calls.get())
    }

    @Test
    fun `deleteFavorite decode failure defaults to false`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 200, "not-json{{{") }
        val ok = runBlocking { api(client).deleteFavorite("https://example.com", "k") }
        assertFalse(ok)
        assertEquals(1, calls.get())
    }

    @Test
    fun `savePlayRecord decode failure defaults to false`() {
        val (client, calls, _) = countingClient { request -> responseFor(request, 200, "not-json{{{") }
        val ok = runBlocking {
            api(client).savePlayRecord("https://example.com", "k", PlayRecord())
        }
        assertFalse(ok)
        assertEquals(1, calls.get())
    }

    // 第 3 轮收尾 B-P2-9：搜索 JSON 损坏时抛错，与“无结果”区分。
    @Test
    fun `searchVideos corrupt JSON throws instead of empty list`() {
        val (client, _, _) = countingClient { request -> responseFor(request, 200, "not-json{{{") }
        assertThrows(IOException::class.java) {
            runBlocking { api(client).searchVideos("https://example.com", "庆余年") }
        }
    }

    @Test
    fun `searchVideos results field not an array throws`() {
        val (client, _, _) = countingClient { request ->
            responseFor(request, 200, """{"results": "oops"}""")
        }
        assertThrows(IOException::class.java) {
            runBlocking { api(client).searchVideos("https://example.com", "庆余年") }
        }
    }

    @Test
    fun `searchVideos missing results field is empty list`() {
        val (client, _, _) = countingClient { request -> responseFor(request, 200, """{"foo": 1}""") }
        val results = runBlocking { api(client).searchVideos("https://example.com", "庆余年") }
        assertTrue(results.isEmpty())
    }

    // 第 3 轮收尾 B-P2-13：去掉外层重试后，一次超时最多发 4 次（execute 内重试 3 次）。
    @Test
    fun `getResources timeout is retried only inside execute`() {
        val (client, calls, _) = countingClient { _ -> throw SocketTimeoutException("timed out") }
        assertThrows(IOException::class.java) {
            runBlocking { api(client).getResources("https://example.com") }
        }
        assertEquals(4, calls.get())
    }

    @Test
    fun `logout rethrows CancellationException`() {
        // D6-P2-7：登出中途被取消时，取消必须原样抛出，不能被 runCatching 吞掉。
        val (client, _, _) = countingClient { _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) {
            runBlocking { api(client).logout("https://example.com") }
        }
    }

    @Test
    fun `logout swallows network errors`() {
        // D6-P2-7 的另一半：登出仍是 best-effort，网络错照常吞掉、不阻断收尾。
        val (client, _, _) = countingClient { _ -> throw IOException("server down") }
        runBlocking { api(client).logout("https://example.com") }
    }

    @Test
    fun `401 refresh success retries POST once`() {
        // D6-P2-8：401 是鉴权前置拒绝、请求未被执行，续期成功后 POST 也重发一次，
        // 不再报“登录已过期”让用户手动重试。
        val (client, calls, _) = countingClient { request ->
            if (calls.get() == 1) responseFor(request, 401, "") else {
                responseFor(request, 200, """{"success": true}""")
            }
        }
        val moonTvApi = api(client)
        moonTvApi.onUnauthorized = { true }
        val ok = runBlocking {
            moonTvApi.addFavorite("https://example.com", "k", Favorite(title = "片"))
        }
        assertTrue(ok)
        assertEquals(2, calls.get())
    }

    @Test
    fun `401 refresh failure still throws for POST`() {
        // D6-P2-8 的另一半：续期失败时 POST 照常抛 UnauthorizedException，不盲重试。
        val (client, calls, _) = countingClient { request -> responseFor(request, 401, "") }
        val moonTvApi = api(client)
        moonTvApi.onUnauthorized = { false }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                moonTvApi.addFavorite("https://example.com", "k", Favorite(title = "片"))
            }
        }
        assertEquals(1, calls.get())
    }
}
