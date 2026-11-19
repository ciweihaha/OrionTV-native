package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * awaitSearchLegs 双路等待测试（v2.0.17）：服务端腿慢/失败时搜索不能干等，
 * 20 秒上限一到就取消服务端腿、先用 agg 结果出结果；两路都失败时抛服务端错。
 */
class SearchLegsTest {

    private fun result(title: String, source: String) =
        SearchResult(id = title, title = title, source = source, source_name = source)

    @Test
    fun `slow server leg times out and agg results are returned`() = runBlocking {
        supervisorScope {
            val server = async { delay(60_000); listOf(result("服务端片", "srv")) }
            val agg = async { listOf(result("哪吒", "agg")) }
            val start = System.currentTimeMillis()
            val merged = awaitSearchLegs(server, agg, "哪吒", serverWaitMs = 100L)
            val elapsed = System.currentTimeMillis() - start
            // 服务端腿 60 秒才回，agg 结果必须在远小于 60 秒内出来。
            assertTrue("server leg must not block on agg (elapsed=${elapsed}ms)", elapsed < 10_000)
            assertEquals(listOf("哪吒"), merged.map { it.title })
            server.join()
            assertTrue(server.isCancelled)
        }
    }

    @Test
    fun `server leg failure falls back to agg`() = runBlocking {
        supervisorScope {
            val server = async<List<SearchResult>> { throw IOException("boom") }
            val agg = async { listOf(result("哪吒", "agg")) }
            val merged = awaitSearchLegs(server, agg, "哪吒", serverWaitMs = 5_000L)
            assertEquals(listOf("哪吒"), merged.map { it.title })
        }
    }

    @Test
    fun `server leg timeout exception falls back to agg`() = runBlocking {
        supervisorScope {
            val server = async<List<SearchResult>> {
                // 真生成一个 TimeoutCancellationException（其构造器是 internal，无法直接 new）。
                withTimeout(10) { delay(60_000) }
                emptyList()
            }
            val agg = async { listOf(result("哪吒", "agg")) }
            val merged = awaitSearchLegs(server, agg, "哪吒", serverWaitMs = 5_000L)
            assertEquals(listOf("哪吒"), merged.map { it.title })
        }
    }

    @Test
    fun `both legs failing throws the server error`() {
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                supervisorScope {
                    val server = async<List<SearchResult>> { throw IOException("server boom") }
                    val agg = async<List<SearchResult>> { throw IOException("agg boom") }
                    awaitSearchLegs(server, agg, "哪吒", serverWaitMs = 5_000L)
                }
            }
        }
        assertTrue(thrown.message!!.contains("server boom"))
    }

    @Test
    fun `server results come first when both succeed`() = runBlocking {
        supervisorScope {
            val server = async { listOf(result("哪吒", "srv")) }
            val agg = async { listOf(result("哪吒2", "agg")) }
            val merged = awaitSearchLegs(server, agg, "哪吒", serverWaitMs = 5_000L)
            assertEquals("srv", merged.first().source)
            assertEquals(2, merged.size)
        }
    }
}
