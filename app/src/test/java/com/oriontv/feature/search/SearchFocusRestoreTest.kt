package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** P2-9（第 5 轮）：返回焦点恢复按条目键锚定，不按 index。 */
class SearchFocusRestoreTest {

    private fun result(source: String, id: String) = SearchResult(
        id = id,
        title = "片名$id",
        poster = "",
        source = source,
        source_name = source,
    )

    @Test
    fun `finds result by key after reorder`() {
        val results = listOf(result("agg", "2"), result("agg", "1"), result("agg", "3"))
        // 原来在 index 1 的那条，刷新后挪到了 index 0。
        assertEquals(1, findSavedResult(results, "agg+1"))
        assertEquals(0, findSavedResult(results, "agg+2"))
    }

    @Test
    fun `returns minus one when missing`() {
        val results = listOf(result("agg", "2"))
        assertEquals(-1, findSavedResult(results, "agg+1"))
        assertEquals(-1, findSavedResult(results, null))
        assertEquals(-1, findSavedResult(emptyList(), "agg+1"))
    }

    @Test
    fun `tick restore skips while remote input modal is open`() {
        // C12-P1-5：搜索页扫码弹窗打开时，全局弹窗关闭把焦点抢出弹窗；
        // tick effect 增加扫码弹窗门控，开着时跳过，关闭后由弹窗自己的
        // 关闭 effect 把焦点还给触发点。
        assertTrue(shouldRunSearchTickRestore(globalModalOpen = false, remoteInputModalOpen = false))
        assertFalse(shouldRunSearchTickRestore(globalModalOpen = true, remoteInputModalOpen = false))
        assertFalse(shouldRunSearchTickRestore(globalModalOpen = false, remoteInputModalOpen = true))
    }

    @Test
    fun `keyboard back handler yields to global modal`() {
        // C12-P2-1：屏上键盘的 BackHandler 后注册、优先于 NavHost 的全局
        // handler；全局弹窗开着时让位，返回键先关全局弹窗、而不是先收键盘。
        assertTrue(shouldEnableKeyboardBackHandler(showKeyboard = true, globalModalOpen = false))
        assertFalse(shouldEnableKeyboardBackHandler(showKeyboard = true, globalModalOpen = true))
        assertFalse(shouldEnableKeyboardBackHandler(showKeyboard = false, globalModalOpen = false))
    }
}
