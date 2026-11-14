package com.oriontv.feature.update

import com.oriontv.data.api.AggInvalidResponseException
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
    fun `strip version prefix removes leading v`() {
        assertEquals("2.0.1", stripVersionPrefix("v2.0.1"))
        assertEquals("2.0.1", stripVersionPrefix("V2.0.1"))
        assertEquals("2.0.1", stripVersionPrefix("2.0.1"))
        assertEquals("2.0.1", stripVersionPrefix("  v2.0.1 "))
        assertEquals("", stripVersionPrefix("v"))
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
    fun `clean old apks keeps latest two by modification time`() {
        val downloader = UpdateDownloader(OkHttpClient())
        val dir = Files.createTempDirectory("oriontv-apk-test").toFile()
        try {
            // 第 5 轮 CI 跟进：连续创建的文件在快机器上 lastModified 会打平
            //（毫秒精度），必须显式摆 mtime，否则排序结果取决于 listFiles()
            // 的未定义顺序、CI 飘红。
            val now = System.currentTimeMillis()
            listOf("OrionTV_v1.apk" to 30_000L, "OrionTV_v2.apk" to 20_000L, "OrionTV_v3.apk" to 10_000L, "OrionTV_v4.apk" to 0L)
                .forEach { (name, ageMs) ->
                    File(dir, name).apply {
                        writeText("x")
                        setLastModified(now - ageMs)
                    }
                }
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
    fun `clean old apks keeps freshly downloaded v2_0_10 over older v2_0_9`() {
        // B5-P1-4：按文件名排序时 "OrionTV_v2.0.10.apk" < "OrionTV_v2.0.9.apk"
        //（字典序 "1" < "9"），刚下好的 v2.0.10 会被当旧包删掉；
        // 改按最后修改时间倒序后，最新下好的包一定保留。
        val downloader = UpdateDownloader(OkHttpClient())
        val dir = Files.createTempDirectory("oriontv-apk-version-test").toFile()
        try {
            val old = File(dir, "OrionTV_v2.0.9.apk").apply { writeText("x") }
            val mid = File(dir, "OrionTV_v2.0.8.apk").apply { writeText("x") }
            val fresh = File(dir, "OrionTV_v2.0.10.apk").apply { writeText("x") }
            val now = System.currentTimeMillis()
            mid.setLastModified(now - 20_000L)
            old.setLastModified(now - 10_000L)
            fresh.setLastModified(now)
            downloader.cleanOldApkFiles(dir)
            val remaining = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertEquals(setOf("OrionTV_v2.0.10.apk", "OrionTV_v2.0.9.apk"), remaining)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `version check error distinguishes bad version format`() {
        // P2-4（第 5 轮）：带 v 前缀的版本号在 AggApi 层抛错，壳侧文案与网络
        // 失败区分开；普通异常仍走原来的“检查网络”文案。
        assertEquals(
            "检查更新失败：服务端版本号格式异常，请稍后重试",
            versionCheckError(AggInvalidResponseException("版本号格式非法：v2.0.1")),
        )
        assertEquals(
            "检查更新失败：网络错误，请检查网络后重试",
            versionCheckError(java.io.IOException("网络错误")),
        )
        assertEquals(
            "检查更新失败：agg 返回 JSON 损坏，请检查网络后重试",
            versionCheckError(AggInvalidResponseException("agg 返回 JSON 损坏")),
        )
    }

    @Test
    fun `format size renders mb and kb`() {
        val downloader = UpdateDownloader(OkHttpClient())
        assertEquals("18.0MB", downloader.formatSize(18L * 1024 * 1024))
        assertEquals("512.0KB", downloader.formatSize(512L * 1024))
        assertEquals("42B", downloader.formatSize(42L))
    }
}
