package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTracksTest {

    private fun track(label: String, language: String?, group: Int = 0, index: Int = 0) =
        SubtitleTrackInfo(label = label, language = language, groupIndex = group, trackIndex = index)

    @Test
    fun `preferred track is Chinese by language code`() {
        val tracks = listOf(
            track("English", "eng"),
            track("", "chi"),
            track("", "en"),
        )
        assertEquals(1, choosePreferredSubtitleTrack(tracks))
    }

    @Test
    fun `preferred track accepts zh variants and label Chinese`() {
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("", "zh-CN"))))
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("", "zho"))))
        // 语言码缺失时 label 里写明中文也认
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("中文字幕", null))))
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("Chinese", null))))
    }

    @Test
    fun `preferred track falls back to English then first`() {
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("", "eng"), track("", "fra"))))
        assertEquals(0, choosePreferredSubtitleTrack(listOf(track("", "fra"), track("", "deu"))))
        assertNull(choosePreferredSubtitleTrack(emptyList()))
    }

    @Test
    fun `display name prefers label then language`() {
        assertEquals("中文字幕", subtitleDisplayName(track("中文字幕", "zh")))
        assertEquals("中文", subtitleDisplayName(track("", "zh-CN")))
        assertEquals("English", subtitleDisplayName(track("", "eng")))
        assertEquals("字幕", subtitleDisplayName(track("", null)))
        assertEquals("fra", subtitleDisplayName(track("", "fra")))
    }

    @Test
    fun `cycle goes off then tracks in order then off`() {
        val tracks = listOf(track("中文", "zh"), track("English", "en"))
        assertEquals(0, nextSubtitleSelection(tracks, null))
        assertEquals(1, nextSubtitleSelection(tracks, 0))
        assertNull(nextSubtitleSelection(tracks, 1))
        assertNull(nextSubtitleSelection(emptyList(), null))
        // 当前选中已失效（换片后下标越界）时从第 1 条开始，不直接跳到关
        assertEquals(0, nextSubtitleSelection(tracks, 5))
        assertEquals(0, nextSubtitleSelection(tracks, -1))
    }

    @Test
    fun `button label covers no tracks off and selected`() {
        assertEquals("字幕：无字幕", subtitleButtonLabel(emptyList(), null))
        val tracks = listOf(track("中文", "zh"), track("English", "en"))
        assertEquals("字幕：关", subtitleButtonLabel(tracks, null))
        assertEquals("字幕：中文", subtitleButtonLabel(tracks, 0))
        assertEquals("字幕：English", subtitleButtonLabel(tracks, 1))
        assertEquals("字幕：关", subtitleButtonLabel(tracks, 9))
    }

    @Test
    fun `refresh auto picks Chinese on first tracks`() {
        val tracks = listOf(track("English", "en"), track("中文", "zh"))
        val decision = resolveSubtitleRefresh(
            oldTracks = emptyList(), oldSelectedIndex = null,
            newTracks = tracks, manualChoice = false, lastApplied = null,
        )
        assertEquals(1, decision.selectedIndex)
        assertEquals(tracks[1], decision.trackToApply)
        assertTrue(decision.shouldApply)
    }

    @Test
    fun `refresh does not reapply the same track`() {
        val tracks = listOf(track("中文", "zh"))
        val decision = resolveSubtitleRefresh(
            oldTracks = tracks, oldSelectedIndex = 0,
            newTracks = tracks, manualChoice = false, lastApplied = tracks[0],
        )
        assertEquals(0, decision.selectedIndex)
        assertFalse(decision.shouldApply)
    }

    @Test
    fun `refresh keeps manual choice when track still present`() {
        val old = listOf(track("中文", "zh", group = 0, index = 0), track("English", "en", group = 1, index = 0))
        // 顺序变了（新片的组顺序不同），按 label + 语言重映射到新下标
        val new = listOf(track("English", "en", group = 0, index = 0), track("中文", "zh", group = 1, index = 0))
        val decision = resolveSubtitleRefresh(
            oldTracks = old, oldSelectedIndex = 1,
            newTracks = new, manualChoice = true, lastApplied = old[1],
        )
        assertEquals(0, decision.selectedIndex)
        assertEquals(new[0], decision.trackToApply)
        assertTrue(decision.shouldApply)
    }

    @Test
    fun `refresh turns subtitles off when manual track disappears`() {
        val old = listOf(track("中文", "zh"))
        val new = listOf(track("English", "en"))
        val decision = resolveSubtitleRefresh(
            oldTracks = old, oldSelectedIndex = 0,
            newTracks = new, manualChoice = true, lastApplied = old[0],
        )
        assertNull(decision.selectedIndex)
        assertNull(decision.trackToApply)
        assertTrue(decision.shouldApply)
    }

    @Test
    fun `refresh re-picks preferred when auto track disappears`() {
        val old = listOf(track("中文", "zh"))
        val new = listOf(track("English", "en"), track("法语", "fra"))
        val decision = resolveSubtitleRefresh(
            oldTracks = old, oldSelectedIndex = 0,
            newTracks = new, manualChoice = false, lastApplied = old[0],
        )
        assertEquals(0, decision.selectedIndex)
        assertTrue(decision.shouldApply)
    }

    @Test
    fun `refresh with no tracks only applies when something was applied`() {
        val applied = resolveSubtitleRefresh(
            oldTracks = listOf(track("中文", "zh")), oldSelectedIndex = 0,
            newTracks = emptyList(), manualChoice = false, lastApplied = track("中文", "zh"),
        )
        assertNull(applied.selectedIndex)
        assertTrue(applied.shouldApply)
        val nothingApplied = resolveSubtitleRefresh(
            oldTracks = emptyList(), oldSelectedIndex = null,
            newTracks = emptyList(), manualChoice = false, lastApplied = null,
        )
        assertNull(nothingApplied.selectedIndex)
        assertFalse(nothingApplied.shouldApply)
    }
}
