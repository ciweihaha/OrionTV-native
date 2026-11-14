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
        val decision = M3uRepository.decideLoad(cached, fresh)
        assertEquals(fresh, decision.channels)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad falls back to cached channels and flags failure`() {
        val cached = listOf(channel("old"))
        val decision = M3uRepository.decideLoad(cached, emptyList())
        assertEquals(cached, decision.channels)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with no cache returns empty and flags failure`() {
        val decision = M3uRepository.decideLoad(null, emptyList())
        assertTrue(decision.channels.isEmpty())
        assertTrue(decision.loadFailed)
    }
}
