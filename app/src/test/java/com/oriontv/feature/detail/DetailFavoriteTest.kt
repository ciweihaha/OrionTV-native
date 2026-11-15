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
}
