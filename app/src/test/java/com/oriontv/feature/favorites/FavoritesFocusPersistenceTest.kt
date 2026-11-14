package com.oriontv.feature.favorites

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 轮：收藏页焦点位置进 SavedStateHandle 的存取底座——
 * 切走（导航弹掉收藏页、ViewModel 被清）再回、进程重建，都不再回到首张卡片。
 * 存取走 FavoritesViewModel 的同键；此处验往返与“用旧 map 重建新 Handle”
 * （即导航保存的返回栈状态 / 进程重建）时值还在。
 * ViewModel 本体不直接实例化（仓库要 Room/接口、单测里没有替身，和第 4 轮
 * SearchStatePersistenceTest 同款做法；接线由 CI 编译 + 全量测试覆盖）。
 */
class FavoritesFocusPersistenceTest {

    @Test
    fun `fresh handle returns defaults`() {
        val handle = SavedStateHandle()
        assertFalse(handle[FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE] ?: false)
        assertEquals(-1, handle[FavoritesViewModel.SAVED_FOCUS_INDEX] ?: -1)
    }

    @Test
    fun `saved focus index round-trips through the handle`() {
        val handle = SavedStateHandle()
        handle[FavoritesViewModel.SAVED_FOCUS_INDEX] = 7
        handle[FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE] = true
        assertEquals(7, handle[FavoritesViewModel.SAVED_FOCUS_INDEX])
        assertEquals(true, handle[FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE])
    }

    @Test
    fun `values survive recreation from saved state map`() {
        // 模拟导航保存的返回栈状态 / 进程重建：新 Handle 用旧 map 重建，
        // 焦点位置与“已落过锚点”标记都在，新 ViewModel 走恢复分支不回首张卡片。
        val restored = SavedStateHandle(
            mapOf(
                FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE to true,
                FavoritesViewModel.SAVED_FOCUS_INDEX to 12,
            ),
        )
        assertTrue(restored[FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE] ?: false)
        // 注意：不能直接写 assertEquals(12, restored[...] ?: -1)——
        // elvis 里的 -1 字面量会让重载解析走到 assertEquals(long, long)，
        // 取指被推断成 Long，存的 Int 在运行时转 Long 抛 ClassCastException。
        // 先读进定型 Int 变量再断言（第 5 轮测试 bug 的单行修复，生产代码不动）。
        val restoredIndex: Int = restored[FavoritesViewModel.SAVED_FOCUS_INDEX] ?: -1
        assertEquals(12, restoredIndex)
    }

    @Test
    fun `overwriting focus index keeps latest value`() {
        val handle = SavedStateHandle()
        handle[FavoritesViewModel.SAVED_FOCUS_INDEX] = 3
        handle[FavoritesViewModel.SAVED_FOCUS_INDEX] = 9
        assertEquals(9, handle[FavoritesViewModel.SAVED_FOCUS_INDEX])
    }
}
