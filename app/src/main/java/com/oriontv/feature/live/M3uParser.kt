package com.oriontv.feature.live

import kotlinx.serialization.Serializable

/**
 * M3U 解析（plan §5.3）：
 * - 逐行解析 `#EXTINF:`，取第一个不在双引号内的逗号之后为台名；
 * - 属性取 `tvg-logo`、`group-title`、`tvg-id`；
 * - 下一行非 `#` 开头且含 `://` 的为流地址；`#EXTINF` 与地址之间的
 *   `#` 行（`#EXTVLCOPT`、`#EXTGRP` 等）跳过继续找地址，遇下一个
 *   `#EXTINF` 才停；
 * - 同一 URL 只留第一次出现；`id` 即 URL 本身；
 * - 同名频道合并多线路（`urls[]`，`url` 恒等于 `urls[0]`，台名为 `Unknown` 的不参与合并），
 *   合并时同步更新 byName（否则第 3 条同名线路 `indexOf` 返回 -1 崩溃），
 *   首条 tvgId/logo/group 为空时用后条补齐。
 * `getPlayableUrl` 是恒等函数，流地址直接播放；没有任何 302 特殊处理。
 */
object M3uParser {

    @Serializable
    data class Channel(
        val id: String,
        val name: String,
        val logo: String? = null,
        val group: String? = null,
        val tvgId: String? = null,
        /** 多线路；url 恒等于 urls[0]（urls 为空时为空串，不崩溃）。 */
        val urls: List<String>,
    ) {
        val url: String get() = urls.firstOrNull().orEmpty()
    }

    fun parse(content: String): List<Channel> {
        val lines = content.lines()
        val parsed = mutableListOf<Channel>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = extractName(line)
                val attrs = extractAttrs(line)
                // 下一行非 # 开头且含 :// 的为流地址；#EXTINF 与地址之间的
                // # 行（#EXTVLCOPT、#EXTGRP 等）跳过继续找，遇下一个 #EXTINF 才停。
                var j = i + 1
                var url: String? = null
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isEmpty()) {
                        j++
                        continue
                    }
                    if (next.startsWith("#EXTINF:")) break
                    if (next.startsWith("#")) {
                        j++
                        continue
                    }
                    if (next.contains("://")) {
                        url = next
                    }
                    break
                }
                if (url != null) {
                    parsed.add(
                        Channel(
                            id = url,
                            name = name,
                            logo = attrs["tvg-logo"],
                            group = attrs["group-title"],
                            tvgId = attrs["tvg-id"],
                            urls = listOf(url),
                        ),
                    )
                    // 只有找到流地址才跳过 URL 行；没找到时下一行可能是
                    // 紧跟的 #EXTINF（上一个台缺地址），必须留给下轮处理，
                    // 否则会把下一个频道整个吞掉。
                    i = j
                }
            }
            i++
        }
        // 同一 URL 只留第一次出现
        val deduped = parsed.distinctBy { it.url }
        // 同名频道合并多线路（台名 Unknown 的不参与合并；D-P2-5：合并按名字
        // 大小写不敏感，与 EPG 的大小写不敏感匹配一致——CCTV1 与 cctv1 是同一台）。
        val merged = mutableListOf<Channel>()
        val byName = LinkedHashMap<String, Channel>()
        for (channel in deduped) {
            if (channel.name == "Unknown") {
                merged.add(channel)
                continue
            }
            val mergeKey = channel.name.lowercase()
            val existing = byName[mergeKey]
            if (existing == null) {
                byName[mergeKey] = channel
                merged.add(channel)
            } else {
                // 合并后必须同步更新 byName：否则第 3 条同名线路到来时
                // merged.indexOf(existing) 找不到旧对象返回 -1，越界崩溃。
                // 首条 tvgId/logo/group 为空时用后条补齐（只补空项，不覆盖首条）。
                val mergedChannel = existing.copy(
                    urls = existing.urls + channel.url,
                    logo = existing.logo?.takeIf { it.isNotBlank() } ?: channel.logo,
                    group = existing.group?.takeIf { it.isNotBlank() } ?: channel.group,
                    tvgId = existing.tvgId?.takeIf { it.isNotBlank() } ?: channel.tvgId,
                )
                val index = merged.indexOf(existing)
                merged[index] = mergedChannel
                byName[mergeKey] = mergedChannel
            }
        }
        return merged
    }

    /** 取第一个不在双引号内的逗号之后为台名。 */
    internal fun extractName(extinf: String): String {
        var inQuotes = false
        for (idx in extinf.indices) {
            when (extinf[idx]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) {
                    return extinf.substring(idx + 1).trim().ifEmpty { "Unknown" }
                }
            }
        }
        return "Unknown"
    }

    /** 取 key="value" 形式的属性。 */
    internal fun extractAttrs(extinf: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""([\w-]+)="([^"]*)"""")
        for (match in regex.findAll(extinf)) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }
}
