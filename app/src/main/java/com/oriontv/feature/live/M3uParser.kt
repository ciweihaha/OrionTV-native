package com.oriontv.feature.live

/**
 * M3U 解析（plan §5.3）：
 * - 逐行解析 `#EXTINF:`，取第一个不在双引号内的逗号之后为台名；
 * - 属性取 `tvg-logo`、`group-title`、`tvg-id`；
 * - 下一行非 `#` 开头且含 `://` 的为流地址；
 * - 同一 URL 只留第一次出现；`id` 即 URL 本身；
 * - 同名频道合并多线路（`urls[]`，`url` 恒等于 `urls[0]`，台名为 `Unknown` 的不参与合并）。
 * `getPlayableUrl` 是恒等函数，流地址直接播放；没有任何 302 特殊处理。
 */
object M3uParser {

    data class Channel(
        val id: String,
        val name: String,
        val logo: String? = null,
        val group: String? = null,
        val tvgId: String? = null,
        /** 多线路；url 恒等于 urls[0]。 */
        val urls: List<String>,
    ) {
        val url: String get() = urls.first()
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
                // 下一行非 # 开头且含 :// 的为流地址
                var j = i + 1
                var url: String? = null
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isEmpty()) {
                        j++
                        continue
                    }
                    if (!next.startsWith("#") && next.contains("://")) {
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
                }
                i = j
            }
            i++
        }
        // 同一 URL 只留第一次出现
        val deduped = parsed.distinctBy { it.url }
        // 同名频道合并多线路（台名 Unknown 的不参与合并）
        val merged = mutableListOf<Channel>()
        val byName = LinkedHashMap<String, Channel>()
        for (channel in deduped) {
            if (channel.name == "Unknown") {
                merged.add(channel)
                continue
            }
            val existing = byName[channel.name]
            if (existing == null) {
                byName[channel.name] = channel
                merged.add(channel)
            } else {
                val index = merged.indexOf(existing)
                merged[index] = existing.copy(urls = existing.urls + channel.url)
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
