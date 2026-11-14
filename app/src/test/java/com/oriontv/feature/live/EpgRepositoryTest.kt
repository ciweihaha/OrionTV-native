package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空 EPG 不落盘的守卫测试（第 4 轮 P1-5；纯函数，不依赖 MockK）。 */
class EpgRepositoryTest {

    private val nonEmpty = EpgParser.EpgData(
        programmesByChannel = mapOf(
            "cctv1" to listOf(
                EpgParser.Programme("cctv1", "新闻联播", 1794628800000L, 1794632400000L),
            ),
        ),
        channelNames = mapOf("cctv1" to "CCTV1"),
    )

    @Test
    fun `non-empty epg should be saved`() {
        assertTrue(EpgRepository.shouldSaveFreshEpg(nonEmpty))
    }

    @Test
    fun `empty epg should not be saved`() {
        assertFalse(
            EpgRepository.shouldSaveFreshEpg(
                EpgParser.EpgData(emptyMap(), emptyMap()),
            ),
        )
    }

    @Test
    fun `null epg should not be saved`() {
        assertFalse(EpgRepository.shouldSaveFreshEpg(null))
    }

    @Test
    fun `decideLoad uses fresh epg on success`() {
        val cached = nonEmpty
        val fresh = EpgParser.EpgData(
            programmesByChannel = mapOf(
                "cctv1" to listOf(
                    EpgParser.Programme("cctv1", "晚间新闻", 1794628800000L, 1794632400000L),
                ),
            ),
            channelNames = mapOf("cctv1" to "CCTV1"),
        )
        val decision = EpgRepository.decideLoad(cached, fresh)
        assertEquals(fresh, decision.data)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad falls back to cached epg and flags failure`() {
        val decision = EpgRepository.decideLoad(nonEmpty, null)
        assertEquals(nonEmpty, decision.data)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with empty fresh epg falls back and flags failure`() {
        // 服务端吐空表与拉网失败同等处理：旧缓存比“无节目单”强，但要置失败标记。
        val decision = EpgRepository.decideLoad(
            nonEmpty,
            EpgParser.EpgData(emptyMap(), emptyMap()),
        )
        assertEquals(nonEmpty, decision.data)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with no cache returns null and flags failure`() {
        val decision = EpgRepository.decideLoad(null, null)
        assertNull(decision.data)
        assertTrue(decision.loadFailed)
    }
}
