package com.oriontv.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailViewModelLogicTest {

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
