package com.oriontv.data.local

import org.junit.Assert.assertEquals
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
}
