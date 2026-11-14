package com.oriontv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RN 迁移逐键补缺判定测试（第 6 轮 D6-P2-11）。 */
class RnMigrationKeysTest {

    @Test
    fun `keysToImport skips existing and blank keys`() {
        assertEquals(
            listOf("b", "d"),
            keysToImport(listOf("a", "b", "", "c", "d"), setOf("a", "c")),
        )
    }

    @Test
    fun `keysToImport returns all when nothing exists`() {
        assertEquals(listOf("a", "b"), keysToImport(listOf("a", "b"), emptySet()))
    }

    @Test
    fun `keysToImport returns empty when all exist`() {
        assertTrue(keysToImport(listOf("a", "b"), setOf("a", "b")).isEmpty())
    }

    @Test
    fun `shouldImportUrlSetting writes only when incoming non-blank and current blank`() {
        // D7-P2-3：逐项门控——另一项已写不影响本项；原生已有值时不覆盖。
        assertTrue(shouldImportUrlSetting("", "https://example.com"))
        assertFalse(shouldImportUrlSetting("https://old.example.com", "https://example.com"))
        assertFalse(shouldImportUrlSetting("", null))
        assertFalse(shouldImportUrlSetting("", "   "))
        assertFalse(shouldImportUrlSetting("https://old.example.com", null))
    }

    @Test
    fun `fallbackSaveTime keeps valid time and decreases for missing time`() {
        // D7-P2-5：缺时间的行按导入顺序递减，顺序唯一、可决胜。
        assertEquals(500L, fallbackSaveTime(500L, 1000L, 3))
        assertEquals(997L, fallbackSaveTime(null, 1000L, 3))
        assertEquals(998L, fallbackSaveTime(0L, 1000L, 2))
        assertEquals(999L, fallbackSaveTime(-5L, 1000L, 1))
        val times = (0 until 5).map { fallbackSaveTime(null, 1000L, it) }
        assertEquals(listOf(1000L, 999L, 998L, 997L, 996L), times)
        assertEquals(5, times.toSet().size)
    }
}
