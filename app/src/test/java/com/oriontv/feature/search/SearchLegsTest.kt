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
 * 第 80 轮 v2.0.22：agg 腿同款 20 秒上限，慢 agg 腿也不能拖住已到手的服务端结果。
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

    // 第 80 轮 v2.0.22：agg 腿也要有等待上限——agg 单次调用最坏约 66 秒，
    // 服务端结果早到了也不能干等它；超时即取消 agg 腿、先用服务端结果出结果。
    @Test
    fun `slow agg leg times out and server results are returned`() = runBlocking {
        supervisorScope {
            val server = async { listOf(result("哪吒", "srv")) }
            val agg = async { delay(60_000); listOf(result("哪吒2", "agg")) }
            val start = System.currentTimeMillis()
            val merged = awaitSearchLegs(
                server, agg, "哪吒",
                serverWaitMs = 5_000L, aggWaitMs = 100L,
            )
            val elapsed = System.currentTimeMillis() - start
            // agg 腿 60 秒才回，服务端结果必须在远小于 60 秒内出来。
            assertTrue("agg leg must not block on server results (elapsed=${elapsed}ms)", elapsed < 10_000)
            assertEquals(listOf("哪吒"), merged.map { it.title })
            agg.join()
            assertTrue(agg.isCancelled)
        }
    }

    @Test
    fun `agg leg timeout exception falls back to server results`() = runBlocking {
        supervisorScope {
            val server = async { listOf(result("哪吒", "srv")) }
            val agg = async<List<SearchResult>> {
                // 真生成一个 TimeoutCancellationException（其构造器是 internal，无法直接 new）。
                withTimeout(10) { delay(60_000) }
                emptyList()
            }
            val merged = awaitSearchLegs(
                server, agg, "哪吒",
                serverWaitMs = 5_000L, aggWaitMs = 5_000L,
            )
            assertEquals(listOf("哪吒"), merged.map { it.title })
        }
    }

    @Test
    fun `both legs timing out throws the server error`() {
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                supervisorScope {
                    val server = async { delay(60_000); listOf(result("服务端片", "srv")) }
                    val agg = async { delay(60_000); listOf(result("哪吒", "agg")) }
                    awaitSearchLegs(
                        server, agg, "哪吒",
                        serverWaitMs = 100L, aggWaitMs = 100L,
                    )
                }
            }
        }
        assertTrue(thrown.message!!.contains("服务端搜索超时"))
    }

    // 第 83 轮 v2.0.26：两路共用一个截止时间——两腿同时起跑，agg 腿的预算
    // 从搜索开始算；两路都慢时总等待 ≤ 两腿上限的最大值，而不是两上限之和
    //（旧实现先等服务端腿满额、再等 agg 腿满额，两路各 400ms 时要等 800ms）。
    @Test
    fun `both slow legs total wait is capped at the shared deadline`() {
        val start = System.currentTimeMillis()
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                supervisorScope {
                    val server = async { delay(60_000); listOf(result("服务端片", "srv")) }
                    val agg = async { delay(60_000); listOf(result("哪吒", "agg")) }
                    awaitSearchLegs(
                        server, agg, "哪吒",
                        serverWaitMs = 400L, aggWaitMs = 400L,
                    )
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(thrown.message!!.contains("服务端搜索超时"))
        // 共享截止：总等待约 400ms（服务端腿上限）；旧实现约 800ms。
        assertTrue("total wait must be capped at shared deadline (elapsed=${elapsed}ms)", elapsed < 650)
    }

    // 第 83 轮 v2.0.26：共享截止仍给晚到的 agg 腿留余地——服务端腿超时后，
    // agg 腿在剩余预算内回来时结果照常用，不是一到服务端上限就丢 agg。
    @Test
    fun `agg result arriving within shared deadline is used after server timeout`() = runBlocking {
        supervisorScope {
            val server = async { delay(60_000); listOf(result("服务端片", "srv")) }
            val agg = async { delay(250); listOf(result("哪吒", "agg")) }
            val merged = awaitSearchLegs(
                server, agg, "哪吒",
                serverWaitMs = 100L, aggWaitMs = 500L,
            )
            assertEquals(listOf("哪吒"), merged.map { it.title })
        }
    }
}
