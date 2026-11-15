package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipTest {

    @Test
    fun `marked intro wins over default`() {
        assertEquals(45_000L, IntroSkip.resolveIntroEndMs(45_000L, 90))
        assertEquals(45_000L, IntroSkip.resolveIntroEndMs(45_000L, 0))
    }

    @Test
    fun `no mark falls back to configured seconds`() {
        assertEquals(90_000L, IntroSkip.resolveIntroEndMs(null, 90))
        assertEquals(30_000L, IntroSkip.resolveIntroEndMs(null, 30))
        assertEquals(120_000L, IntroSkip.resolveIntroEndMs(0L, 120))
    }

    @Test
    fun `no mark and zero seconds means no skip`() {
        assertEquals(0L, IntroSkip.resolveIntroEndMs(null, 0))
        assertEquals(0L, IntroSkip.resolveIntroEndMs(-5L, 0))
    }

    @Test
    fun `outro triggers at marked position`() {
        assertTrue(IntroSkip.isAtOutro(600_000L, 1_800_000L, 600_000L))
        assertTrue(IntroSkip.isAtOutro(601_000L, 1_800_000L, 600_000L))
        assertFalse(IntroSkip.isAtOutro(599_999L, 1_800_000L, 600_000L))
        assertFalse(IntroSkip.isAtOutro(600_000L, 1_800_000L, null))
        assertFalse(IntroSkip.isAtOutro(600_000L, 0L, 600_000L))
    }

    @Test
    fun `auto skip fires only inside intro window`() {
        // P1-10：开关开、位置在片头内、本集没跳过 → 自动跳；断点已过片头不触发
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false))
        assertTrue(IntroSkip.shouldAutoSkip(45_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(90_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(120_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, false, false))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, true, true))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 0L, true, false))
    }

    @Test
    fun `learn intro from manual forward seek inside window`() {
        // 片头内起跳、越过认定结束点、大跳跃 → 学
        assertTrue(IntroSkip.shouldLearnIntroEnd(0L, 120_000L, 90_000L, false))
        assertTrue(IntroSkip.shouldLearnIntroEnd(45_000L, 150_000L, 90_000L, false))
        // 片长已知、落点离片尾够远 → 学
        assertTrue(IntroSkip.shouldLearnIntroEnd(0L, 120_000L, 90_000L, false, durationMs = 1_800_000L))
    }

    @Test
    fun `do not learn when already marked or no window`() {
        // 已有手动标记：显式标记优先，不覆盖
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 120_000L, 90_000L, true))
        // 没认定过片头结束点（时长 0 = 不跳）：无从学起
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 120_000L, 0L, false))
    }

    @Test
    fun `do not learn seeks starting past the intro`() {
        // 起点已过认定结束点：正片中途的场景快进，学了会把下集正片开头也跳掉
        assertFalse(IntroSkip.shouldLearnIntroEnd(100_000L, 200_000L, 90_000L, false))
        assertFalse(IntroSkip.shouldLearnIntroEnd(90_000L, 100_000L, 90_000L, false))
    }

    @Test
    fun `do not learn capsule or auto-skip seeks and small nudges`() {
        // 落点等于认定结束点：自动跳/胶囊点的就是这个点，不是用户行为
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 90_000L, 90_000L, false))
        // 落点没越过认定结束点
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 60_000L, 90_000L, false))
        // 跳跃 <30 秒：误触的单次 +10 秒
        assertFalse(IntroSkip.shouldLearnIntroEnd(80_000L, 95_000L, 90_000L, false))
    }

    @Test
    fun `do not learn jumps past the intro or into the credits`() {
        // 落点超过 10 分钟：跳到正片中间去了
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 700_000L, 90_000L, false))
        // 落点距片尾不到 10 秒：跳到片尾去了
        assertFalse(IntroSkip.shouldLearnIntroEnd(0L, 195_000L, 90_000L, false, durationMs = 200_000L))
    }

    @Test
    fun `auto skip does not fire when intro end leaves no watchable remainder`() {
        // 第 6 轮 P1-2：短片遇默认 90 秒兜底，开播即被送到片尾前 1 秒。
        // 跳点距片尾不到 10 秒（或已越过片尾）时不自动跳。
        // 60 秒片 + 90 秒片头 → 不跳
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false, durationMs = 60_000L))
        // 90 秒片头、100 秒片长（只剩 10 秒）→ 不跳
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false, durationMs = 100_000L))
        // 90 秒片头、101 秒片长（剩 11 秒）→ 跳
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false, durationMs = 101_000L))
        // 长片正常跳
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false, durationMs = 1_800_000L))
        // 片头本身很短的短片照常跳（5 秒片头、60 秒片长）
        assertTrue(IntroSkip.shouldAutoSkip(0L, 5_000L, true, false, durationMs = 60_000L))
        // 片长未知（durationMs=0）时不做守卫，保持旧行为
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false, durationMs = 0L))
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false))
    }

    @Test
    fun `skip intro capsule shows only once per episode`() {
        // P2-5：90 秒片头不再每 10 秒重现（之前约出现 9 次）
        assertTrue(IntroSkip.shouldShowSkipIntroCapsule(true, false, false))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(true, true, false))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(true, false, true))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(false, false, false))
    }
}
