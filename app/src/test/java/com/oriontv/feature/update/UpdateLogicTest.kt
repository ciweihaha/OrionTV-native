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
    fun `resolve skip version prefers memory over stored`() {
        // Jewel 第 8 轮 P2-1：skipVersion() 同步置位的内存态优先——写盘尚未
        // 落地的窗口内，完成分支也按“已跳过”走；内存态为空时回落到 DataStore 值。
        assertEquals("2.0.10", resolveSkipVersion("2.0.10", ""))
        assertEquals("2.0.10", resolveSkipVersion("2.0.10", "2.0.9"))
        assertEquals("2.0.9", resolveSkipVersion(null, "2.0.9"))
        assertEquals("", resolveSkipVersion(null, ""))
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
    fun `clean old apks breaks mtime ties by version not by filename`() {
        // B8-P2-10：lastModified 打平时旧实现按文件名倒序决胜，字典序下
        // v2.0.10 < v2.0.9，刚下好的包会被当旧包删掉；现在按版本号倒序。
        val downloader = UpdateDownloader(OkHttpClient())
        val dir = Files.createTempDirectory("oriontv-apk-tie-test").toFile()
        try {
            val now = System.currentTimeMillis()
            listOf("OrionTV_v2.0.8.apk", "OrionTV_v2.0.9.apk", "OrionTV_v2.0.10.apk", "OrionTV_v2.0.11.apk")
                .forEach { name ->
                    File(dir, name).apply {
                        writeText("x")
                        setLastModified(now)
                    }
                }
            downloader.cleanOldApkFiles(dir)
            val remaining = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertEquals(setOf("OrionTV_v2.0.11.apk", "OrionTV_v2.0.10.apk"), remaining)
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
    fun `silent update check failure writes no error`() {
        // B12-P2-7：静默检查失败不写 error——设置页只要 error != null 就在
        // 底部渲染红字，且没有任何路径清 error，静默失败会让红字长期挂着；
        // 手动检查失败才展示。
        assertEquals(null, updateCheckErrorOrNull(silent = true, "检查更新超时，请检查网络后重试"))
        assertEquals(
            "检查更新超时，请检查网络后重试",
            updateCheckErrorOrNull(silent = false, "检查更新超时，请检查网络后重试"),
        )
    }

    @Test
    fun `format size renders mb and kb`() {
        val downloader = UpdateDownloader(OkHttpClient())
        assertEquals("18.0MB", downloader.formatSize(18L * 1024 * 1024))
        assertEquals("512.0KB", downloader.formatSize(512L * 1024))
        assertEquals("42B", downloader.formatSize(42L))
    }

    @Test
    fun `keeps downloaded file only when versions match`() {
        // 第 6 轮 P2-2：重查时同版保留已下载包（文件名按版本固定，同版即同一文件），
        // 版本不同或从未下载过时清掉重下。
        assertTrue(shouldKeepDownloadedFile("2.0.1", "2.0.1"))
        assertFalse(shouldKeepDownloadedFile("2.0.0", "2.0.1"))
        assertFalse(shouldKeepDownloadedFile(null, "2.0.1"))
    }

    @Test
    fun `downloaded flag requires downloaded version to match latest`() {
        // B40-P2-3：下载中途重查出新版，旧版跑完也不能算“已下载”——卡片不
        // 能把旧包当新版叫用户“立即安装”；同版、路径在时才算已下载。
        assertTrue(isDownloadedForLatestVersion("/x/OrionTV_v2.0.11.apk", "2.0.11", "2.0.11"))
        assertFalse(isDownloadedForLatestVersion("/x/OrionTV_v2.0.10.apk", "2.0.10", "2.0.11"))
        assertFalse(isDownloadedForLatestVersion(null, "2.0.11", "2.0.11"))
        assertFalse(isDownloadedForLatestVersion("/x/OrionTV_v2.0.11.apk", null, "2.0.11"))
        assertFalse(isDownloadedForLatestVersion("/x/OrionTV_v2.0.11.apk", "2.0.11", null))
    }

    @Test
    fun `download complete toast suppressed for skipped version`() {
        // 第 7 轮 P2-4：已跳过版本的完成回调不再弹 Toast（跳过与完成竞态时
        // 完成分支凭此复核）；未跳过的正常弹。
        assertFalse(shouldNotifyDownloadComplete("2.0.10", "2.0.10"))
        assertTrue(shouldNotifyDownloadComplete("2.0.10", "2.0.9"))
        assertTrue(shouldNotifyDownloadComplete("2.0.10", ""))
        assertTrue(shouldNotifyDownloadComplete("2.0.10", null))
    }

    @Test
    fun `stale download completion is rejected when latest changed`() {
        // B41-P2-1：下载中途重查出新版，旧版跑完不落盘（不发完成 Toast、不写
        // 路径/版本、不重弹卡片）；服务端版本回落到当前版、重查已报“已是最新
        // 版本”时同样拒绝；快照仍是当前最新版且仍需更新、未跳过时才接受。
        assertTrue(shouldAcceptDownloadComplete("2.0.11", "2.0.11", "2.0.10", null))
        assertTrue(shouldAcceptDownloadComplete("2.0.11", "2.0.11", "2.0.10", "2.0.9"))
        // 重查出新版：快照已不是当前最新版。
        assertFalse(shouldAcceptDownloadComplete("2.0.10", "2.0.11", "2.0.10", null))
        // 服务端版本回落到当前版：已不需更新。
        assertFalse(shouldAcceptDownloadComplete("2.0.10", "2.0.10", "2.0.10", null))
        // 当前最新版未知：不接受。
        assertFalse(shouldAcceptDownloadComplete("2.0.11", null, "2.0.10", null))
        // 已跳过该版：不接受。
        assertFalse(shouldAcceptDownloadComplete("2.0.11", "2.0.11", "2.0.10", "2.0.11"))
    }

    @Test
    fun `restored download validates file existence and versioned name`() {
        // 第 7 轮 P2-5：启动恢复校验——文件仍在且文件名与版本匹配才复用。
        val dir = Files.createTempDirectory("oriontv-restore-test").toFile()
        try {
            val apk = File(dir, "OrionTV_v2.0.10.apk").apply { writeText("x") }
            assertTrue(validateRestoredDownload(apk.absolutePath, "2.0.10"))
            // 文件不在：无效，无孤儿可删。
            assertFalse(validateRestoredDownload(File(dir, "OrionTV_v2.0.11.apk").absolutePath, "2.0.11"))
            assertFalse(isOrphanDownloadFile(File(dir, "OrionTV_v2.0.11.apk").absolutePath, "2.0.11"))
            // 版本对不上文件名：无效，且是孤儿包（恢复时删）。
            assertFalse(validateRestoredDownload(apk.absolutePath, "2.0.9"))
            assertTrue(isOrphanDownloadFile(apk.absolutePath, "2.0.9"))
            // 记录缺失：无效，不是孤儿。
            assertFalse(validateRestoredDownload(null, "2.0.10"))
            assertFalse(validateRestoredDownload(apk.absolutePath, null))
            assertFalse(validateRestoredDownload("", "2.0.10"))
            assertFalse(isOrphanDownloadFile(null, "2.0.10"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
