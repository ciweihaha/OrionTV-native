package com.oriontv.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailResumeTest {

    @Test
    fun `resume position only when selected episode is the recorded one`() {
        // 选中集 = 断点集：带断点位置（秒转毫秒）。
        assertEquals(90_000L, resumePositionMs(5, 5, 90L))
        // 换集后断点失效：从 0 开始。
        assertEquals(0L, resumePositionMs(6, 5, 90L))
        assertEquals(0L, resumePositionMs(0, 5, 90L))
    }

    @Test
    fun `no record means start from zero`() {
        assertEquals(0L, resumePositionMs(0, null, 90L))
        assertEquals(0L, resumePositionMs(0, 0, null))
        assertEquals(0L, resumePositionMs(0, null, null))
    }

    @Test
    fun `negative position is clamped to zero`() {
        assertEquals(0L, resumePositionMs(3, 3, -5L))
    }

    @Test
    fun `recorded episode clamps to current source size`() {
        // B7-P2-6：断点集号超出当前源集数时落到末集；集数正常时不动。
        assertEquals(9, DetailViewModel.clampSelectedEpisode(25, 10))
        assertEquals(5, DetailViewModel.clampSelectedEpisode(5, 10))
        assertEquals(0, DetailViewModel.clampSelectedEpisode(0, 10))
        assertEquals(0, DetailViewModel.clampSelectedEpisode(-1, 10))
        // 源还没回来（集数为 0）时只保底 >= 0，不动集号。
        assertEquals(25, DetailViewModel.clampSelectedEpisode(25, 0))
    }
}
