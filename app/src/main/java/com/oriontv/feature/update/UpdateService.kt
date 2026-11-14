package com.oriontv.feature.update

import java.security.MessageDigest

/**
 * 更新服务骨架（plan §5.1）：下载地址客户端拼
 * `https://dl.ciweihaha.win/orionTV.{version}.apk`；
 * 下载后强制校验 `<url>.sha256`（支持 `hash  filename` 或纯 hash 两种格式），
 * 不匹配拒绝安装。下载/安装流程在 M3 实现。
 */
object UpdateService {
    fun downloadUrl(version: String): String = "https://dl.ciweihaha.win/orionTV.$version.apk"

    fun sha256Url(version: String): String = "${downloadUrl(version)}.sha256"

    /** 解析 .sha256 文件内容：支持 `hash  filename` 或纯 hash 两种格式。 */
    fun parseSha256File(content: String): String? {
        val trimmed = content.trim()
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
