package com.oriontv.feature.update

import java.security.MessageDigest

/**
 * 更新服务骨架（plan §5.1）：下载地址客户端拼
 * `https://dl.ciweihaha.win/orionTV.{version}.apk`；
 * 下载后强制校验 `<url>.sha256`（支持 `hash  filename` 或纯 hash 两种格式），
 * 不匹配拒绝安装。下载/校验/安装流程见 [UpdateDownloader] 与 [UpdateViewModel]。
 */
object UpdateService {
    /** 冷启动静默检查间隔 12 小时（plan §5.6：须等 lastCheckTime 读回后再算）。 */
    const val SILENT_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /**
     * 下载地址：带 `?v=$version` 破缓存——R2/下载端点有 4 小时缓存，
     * 不带版本参数会拿到旧版 APK（1.7.2 教训）。
     */
    fun downloadUrl(version: String): String = "https://dl.ciweihaha.win/orionTV.$version.apk?v=$version"

    fun sha256Url(version: String): String = "https://dl.ciweihaha.win/orionTV.$version.apk.sha256?v=$version"

    /** 是否到了静默检查时间（上次检查距今 >= 12 小时；从未检查过返回 true）。 */
    fun shouldCheckNow(lastCheckTimeMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastCheckTimeMs <= 0L || nowMs - lastCheckTimeMs >= SILENT_CHECK_INTERVAL_MS

    /** 该版本是否已被用户跳过。 */
    fun isSkippedVersion(version: String, skipVersion: String): Boolean =
        skipVersion.isNotBlank() && version == skipVersion

    /** 解析 .sha256 文件内容：支持 `hash  filename` 或纯 hash 两种格式；去 BOM。 */
    fun parseSha256File(content: String): String? {
        // 有的 .sha256 带 UTF-8 BOM（\uFEFF），trim 去不掉，先去 BOM 再 trim。
        val trimmed = content.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return null
        val firstToken = trimmed.split(Regex("\\s+")).firstOrNull().orEmpty()
        return firstToken.ifEmpty { null }
    }

    /** 计算字节数组的 SHA-256（小写十六进制）。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
