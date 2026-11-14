package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 缓存原子写测试（第 6 轮 D6-P2-10）：临时文件 + 重命名，永不留半截文件。 */
class LiveCacheAtomicWriteTest {

    private fun tempDir(): File = Files.createTempDirectory("livecache").toFile()

    @Test
    fun `atomic write replaces content and leaves no temp file`() {
        val dir = tempDir()
        val file = File(dir, "m3u_cache_x.json")
        writeTextAtomically(file, "one")
        assertEquals("one", file.readText())
        assertTrue(dir.listFiles()!!.none { ".tmp-" in it.name })
        writeTextAtomically(file, "two")
        assertEquals("two", file.readText())
        assertTrue(dir.listFiles()!!.none { ".tmp-" in it.name })
    }

    @Test
    fun `atomic write failure leaves original intact and no temp file`() {
        val dir = tempDir()
        val file = File(dir, "f.json")
        file.writeText("orig")
        val badTarget = File(dir, "missing-dir/f.json")
        assertThrows(IOException::class.java) { writeTextAtomically(badTarget, "new") }
        assertEquals("orig", file.readText())
        assertTrue(dir.listFiles()!!.none { ".tmp-" in it.name })
    }

    @Test
    fun `prune removes stale tmp files but keeps current cache`() {
        val dir = tempDir()
        val current = File(dir, "m3u_cache_a.json").apply { writeText("a") }
        val stale = File(dir, "m3u_cache_b.json").apply { writeText("b") }
        val staleTmp = File(dir, "m3u_cache_a.json.tmp-123").apply { writeText("x") }
        val other = File(dir, "other.txt").apply { writeText("keep") }
        pruneStaleM3uCacheFiles(dir, keepFileName = "m3u_cache_a.json")
        assertTrue(current.exists())
        assertFalse(stale.exists())
        assertFalse(staleTmp.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `prune removes stale epg tmp files but keeps epg cache`() {
        // D7-P2-6：EPG 原子写崩溃残留的 epg_cache.json.tmp-* 由 EPG 保存时清理。
        val dir = tempDir()
        val current = File(dir, "epg_cache.json").apply { writeText("{}") }
        val staleTmp = File(dir, "epg_cache.json.tmp-456").apply { writeText("x") }
        val other = File(dir, "other.txt").apply { writeText("keep") }
        pruneStaleEpgCacheFiles(dir)
        assertTrue(current.exists())
        assertFalse(staleTmp.exists())
        assertTrue(other.exists())
    }
}
