package com.oriontv.feature.search

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 6 轮 P1-1：搜索页焦点锚点进 SavedStateHandle 的存取底座——
 * 切走（导航弹掉搜索页、ViewModel 被清）再回、进程重建，都不再落回搜索框。
 * 存取走 SearchViewModel 的同键；此处验往返与“用旧 map 重建新 Handle”
 * （即导航保存的返回栈状态 / 进程重建）时值还在。
 * ViewModel 本体不直接实例化（仓库要接口/服务、单测里没有替身，和第 5 轮
 * FavoritesFocusPersistenceTest 同款做法；接线由 CI 编译 + 全量测试覆盖）。
 * 注意：读 Handle 必须写死类型（get<String>/get<Boolean>），不能只写
 * `handle[KEY] ?: -1` 再断言——assertEquals 的 long 重载会把泛型 T 推成 Long，
 * 存的 Int 取出来转 Long 直接 ClassCastException（第 5 轮修复 1 的教训）。
 */
class SearchFocusPersistenceTest {

    @Test
    fun `fresh handle returns defaults`() {
        val handle = SavedStateHandle()
        assertFalse(handle.get<Boolean>(SearchViewModel.SAVED_INITIAL_FOCUS_DONE) ?: false)
        assertNull(handle.get<String>(SearchViewModel.SAVED_RESULT_KEY))
    }

    @Test
    fun `focus anchor round-trips through the handle`() {
        val handle = SavedStateHandle()
        handle[SearchViewModel.SAVED_RESULT_KEY] = "agg+123"
        handle[SearchViewModel.SAVED_INITIAL_FOCUS_DONE] = true
        assertEquals("agg+123", handle.get<String>(SearchViewModel.SAVED_RESULT_KEY))
        assertEquals(true, handle.get<Boolean>(SearchViewModel.SAVED_INITIAL_FOCUS_DONE))
    }

    @Test
    fun `values survive recreation from saved state map`() {
        // 模拟导航保存的返回栈状态 / 进程重建：新 Handle 用旧 map 重建，
        // 焦点位置与“已落过锚点”标记都在，新 ViewModel 走恢复分支不回搜索框。
        val restored = SavedStateHandle(
            mapOf(
                SearchViewModel.SAVED_INITIAL_FOCUS_DONE to true,
                SearchViewModel.SAVED_RESULT_KEY to "agg+12",
            ),
        )
        assertTrue(restored.get<Boolean>(SearchViewModel.SAVED_INITIAL_FOCUS_DONE) ?: false)
        assertEquals("agg+12", restored.get<String>(SearchViewModel.SAVED_RESULT_KEY))
    }

    @Test
    fun `overwriting result key keeps latest value`() {
        val handle = SavedStateHandle()
        handle[SearchViewModel.SAVED_RESULT_KEY] = "agg+3"
        handle[SearchViewModel.SAVED_RESULT_KEY] = "agg+9"
        assertEquals("agg+9", handle.get<String>(SearchViewModel.SAVED_RESULT_KEY))
    }
}
