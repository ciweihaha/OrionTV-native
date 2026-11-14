package com.oriontv.feature.favorites

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 轮：收藏页焦点位置进 SavedStateHandle 的存取底座——
 * 切走（导航弹掉收藏页、ViewModel 被清）再回、进程重建，都不再回到首张卡片。
 * 存取走 FavoritesViewModel 的同键；此处验往返与“用旧 map 重建新 Handle”
 * （即导航保存的返回栈状态 / 进程重建）时值还在。
 * ViewModel 本体不直接实例化（仓库要 Room/接口、单测里没有替身，和第 4 轮
 * SearchStatePersistenceTest 同款做法；接线由 CI 编译 + 全量测试覆盖）。
 * 注意：读 Handle 必须写死类型（get<String>/get<Boolean>），不能只写
 * `handle[KEY] ?: -1` 再断言——assertEquals 的 long 重载会把泛型 T 推成 Long，
 * 存的 Int 取出来转 Long 直接 ClassCastException（修复 1 的教训）。
 * P2-9（第 5 轮 rebase 合并）：焦点锚点由 index 改为记录键（source+id），
 * 存取的键随之由 SAVED_FOCUS_INDEX 改为 SAVED_FOCUS_KEY——刷新/删除后
 * index 对应的已是另一张卡，键才稳；持久化思路（SavedStateHandle 承载）
 * 不变，仍是第 5 轮 mica P1-3/P1-4 那套。
 */
class FavoritesFocusPersistenceTest {

    @Test
    fun `fresh handle returns defaults`() {
        val handle = SavedStateHandle()
        assertFalse(handle.get<Boolean>(FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE) ?: false)
        assertNull(handle.get<String>(FavoritesViewModel.SAVED_FOCUS_KEY))
    }

    @Test
    fun `saved focus key round-trips through the handle`() {
        val handle = SavedStateHandle()
        handle[FavoritesViewModel.SAVED_FOCUS_KEY] = "agg+123"
        handle[FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE] = true
        assertEquals("agg+123", handle.get<String>(FavoritesViewModel.SAVED_FOCUS_KEY))
        assertEquals(true, handle.get<Boolean>(FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE))
    }

    @Test
    fun `values survive recreation from saved state map`() {
        // 模拟导航保存的返回栈状态 / 进程重建：新 Handle 用旧 map 重建，
        // 焦点位置与“已落过锚点”标记都在，新 ViewModel 走恢复分支不回首张卡片。
        val restored = SavedStateHandle(
            mapOf(
                FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE to true,
                FavoritesViewModel.SAVED_FOCUS_KEY to "agg+12",
            ),
        )
        assertTrue(restored.get<Boolean>(FavoritesViewModel.SAVED_INITIAL_FOCUS_DONE) ?: false)
        assertEquals("agg+12", restored.get<String>(FavoritesViewModel.SAVED_FOCUS_KEY))
    }

    @Test
    fun `overwriting focus key keeps latest value`() {
        val handle = SavedStateHandle()
        handle[FavoritesViewModel.SAVED_FOCUS_KEY] = "agg+3"
        handle[FavoritesViewModel.SAVED_FOCUS_KEY] = "agg+9"
        assertEquals("agg+9", handle.get<String>(FavoritesViewModel.SAVED_FOCUS_KEY))
    }
}
