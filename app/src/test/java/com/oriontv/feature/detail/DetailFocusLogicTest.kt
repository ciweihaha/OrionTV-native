package com.oriontv.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 7 轮 P1-1：详情页焦点 effect 的纯决策测试。
 * effect 本体是 Compose（LaunchedEffect），JVM 单测覆盖不到——
 * 决策抽成纯函数钉在这里；接线（effect 调决策、弹窗关闭 tick 恢复）
 * 由 CI 编译 + 真机验证覆盖。
 */
class DetailFocusLogicTest {

    @Test
    fun `play focus only on first arrival without modal open`() {
        // 首个 current 到达、无弹窗：落“播放”。
        assertTrue(shouldFocusPlayButton(hasCurrent = true, focusAnchored = false, globalModalOpen = false))
        // 已落过锚点（换源）：不抢。
        assertFalse(shouldFocusPlayButton(hasCurrent = true, focusAnchored = true, globalModalOpen = false))
        // 还没数据：不落。
        assertFalse(shouldFocusPlayButton(hasCurrent = false, focusAnchored = false, globalModalOpen = false))
        // P1-1：更新卡片/登录弹窗开着：不抢焦点、不置锚点。
        assertFalse(shouldFocusPlayButton(hasCurrent = true, focusAnchored = false, globalModalOpen = true))
    }

    @Test
    fun `retry focus gated by modal and one-shot per failure`() {
        // 失败到达、无数据、无弹窗：落“重试”。
        assertTrue(shouldFocusRetryButton("网络错误", hasCurrent = false, globalModalOpen = false, lastHandledError = null))
        // P1-1：弹窗开着：不抢。
        assertFalse(shouldFocusRetryButton("网络错误", hasCurrent = false, globalModalOpen = true, lastHandledError = null))
        // 有数据时 error 不触发（错误态只在 current 为空时落重试）。
        assertFalse(shouldFocusRetryButton("网络错误", hasCurrent = true, globalModalOpen = false, lastHandledError = null))
        // 同一失败只触发一次：上次已落过焦点的同文案不再触发。
        assertFalse(shouldFocusRetryButton("网络错误", hasCurrent = false, globalModalOpen = false, lastHandledError = "网络错误"))
        // 不同文案的新失败：触发。
        assertTrue(shouldFocusRetryButton("超时", hasCurrent = false, globalModalOpen = false, lastHandledError = "网络错误"))
        // 无错误：不触发。
        assertFalse(shouldFocusRetryButton(null, hasCurrent = false, globalModalOpen = false, lastHandledError = null))
    }

    @Test
    fun `restore target follows current state after modal closes`() {
        assertEquals(DetailFocusTarget.Play, detailRestoreFocusTarget(hasCurrent = true, hasError = false))
        assertEquals(DetailFocusTarget.Play, detailRestoreFocusTarget(hasCurrent = true, hasError = true))
        // 错误态（弹窗打开期间失败到达、error effect 被门控拦住）：tick 恢复落“重试”。
        assertEquals(DetailFocusTarget.Retry, detailRestoreFocusTarget(hasCurrent = false, hasError = true))
        // 加载中：不恢复。
        assertNull(detailRestoreFocusTarget(hasCurrent = false, hasError = false))
    }

    @Test
    fun `detail meta shows home rating and hides invalid ones`() {
        // 首页透传评分：年份 · 评分 · 类型 · 分类。
        assertEquals("2024 · 8.5 分 · 电影 · 喜剧", formatDetailMeta("2024", "8.5", "电影", "喜剧"))
        // 无评分（搜索/收藏/深链入口）：与旧 meta 一致。
        assertEquals("2024 · 电影 · 喜剧", formatDetailMeta("2024", null, "电影", "喜剧"))
        assertEquals("2024", formatDetailMeta("2024", null, null, null))
        // "0.0" 不显示（首页映射层已滤，这里再守一道）。
        assertEquals("2024", formatDetailMeta("2024", "0.0", null, null))
        assertEquals("2024", formatDetailMeta("2024", "  ", null, null))
        assertEquals("", formatDetailMeta(null, null, null, null))
    }
}
