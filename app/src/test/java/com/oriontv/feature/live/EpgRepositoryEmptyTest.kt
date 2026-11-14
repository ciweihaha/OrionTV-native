package com.oriontv.feature.live

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** EPG 空缓存测试（第 4 轮 P1-5）：空 EPG 不落盘、不覆盖好缓存。 */
class EpgRepositoryEmptyTest {

    @Test
    fun `empty fresh epg does not overwrite good cache`() = runBlocking {
        val cachedData = EpgParser.parse(
            """<?xml version="1.0" encoding="UTF-8"?><tv>
              <channel id="cctv1"><display-name>CCTV1</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800"><title>新闻联播</title></programme>
            </tv>""".trimIndent(),
        )
        val cacheStore = mockk<LiveCacheStore>()
        // 缓存已过期（ts=0），迫使 loadEpg 拉网。
        every { cacheStore.readEpgCache() } returns EpgCache(ts = 0L, data = cachedData)
        every { cacheStore.saveEpgCache(any()) } just Runs
        val emptyEpgXml = """<?xml version="1.0" encoding="UTF-8"?><tv></tv>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(emptyEpgXml.toResponseBody("application/xml".toMediaTypeOrNull()))
                .build()
        }.build()
        val repo = EpgRepository(client, cacheStore, Dispatchers.Unconfined)
        val result = repo.loadEpg(nowMs = 1794630600000L)
        assertEquals(cachedData, result)
        verify(exactly = 0) { cacheStore.saveEpgCache(any()) }
    }
}
