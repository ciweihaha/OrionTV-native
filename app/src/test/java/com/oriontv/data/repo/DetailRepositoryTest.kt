package com.oriontv.data.repo

import com.oriontv.data.api.AggApi
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.api.UnauthorizedException
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * DetailRepository 详情搜索回落测试（v2.0.17）：
 * MoonTV 全量搜索失败（未登录 401）时不再直接报加载失败——有首选源结果
 * 就发首选源结果，没有就回落 agg 直取（agg 不用登录）；agg 也没结果时
 * 才原样抛回原始错误。用拦截器假客户端，不走真网。
 */
class DetailRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private class FakeDetailSettings(private val baseUrl: String) : AuthSettings {
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = ""
        override suspend fun setAuthCookies(value: String) {}
        override suspend fun refreshServerConfig(): ServerConfig? = null
        override suspend fun isServerConfigUnknown(): Boolean = false
        override suspend fun setApiBaseUrl(value: String) {}
    }

    private fun responseFor(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

    /** MoonTV 假客户端：资源列表 401（未登录），单源搜索回一条“哪吒”。 */
    private fun moonTvClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/api/search/resources" -> responseFor(request, 401, "")
                "/api/search/one" -> responseFor(
                    request,
                    200,
                    """{"results":[{"id":"m1","title":"哪吒","source":"r1","source_name":"源1"}]}""",
                )
                else -> responseFor(request, 404, "")
            }
        }.build()

    /** agg 假客户端：detail 回可播的“哪吒”，videolist 回空。 */
    private fun aggClient(detailBody: String, aggCalls: AtomicInteger? = null): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            aggCalls?.incrementAndGet()
            val request = chain.request()
            when (request.url.queryParameter("ac")) {
                "detail" -> responseFor(request, 200, detailBody)
                else -> responseFor(request, 200, """{"code":1,"list":[]}""")
            }
        }.build()

    private fun repository(detailBody: String, aggCalls: AtomicInteger? = null): DetailRepository {
        val moonTvApi = MoonTvApi(moonTvClient(), json, Dispatchers.Unconfined, retryBackoffMs = 0L)
        val aggApi = AggApi(aggClient(detailBody, aggCalls), json, Dispatchers.Unconfined, retryBackoffMs = { 0L })
        return DetailRepository(moonTvApi, FakeDetailSettings("https://example.com"), aggApi, Dispatchers.Unconfined)
    }

    @Test
    fun `moontv full search 401 without preferred results falls back to agg`() {
        val detailBody = """{"code":1,"list":[{"vod_id":"s~1","vod_name":"哪吒","vod_play_from":"lzm3u8","vod_play_url":"国语中字${'$'}https://a/1.m3u8"}]}"""
        val repo = repository(detailBody)
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "哪吒", preferredSource = null, id = "s~1") { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        // agg 回落出结果、标记加载完成，不抛错。
        assertTrue(updates.isNotEmpty())
        val (results, allLoaded) = updates.last()
        assertTrue(allLoaded)
        assertEquals(listOf("哪吒"), results.map { it.title })
        assertEquals("agg:lzm3u8", results.first().source)
        assertTrue(results.first().episodes.isNotEmpty())
    }

    @Test
    fun `moontv full search 401 with preferred results keeps preferred results`() {
        val aggCalls = AtomicInteger(0)
        val repo = repository("""{"code":1,"list":[]}""", aggCalls)
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "哪吒", preferredSource = "r1", id = null) { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        // 首选源结果先发出（allLoaded=false），全量失败后仍以它收尾（allLoaded=true），
        // 不走 agg、不抛错。
        assertEquals(2, updates.size)
        assertEquals(listOf("m1"), updates.last().first.map { it.id })
        assertTrue(updates.last().second)
        assertEquals(0, aggCalls.get())
    }

    @Test
    fun `moontv 401 and agg empty rethrows original error`() {
        val repo = repository("""{"code":1,"list":[]}""")
        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                repo.searchDetail(q = "哪吒", preferredSource = null, id = "s~1") { _, _ -> }
            }
        }
    }
}
