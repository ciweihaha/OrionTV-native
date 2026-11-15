package com.oriontv.data.repo

import com.oriontv.data.api.PlayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单条断点查询的缓存合并测试（B14-P2-7）。 */
class PlayRecordLookupTest {

    private fun record(title: String, playTime: Long = 0) =
        PlayRecord(title = title, play_time = playTime)

    @Test
    fun `single record response merges into cache`() {
        // 真正单条回表（仅含请求键）才合并，不把全表缓存冲成一条（D7-P2-7）。
        val cached = mapOf("a" to record("A"), "b" to record("B"))
        val merged = mergePlayRecordLookup(cached, mapOf("a" to record("A", 120)), "a")
        assertEquals(setOf("a", "b"), merged.keys)
        assertEquals(120L, merged.getValue("a").play_time)
        assertEquals(0L, merged.getValue("b").play_time)
    }

    @Test
    fun `whole table response replaces cache`() {
        // 服务端忽略 ?key= 回整表时整体替换——plus 合并会把已删记录幽灵复活：
        // 缓存里已删的键 b 又被整表带回来。
        val cached = mapOf("a" to record("A"))
        val replaced = mergePlayRecordLookup(
            cached,
            mapOf("a" to record("A", 120), "b" to record("B")),
            "a",
        )
        assertEquals(mapOf("a" to record("A", 120), "b" to record("B")), replaced)
    }

    @Test
    fun `single entry with other key replaces cache`() {
        // 单条回表却含非请求键，同样按整表处理（不是“仅含请求键”的单条）。
        val cached = mapOf("a" to record("A"), "gone" to record("已删"))
        val replaced = mergePlayRecordLookup(cached, mapOf("gone" to record("已删")), "a")
        assertEquals(mapOf("gone" to record("已删")), replaced)
        assertNull(replaced["a"])
    }

    @Test
    fun `empty response keeps cache`() {
        val cached = mapOf("a" to record("A"))
        assertEquals(cached, mergePlayRecordLookup(cached, emptyMap(), "a"))
        assertTrue(mergePlayRecordLookup(null, emptyMap(), "a").isEmpty())
    }

    @Test
    fun `single record with empty cache becomes cache`() {
        assertEquals(
            mapOf("a" to record("A")),
            mergePlayRecordLookup(null, mapOf("a" to record("A")), "a"),
        )
    }
}
