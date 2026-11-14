package com.oriontv.feature.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipIntroCapsuleTest {

    @Test
    fun `capsule stays visible within ten seconds across polls then hides`() {
        // P0-1：模拟两轮 500ms 轮询——第 1 轮出现，第 2 轮仍可见（旧实现第 2 轮就藏起来，
        // 胶囊最多闪现一个轮询间隔）；10 秒后消失。
        var now = 1_000_000L
        val capsule = SkipIntroCapsule(nowMs = { now })
        // 第 1 轮轮询：在片头窗口内 → 出现
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
        assertTrue(capsule.visible)
        // 第 2 轮轮询（500ms 后）：仍在 10 秒内 → 仍可见
        now += 500
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
        assertTrue(capsule.visible)
        // 10 秒后：消失
        now += 10_000
        assertFalse(capsule.update(inWindow = true, alreadySkipped = false))
        assertFalse(capsule.visible)
    }

    @Test
    fun `capsule shows only once per episode`() {
        var now = 0L
        val capsule = SkipIntroCapsule(nowMs = { now })
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
        now += 11_000
        assertFalse(capsule.update(inWindow = true, alreadySkipped = false))
        // 隐藏后回到窗口也不再出现
        assertFalse(capsule.update(inWindow = true, alreadySkipped = false))
        // 换集重置后可再次出现
        capsule.resetForEpisode()
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
    }

    @Test
    fun `capsule appears with auto skip disabled and hides after skip`() {
        // P0-1：inWindow 不再依赖自动跳开关——关掉自动跳时胶囊也能出现（手动入口）；
        // 已跳过片头则立即隐藏、不再出现。
        val capsule = SkipIntroCapsule(nowMs = { 0L })
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
        assertFalse(capsule.update(inWindow = true, alreadySkipped = true))
        assertFalse(capsule.visible)
    }

    @Test
    fun `capsule hides immediately once seek passes intro window`() {
        // P2-4：胶囊可见时快进越过片头（inWindow 变 false），立即隐藏，
        // 不等 10 秒超时；只消耗过一次，隐藏后本集不再出现
        var now = 1_000_000L
        val capsule = SkipIntroCapsule(nowMs = { now })
        assertTrue(capsule.update(inWindow = true, alreadySkipped = false))
        now += 500
        assertFalse(capsule.update(inWindow = false, alreadySkipped = false))
        assertFalse(capsule.visible)
        assertFalse(capsule.update(inWindow = true, alreadySkipped = false))
    }

    @Test
    fun `capsule never appears outside intro window`() {
        val capsule = SkipIntroCapsule(nowMs = { 0L })
        assertFalse(capsule.update(inWindow = false, alreadySkipped = false))
        assertFalse(capsule.shownThisEpisode)
    }
}
