package com.oriontv.data.repo

import com.oriontv.data.api.Favorite
import com.oriontv.data.api.PlayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端写成功后同步 lastGood* 缓存的补丁测试（第 9 轮 D9-P2-1）：
 * 写成功后不更新，读失败回落旧表时已删项会“幽灵复活”（B14-P2-7 修过
 * 整表回表的同款，这里是写路径的同款缺口）；纯函数，不依赖 MockK。
 */
class LastGoodCachePatchTest {

    private val favorite = Favorite(title = "片", source_name = "源A")
    private val record = PlayRecord(title = "片", source_name = "源A", index = 3)

    @Test
    fun `adding a favorite patches lastGood cache`() {
        val cached = mapOf("a+1" to favorite)
        val patched = patchLastGoodEntry(cached, "b+2", favorite, true)
        assertEquals(setOf("a+1", "b+2"), patched?.keys)
        assertEquals(favorite, patched?.get("b+2"))
    }

    @Test
    fun `adding to never-fetched cache creates single-entry table`() {
        val patched = patchLastGoodEntry<String>(null, "b+2", "v", true)
        assertEquals(mapOf("b+2" to "v"), patched)
    }

    @Test
    fun `deleting a favorite removes it from lastGood cache`() {
        val cached = mapOf("a+1" to favorite, "b+2" to favorite)
        val patched = patchLastGoodEntry(cached, "a+1", null, false)
        assertEquals(mapOf("b+2" to favorite), patched)
    }

    @Test
    fun `deleting from never-fetched cache keeps null`() {
        assertNull(patchLastGoodEntry<String>(null, "a+1", null, false))
    }

    @Test
    fun `saving a play record patches lastGood cache`() {
        val cached = mapOf("a+1" to record)
        val patched = patchLastGoodEntry(cached, "b+2", record, true)
        assertEquals(record, patched?.get("b+2"))
        assertEquals(2, patched?.size)
    }

    @Test
    fun `deleting a play record removes it from lastGood cache`() {
        val cached = mapOf("a+1" to record, "b+2" to record)
        val patched = patchLastGoodEntry(cached, "a+1", null, false)
        assertEquals(mapOf("b+2" to record), patched)
    }

    @Test
    fun `adding a history keyword prepends and dedupes`() {
        val cached = listOf("旧词", "新词", "更旧")
        val patched = patchLastGoodHistory(cached, "新词", true)
        assertEquals(listOf("新词", "旧词", "更旧"), patched)
    }

    @Test
    fun `adding a history keyword to never-fetched cache creates list`() {
        assertEquals(listOf("新词"), patchLastGoodHistory(null, "新词", true))
    }

    @Test
    fun `deleting a history keyword removes it`() {
        val cached = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), patchLastGoodHistory(cached, "b", false))
    }

    @Test
    fun `deleting from never-fetched history keeps null`() {
        assertNull(patchLastGoodHistory(null, "a", false))
    }

    @Test
    fun `deleting a missing key leaves cache unchanged`() {
        val cached = mapOf("a+1" to favorite)
        assertEquals(cached, patchLastGoodEntry(cached, "nope", null, false))
        assertTrue(patchLastGoodHistory(listOf("a"), "nope", false) == listOf("a"))
    }
}
