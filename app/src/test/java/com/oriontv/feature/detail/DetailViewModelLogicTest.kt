package com.oriontv.feature.detail

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailViewModelLogicTest {

    private fun result(source: String, episodes: Int) = SearchResult(
        id = source,
        title = "片名",
        poster = "",
        episodes = (1..episodes).map { "http://x/$it.m3u8" },
        source = source,
        source_name = "源$source",
    )

    @Test
    fun `resolveCurrentOnUpdate switches away from zero-episode current to playable`() {
        // v2.0.18：当前源 0 集时，有可播的就自动切过去，详情页不留“共0集”。
        val zero = result("r1", 0)
        val playable = result("r2", 3)
        assertEquals(playable, DetailViewModel.resolveCurrentOnUpdate(zero, listOf(zero, playable)))
        // 还没有当前源时取第一个可播的（0 集排前面也不认）。
        assertEquals(playable, DetailViewModel.resolveCurrentOnUpdate(null, listOf(zero, playable)))
    }

    @Test
    fun `resolveCurrentOnUpdate keeps playable current`() {
        // v2.0.18：已有可播当前源就保留——用户手动切的源、断点集都不动。
        val current = result("r1", 2)
        val other = result("r2", 5)
        assertEquals(current, DetailViewModel.resolveCurrentOnUpdate(current, listOf(other, current)))
    }

    @Test
    fun `resolveCurrentOnUpdate falls back when nothing is playable`() {
        // 全网都没可播的：保留原当前源（仓库侧会报“没有找到相关内容”，不长期停在 0 集页）。
        val zero = result("r1", 0)
        assertEquals(zero, DetailViewModel.resolveCurrentOnUpdate(zero, listOf(zero)))
        assertEquals(zero, DetailViewModel.resolveCurrentOnUpdate(null, listOf(zero)))
    }

    @Test
    fun `not found maps to keyword hint not network error`() {
        // B10-P2-1：各源都返回、但无精确标题匹配时仓库抛“没有找到相关内容”，
        // 那是“搜不到”，不是网络失败——不能显示“加载失败，请检查网络后重试”
        assertEquals("没有找到相关内容，换个关键词试试", DetailViewModel.mapDetailError("没有找到相关内容"))
        assertEquals(
            "没有找到相关内容，换个关键词试试",
            DetailViewModel.mapDetailError("详情搜索失败：没有找到相关内容"),
        )
        // 其余分支不动
        assertEquals("搜索超时，请重试", DetailViewModel.mapDetailError("详情搜索超时，请重试"))
        // B36-P2-3：零视频源不是“搜不到”，给去设置的提示。
        assertEquals("还没有配置视频源，先去设置里添加吧", DetailViewModel.mapDetailError("服务器未配置视频源"))
        assertEquals("登录已过期，请重新登录", DetailViewModel.mapDetailError("登录已过期，请重新登录"))
        assertEquals("还没有配置源地址，先去设置里添加吧", DetailViewModel.mapDetailError("未配置服务器地址"))
        assertEquals("加载失败，请检查网络后重试", DetailViewModel.mapDetailError("HTTP 500"))
        assertEquals("加载失败，请检查网络后重试", DetailViewModel.mapDetailError(null))
    }

    @Test
    fun `playback failed sources merge only known sources without duplicates`() {
        // B31-P2-1：播放页回传的失败源并入详情页标灰集——只并入本次详情页
        // 已知源，handle 里万一残留跨片旧值不串台；已标灰的去重。
        assertEquals(
            setOf("a", "b"),
            DetailViewModel.mergePlaybackFailedSources(
                existing = setOf("a"),
                incoming = listOf("b", "b"),
                knownSources = listOf("a", "b", "c"),
            ),
        )
        // 未知源（跨片旧值）不并入
        assertEquals(
            setOf("a"),
            DetailViewModel.mergePlaybackFailedSources(
                existing = setOf("a"),
                incoming = listOf("zzz"),
                knownSources = listOf("a", "b"),
            ),
        )
        // 空回传不改动
        assertEquals(
            emptySet<String>(),
            DetailViewModel.mergePlaybackFailedSources(
                existing = emptySet(),
                incoming = emptyList(),
                knownSources = listOf("a"),
            ),
        )
    }
}
