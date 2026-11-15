package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3U 加载结果判定测试（第 6 轮 D6-P2-13）。 */
class M3uRepositoryTest {

    private fun channel(name: String) = M3uParser.Channel(
        id = name,
        name = name,
        urls = listOf("https://example.com/$name.m3u8"),
    )

    @Test
    fun `decideLoad uses fresh channels on success`() {
        val cached = listOf(channel("old"))
        val fresh = listOf(channel("new"))
        val decision = M3uRepository.decideLoad(cached, LiveFetchResult.Success(fresh))
        assertEquals(fresh, decision.channels)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad falls back to cached channels and flags failure`() {
        val cached = listOf(channel("old"))
        val decision = M3uRepository.decideLoad(cached, LiveFetchResult.Failed)
        assertEquals(cached, decision.channels)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with no cache returns empty and flags failure`() {
        val decision = M3uRepository.decideLoad(null, LiveFetchResult.Failed)
        assertTrue(decision.channels.isEmpty())
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with empty fresh source is success not failure`() {
        // D9-P2-8：拉取成功但源本身 0 台是合法空源，按成功处理——
        // 返回空表、loadFailed=false，不再恒报“刷新失败”。
        val cached = listOf(channel("old"))
        val decision = M3uRepository.decideLoad(cached, LiveFetchResult.Success(emptyList()))
        assertTrue(decision.channels.isEmpty())
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideEmptyAddressLoad returns empty and resets loadFailed`() {
        // D9-P2-7：空地址不算一次加载——返回空表且 loadFailed 复位为 false，
        // 否则一次失败后用户清空 M3U 地址，loadFailed 仍滞留 true。
        val decision = M3uRepository.decideEmptyAddressLoad()
        assertTrue(decision.channels.isEmpty())
        assertFalse(decision.loadFailed)
    }
}
