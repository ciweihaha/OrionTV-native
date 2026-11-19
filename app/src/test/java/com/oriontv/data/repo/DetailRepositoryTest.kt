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

    /** MoonTV 假客户端：资源列表正常，单源搜索回给定的 body。 */
    private fun moonTvClientOk(searchBody: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/api/search/resources" -> responseFor(request, 200, """[{"key":"r1","name":"源1"}]""")
                "/api/search/one" -> responseFor(request, 200, searchBody)
                else -> responseFor(request, 404, "")
            }
        }.build()

    /** 聚合两腿假客户端：vod-api 腿回空，agg 腿回可播详情——证明第 2 腿确实被走。 */
    private fun twoLegAggClient(detailBody: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "live.ciweihaha.win" ->
                    responseFor(request, 200, """{"code":1,"list":[]}""")
                request.url.host == "ysp.ciweihaha.win" && request.url.queryParameter("ac") == "detail" ->
                    responseFor(request, 200, detailBody)
                else -> responseFor(request, 200, """{"code":1,"list":[]}""")
            }
        }.build()

    /**
     * 聚合假客户端（v2.0.19）：vod-api 腿上 id 直取回 0 集条目（vod_play_url 为空），
     * 标题搜回两个同名候选（死掉的 45_53656 在前、可播的 46_777 在后）；agg 腿全空——
     * 证明同一腿内的标题重试确实被走、可播结果不需要等第 2 腿。
     */
    private fun deadIdAggClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val ac = request.url.queryParameter("ac")
            val ids = request.url.queryParameter("ids")
            val wd = request.url.queryParameter("wd")
            when {
                request.url.host == "live.ciweihaha.win" && ac == "detail" && ids == "45_53656" ->
                    responseFor(request, 200, """{"code":1,"list":[{"vod_id":"45_53656","vod_name":"哪吒","vod_play_from":"lzm3u8","vod_play_url":""}]}""")
                request.url.host == "live.ciweihaha.win" && ac == "videolist" && wd == "哪吒" ->
                    responseFor(request, 200, """{"code":1,"list":[{"vod_id":"45_53656","vod_name":"哪吒"},{"vod_id":"46_777","vod_name":"哪吒"}]}""")
                request.url.host == "live.ciweihaha.win" && ac == "detail" && ids == "46_777" ->
                    responseFor(request, 200, """{"code":1,"list":[{"vod_id":"46_777","vod_name":"哪吒","vod_play_from":"厂长","vod_play_url":"第1集${'$'}https://b/1.m3u8"}]}""")
                else -> responseFor(request, 200, """{"code":1,"list":[]}""")
            }
        }.build()

    /**
     * 聚合假客户端（v2.0.19）：vod-api 腿上 id 直取回 0 集条目，标题搜只回同一个死 id；
     * agg 腿全空——重试跳过已试过的 id、不重复取、不死循环，最终报“没有找到相关内容”。
     */
    private fun deadIdOnlyAggClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val ac = request.url.queryParameter("ac")
            val ids = request.url.queryParameter("ids")
            val wd = request.url.queryParameter("wd")
            when {
                request.url.host == "live.ciweihaha.win" && ac == "detail" && ids == "45_53656" ->
                    responseFor(request, 200, """{"code":1,"list":[{"vod_id":"45_53656","vod_name":"哪吒","vod_play_from":"lzm3u8","vod_play_url":""}]}""")
                request.url.host == "live.ciweihaha.win" && ac == "videolist" && wd == "哪吒" ->
                    responseFor(request, 200, """{"code":1,"list":[{"vod_id":"45_53656","vod_name":"哪吒"}]}""")
                else -> responseFor(request, 200, """{"code":1,"list":[]}""")
            }
        }.build()

    private fun repository(detailBody: String, aggCalls: AtomicInteger? = null): DetailRepository {
        val moonTvApi = MoonTvApi(moonTvClient(), json, Dispatchers.Unconfined, retryBackoffMs = 0L)
        val aggApi = AggApi(aggClient(detailBody, aggCalls), json, Dispatchers.Unconfined, retryBackoffMs = { 0L })
        return DetailRepository(moonTvApi, FakeDetailSettings("https://example.com"), aggApi, Dispatchers.Unconfined)
    }

    private fun repositoryWith(moonTv: OkHttpClient, agg: OkHttpClient): DetailRepository {
        val moonTvApi = MoonTvApi(moonTv, json, Dispatchers.Unconfined, retryBackoffMs = 0L)
        val aggApi = AggApi(agg, json, Dispatchers.Unconfined, retryBackoffMs = { 0L })
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
    fun `moontv 401 with zero-episode preferred results and empty agg reports not found`() {
        // v2.0.18：首选源结果全是 0 集时不再直接发——全量失败 + 聚合也空时
        // 报“没有找到相关内容”，详情页不展示 0 集；聚合腿确实被走了（fake 计了数）。
        val aggCalls = AtomicInteger(0)
        val repo = repository("""{"code":1,"list":[]}""", aggCalls)
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.searchDetail(q = "哪吒", preferredSource = "r1", id = null) { _, _ -> }
            }
        }
        assertTrue(thrown.message!!.contains("没有找到相关内容"))
        assertTrue(aggCalls.get() > 0)
    }

    @Test
    fun `moontv preferred zero-episode results fall back to playable agg results`() {
        // v2.0.18：首选源只有 0 集结果时，聚合回落的可播结果要发出来——
        // 发出的结果里不能有 0 集的，更不能把 0 集结果当当前源。
        val detailBody = """{"code":1,"list":[{"vod_id":"s~1","vod_name":"哪吒","vod_play_from":"lzm3u8","vod_play_url":"国语中字${'$'}https://a/1.m3u8"}]}"""
        val repo = repository(detailBody)
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "哪吒", preferredSource = "r1", id = "s~1") { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        assertTrue(updates.isNotEmpty())
        val (results, allLoaded) = updates.last()
        assertTrue(allLoaded)
        assertTrue(results.all { it.episodes.isNotEmpty() })
        assertEquals(listOf("哪吒"), results.map { it.title })
        assertEquals("agg:lzm3u8", results.first().source)
    }

    @Test
    fun `moontv merged all zero-episode results fall back to playable agg results`() {
        // v2.0.18：全量搜索只搜到 0 集结果时（merged 非空但无可播），同样走聚合回落；
        // 旧实现直接发 merged，详情页就停在 0 集源上、聚合回落根本不触发。
        val detailBody = """{"code":1,"list":[{"vod_id":"s~1","vod_name":"哪吒","vod_play_from":"lzm3u8","vod_play_url":"国语中字${'$'}https://a/1.m3u8"}]}"""
        val moonTv = moonTvClientOk("""{"results":[{"id":"m1","title":"哪吒","source":"r1","source_name":"源1"}]}""")
        val repo = repositoryWith(moonTv, aggClient(detailBody))
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "哪吒", preferredSource = null, id = "s~1") { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        assertTrue(updates.isNotEmpty())
        val (results, allLoaded) = updates.last()
        assertTrue(allLoaded)
        assertTrue(results.all { it.episodes.isNotEmpty() })
        assertEquals("agg:lzm3u8", results.first().source)
    }

    @Test
    fun `agg fallback tries vod-api first then agg second leg`() {
        // v2.0.18：vod-api 腿空时走 agg 第 2 腿（vod-api 里没有的片 agg 可能有，如《土豆屋：开始》）。
        val detailBody = """{"code":1,"list":[{"vod_id":"kanju~x","vod_name":"土豆屋:开始","vod_play_from":"看剧AI","vod_play_url":"正片${'$'}https://ysp.ciweihaha.win/agg?ac=play&token=t"}]}"""
        val repo = repositoryWith(moonTvClient(), twoLegAggClient(detailBody))
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "土豆屋:开始", preferredSource = null, id = "kanju~x") { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        assertTrue(updates.isNotEmpty())
        val (results, allLoaded) = updates.last()
        assertTrue(allLoaded)
        assertEquals(listOf("土豆屋:开始"), results.map { it.title })
        assertEquals("agg:看剧AI", results.first().source)
        assertTrue(results.first().episodes.isNotEmpty())
    }

    @Test
    fun `no playable source anywhere reports not found`() {
        // v2.0.18：MoonTV 全是 0 集、聚合也空时，报“没有找到相关内容”，不发 0 集结果。
        val moonTv = moonTvClientOk("""{"results":[]}""")
        val repo = repositoryWith(moonTv, aggClient("""{"code":1,"list":[]}"""))
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.searchDetail(q = "哪吒", preferredSource = null, id = null) { _, _ -> }
            }
        }
        assertTrue(thrown.message!!.contains("没有找到相关内容"))
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

    @Test
    fun `agg id-direct zero-episode detail falls back to title search on same leg`() {
        // v2.0.19：vod-api 腿上 id 直取的片 0 集时，同一腿内按标题再搜——
        // 旧实现直接报空，标题搜根本不跑；可播的 46_777 在同一腿里，不能等第 2 腿救。
        val repo = repositoryWith(moonTvClient(), deadIdAggClient())
        val updates = mutableListOf<Pair<List<SearchResult>, Boolean>>()
        runBlocking {
            repo.searchDetail(q = "哪吒", preferredSource = null, id = "45_53656") { results, allLoaded ->
                updates += results to allLoaded
            }
        }
        assertTrue(updates.isNotEmpty())
        val (results, allLoaded) = updates.last()
        assertTrue(allLoaded)
        assertTrue(results.all { it.episodes.isNotEmpty() })
        assertEquals(listOf("哪吒"), results.map { it.title })
        assertEquals("agg:厂长", results.first().source)
    }

    @Test
    fun `agg id-direct zero-episode detail with only same dead id reports not found`() {
        // v2.0.19：id 直取 0 集、标题搜只回同一个死 id 时跳过它、不重复取、
        // 不死循环，最终报“没有找到相关内容”。
        val repo = repositoryWith(moonTvClient(), deadIdOnlyAggClient())
        val thrown = assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                repo.searchDetail(q = "哪吒", preferredSource = null, id = "45_53656") { _, _ -> }
            }
        }
        // 未配首选源时全量搜索的 401 是原始错误，按既有语义原样抛回（不是“没内容”）。
        assertTrue(thrown.message!!.contains("登录已过期"))
    }
}
