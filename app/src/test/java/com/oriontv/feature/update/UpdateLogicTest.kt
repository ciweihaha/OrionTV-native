package com.oriontv.feature.update

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test
    fun `silent check interval is 12 hours`() {
        val now = 1_000_000_000L
        assertTrue(UpdateService.shouldCheckNow(0L, now))
        assertTrue(UpdateService.shouldCheckNow(now - 12 * 60 * 60 * 1000L, now))
        assertFalse(UpdateService.shouldCheckNow(now - 11 * 60 * 60 * 1000L, now))
        assertFalse(UpdateService.shouldCheckNow(now, now))
    }

    @Test
    fun `skipped version only matches exact version`() {
        assertTrue(UpdateService.isSkippedVersion("2.0.1", "2.0.1"))
        assertFalse(UpdateService.isSkippedVersion("2.0.2", "2.0.1"))
        assertFalse(UpdateService.isSkippedVersion("2.0.1", ""))
    }

    @Test
    fun `verify sha256 matches file content`() {
        val downloader = UpdateDownloader(OkHttpClient())
        val dir = Files.createTempDirectory("oriontv-update-test").toFile()
        try {
            val file = File(dir, "test.bin")
            file.writeBytes("hello".toByteArray())
            assertTrue(
                downloader.verifySha256(
                    file,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ),
            )
            assertFalse(downloader.verifySha256(file, "0".repeat(64)))
            assertFalse(downloader.verifySha256(File(dir, "missing.apk"), "a".repeat(64)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clean old apks keeps latest two by name`() {
        val downloader = UpdateDownloader(OkHttpClient())
        val dir = Files.createTempDirectory("oriontv-apk-test").toFile()
        try {
            listOf("OrionTV_v1.apk", "OrionTV_v2.apk", "OrionTV_v3.apk", "OrionTV_v4.apk")
                .forEach { File(dir, it).writeText("x") }
            File(dir, "other.txt").writeText("keep me")
            downloader.cleanOldApkFiles(dir)
            val remaining = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertEquals(setOf("OrionTV_v4.apk", "OrionTV_v3.apk", "other.txt"), remaining)
            // 只有 2 个时不删。
            downloader.cleanOldApkFiles(dir)
            assertEquals(setOf("OrionTV_v4.apk", "OrionTV_v3.apk", "other.txt"), dir.listFiles()?.map { it.name }?.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `format size renders mb and kb`() {
        val downloader = UpdateDownloader(OkHttpClient())
        assertEquals("18.0MB", downloader.formatSize(18L * 1024 * 1024))
        assertEquals("512.0KB", downloader.formatSize(512L * 1024))
        assertEquals("42B", downloader.formatSize(42L))
    }
}
