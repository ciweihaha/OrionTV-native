package com.oriontv.feature.detail

import com.oriontv.data.repo.FavoriteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailFavoriteTest {

    @Test
    fun `favorite state maps to button state without showing unknown as not favorite`() {
        // 第 7 轮跟进 D7-P2-10：FAVORITE / NOT_FAVORITE 正常映射；UNKNOWN 不得
        // 画成“未收藏”——isFavorite 仍按 false 计（与 isFavorite() 的旧 Boolean
        // 契约一致），另置 unknown 由 UI 把按钮置灰禁用。
        assertEquals(
            FavoriteButtonState(isFavorite = true, unknown = false),
            DetailViewModel.mapFavoriteState(FavoriteState.FAVORITE),
        )
        assertEquals(
            FavoriteButtonState(isFavorite = false, unknown = false),
            DetailViewModel.mapFavoriteState(FavoriteState.NOT_FAVORITE),
        )
        val unknown = DetailViewModel.mapFavoriteState(FavoriteState.UNKNOWN)
        assertFalse(unknown.isFavorite)
        assertTrue(unknown.unknown)
    }

    @Test
    fun `toggle failure re-read wins over previous state`() {
        // 第 7 轮跟进 D-P2-13：toggle 失败后重读 isFavorite()，UI 显示服务端
        // 实态——写后校验分叉时数据层已先按实态更新 lastKnown 再抛错，重读
        // 读到的就是实态：加收藏分叉（服务端仍无）→ 显示未收藏；删收藏分叉
        //（服务端仍有）→ 显示已收藏。
        assertFalse(DetailViewModel.resolveFavoriteAfterToggleFailure(reRead = false, previous = true))
        assertTrue(DetailViewModel.resolveFavoriteAfterToggleFailure(reRead = true, previous = false))
    }

    @Test
    fun `toggle failure keeps previous state when re-read itself fails`() {
        // 第 7 轮跟进 D-P2-13：重读本身失败时保留切换前旧态，不二次崩溃、
        // 不凭空翻转 UI。
        assertTrue(DetailViewModel.resolveFavoriteAfterToggleFailure(reRead = null, previous = true))
        assertFalse(DetailViewModel.resolveFavoriteAfterToggleFailure(reRead = null, previous = false))
    }

    @Test
    fun `favorite state starts unknown until read back`() {
        // B25-P2-3：初始态与 init 重置共用 DetailUiState 默认值——之前默认
        // false（“已知未收藏”），首选源结果一到按钮即按“收藏”显示且可点，
        // 但真正收藏态要等全量搜索跑完才读回；窗口内点按 toggleFavorite()
        // 是读-改-写翻转，会把已收藏项反向取消。默认 true，读回前按钮置灰禁用。
        assertTrue(DetailUiState().isFavoriteUnknown)
        assertFalse(DetailUiState().isFavorite)
    }

    @Test
    fun `stale toggle result is not applied to a new title`() {
        // B12-P2-4：切换开始时快照的 key 与代次仍等于当前值时才写态——init()
        // 切新片后旧切换的 toggle() 可能已越过挂起点、取消拦不住，旧片结果
        // 写到新片态上即串台；key 或代次任一变了都不写。
        assertTrue(DetailViewModel.shouldApplyToggleResult("agg+1", "agg+1", 3, 3))
        assertFalse(DetailViewModel.shouldApplyToggleResult("agg+1", "agg+2", 3, 3))
        assertFalse(DetailViewModel.shouldApplyToggleResult("agg+1", "agg+1", 3, 4))
        assertFalse(DetailViewModel.shouldApplyToggleResult("agg+1", "agg+2", 3, 4))
    }
}
