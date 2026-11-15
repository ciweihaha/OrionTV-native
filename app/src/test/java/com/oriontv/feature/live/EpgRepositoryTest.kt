package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空 EPG 不落盘的守卫测试（第 4 轮 P1-5；纯函数，不依赖 MockK）。 */
class EpgRepositoryTest {

    // 节目窗口 1794628800000L–1794632400000L（2026-11-15 12:00–13:00 CST）；
    // nowMs 取窗口内，节目未播完。
    private val nowMs = 1794628800000L

    private val nonEmpty = EpgParser.EpgData(
        programmesByChannel = mapOf(
            "cctv1" to listOf(
                EpgParser.Programme("cctv1", "新闻联播", 1794628800000L, 1794632400000L),
            ),
        ),
        channelNames = mapOf("cctv1" to "CCTV1"),
    )

    private val expired = EpgParser.EpgData(
        programmesByChannel = mapOf(
            "cctv1" to listOf(
                // 全部节目已播完（endAt <= nowMs）：模拟服务端仍只发昨天那份 epg.xml。
                EpgParser.Programme("cctv1", "昨天的新闻联播", 1794542400000L, 1794546000000L),
            ),
        ),
        channelNames = mapOf("cctv1" to "CCTV1"),
    )

    @Test
    fun `non-empty epg should be saved`() {
        assertTrue(EpgRepository.shouldSaveFreshEpg(nonEmpty, nowMs))
    }

    @Test
    fun `empty epg should not be saved`() {
        assertFalse(
            EpgRepository.shouldSaveFreshEpg(
                EpgParser.EpgData(emptyMap(), emptyMap()),
                nowMs,
            ),
        )
    }

    @Test
    fun `null epg should not be saved`() {
        assertFalse(EpgRepository.shouldSaveFreshEpg(null, nowMs))
    }

    @Test
    fun `expired epg should not be saved`() {
        // D9-P1-1：节目非空但全部过期，不当成功、不落盘。
        assertFalse(EpgRepository.shouldSaveFreshEpg(expired, nowMs))
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
        val decision = EpgRepository.decideLoad(cached, LiveFetchResult.Success(fresh), nowMs)
        assertEquals(fresh, decision.data)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad falls back to cached epg and flags failure`() {
        val decision = EpgRepository.decideLoad(nonEmpty, LiveFetchResult.Failed, nowMs)
        assertEquals(nonEmpty, decision.data)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with empty fresh epg is success not failure`() {
        // D9-P2-8：拉取成功但源本身 0 节目是合法空源，按成功处理——
        // loadFailed=false，不再恒报“刷新失败”。
        // 第 10 轮 D10-P2-1：返回数据时优先用仍新鲜的旧缓存（旧缓存里
        // 的节目未播完），而不是空表——服务端偶尔吐一次空文件时，旧
        // 节目单比“当次无节目单”强。
        val decision = EpgRepository.decideLoad(
            nonEmpty,
            LiveFetchResult.Success(EpgParser.EpgData(emptyMap(), emptyMap())),
            nowMs,
        )
        assertEquals(nonEmpty, decision.data)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad with empty fresh epg and expired cache returns empty`() {
        // D10-P2-1：旧缓存本身也已过期时无可用的节目可回落，返回空表
        // （loadFailed 仍为 false：空源本身是成功的拉取）。
        val decision = EpgRepository.decideLoad(
            expired,
            LiveFetchResult.Success(EpgParser.EpgData(emptyMap(), emptyMap())),
            nowMs,
        )
        assertEquals(EpgParser.EpgData(emptyMap(), emptyMap()), decision.data)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad with empty fresh epg and no cache returns empty`() {
        // D10-P2-1：无旧缓存时同样返回空表、不报失败。
        val decision = EpgRepository.decideLoad(
            null,
            LiveFetchResult.Success(EpgParser.EpgData(emptyMap(), emptyMap())),
            nowMs,
        )
        assertEquals(EpgParser.EpgData(emptyMap(), emptyMap()), decision.data)
        assertFalse(decision.loadFailed)
    }

    @Test
    fun `decideLoad with expired fresh epg falls back and flags failure`() {
        // D9-P1-1：fresh 非空但全部过期，不当成功——回落旧缓存、置失败信号。
        val decision = EpgRepository.decideLoad(nonEmpty, LiveFetchResult.Success(expired), nowMs)
        assertEquals(nonEmpty, decision.data)
        assertTrue(decision.loadFailed)
    }

    @Test
    fun `decideLoad with no cache returns null and flags failure`() {
        val decision = EpgRepository.decideLoad(null, LiveFetchResult.Failed, nowMs)
        assertNull(decision.data)
        assertTrue(decision.loadFailed)
    }
}
