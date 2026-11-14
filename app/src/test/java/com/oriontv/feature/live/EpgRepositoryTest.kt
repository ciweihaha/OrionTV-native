package com.oriontv.feature.live

import org.junit.Assert.assertFalse
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
}
