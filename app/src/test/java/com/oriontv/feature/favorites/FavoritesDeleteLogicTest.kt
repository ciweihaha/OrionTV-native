package com.oriontv.feature.favorites

import com.oriontv.data.api.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 7 轮 P2-3：收藏删失败的纯逻辑测试。
 * removeFavorite 本体是 ViewModel 协程（仓库要 Room/接口、Main dispatcher
 * 在 JVM 单测里不可用，和 FavoritesFocusPersistenceTest 同款原因不直接
 * 实例化 ViewModel）——落点键计算与 refresh 状态重建抽成纯函数钉在这里；
 * “toggle 成功后才推进焦点恢复”的顺序改动由代码审查 + CI 编译覆盖。
 */
class FavoritesDeleteLogicTest {

    private fun favorite(title: String) = Favorite(title = title)

    @Test
    fun `next focus key is the following card`() {
        val items = listOf("a" to favorite("A"), "b" to favorite("B"), "c" to favorite("C"))
        assertEquals("b", nextSavedFocusKey(items, "a"))
        assertEquals("c", nextSavedFocusKey(items, "b"))
    }

    @Test
    fun `next focus key falls back to previous card at the end`() {
        val items = listOf("a" to favorite("A"), "b" to favorite("B"), "c" to favorite("C"))
        assertEquals("b", nextSavedFocusKey(items, "c"))
    }

    @Test
    fun `next focus key is null for the only card or unknown key`() {
        assertNull(nextSavedFocusKey(listOf("a" to favorite("A")), "a"))
        assertNull(nextSavedFocusKey(listOf("a" to favorite("A"), "b" to favorite("B")), "x"))
        assertNull(nextSavedFocusKey(emptyList(), "a"))
    }

    @Test
    fun `refresh keeps favorite error removing keys and restore request`() {
        val current = FavoritesUiState(
            items = listOf("a" to favorite("A")),
            favoriteError = com.oriontv.ui.components.nextToastEvent("取消收藏失败，请检查网络后重试"),
            removingKeys = setOf("b"),
            focusRestoreRequest = 2,
        )
        val refreshed = refreshedFavoritesState(
            current = current,
            items = listOf("c" to favorite("C")),
            playRecords = emptyMap(),
            loadFailed = true,
        )
        // 新列表/失败标记替换进来。
        assertEquals(listOf("c" to favorite("C")), refreshed.items)
        assertTrue(refreshed.loadFailed)
        // 删失败提示、进行中的删除键、焦点恢复计数都保留——刷新一次不能静默清掉。
        assertEquals(current.favoriteError, refreshed.favoriteError)
        assertEquals(setOf("b"), refreshed.removingKeys)
        assertEquals(2, refreshed.focusRestoreRequest)
    }

    @Test
    fun `refresh without error stays without error`() {
        val refreshed = refreshedFavoritesState(
            current = FavoritesUiState(),
            items = listOf("a" to favorite("A")),
            playRecords = emptyMap(),
            loadFailed = false,
        )
        assertNull(refreshed.favoriteError)
    }
}
