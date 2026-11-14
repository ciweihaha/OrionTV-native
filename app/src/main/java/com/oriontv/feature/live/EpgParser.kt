package com.oriontv.feature.live

import com.oriontv.core.util.TimeUtils
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * EPG 解析（plan §5.3）：XMLTV，地址硬编码 https://ysp.ciweihaha.win/epg.xml，
 * 与 live.m3u 的 tvg-id 是同一套 id。
 * - `<channel id>` + `display-name`、`<programme channel start stop>` + `<title>`，
 *   支持 CDATA 与 XML 实体；
 * - 时间主格式 `yyyyMMddHHmmss +0800`，有无时区/冒号时区兜底，非法值拦下，
 *   `endAt <= startAt` 的节目丢弃；
 * - 匹配先按 `tvg-id` 精确，对不上按归一化名字（去空白/连字符/下划线 + 小写）匹配。
 *
 * 用 javax.xml 解析（纯 JVM），便于本地单测；不要用 android.util.Xml。
 */
object EpgParser {

    data class Programme(
        val channelId: String,
        val title: String,
        val startAt: Long,
        val endAt: Long,
    )

    data class EpgData(
        /** channel id → 节目列表（按开始时间排序）。 */
        val programmesByChannel: Map<String, List<Programme>>,
        /** channel id → 台名。 */
        val channelNames: Map<String, String>,
    )

    fun parse(xml: String): EpgData {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // 防 XXE
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xml)))

        val channelNames = mutableMapOf<String, String>()
        val channels = doc.getElementsByTagName("channel")
        for (i in 0 until channels.length) {
            val el = channels.item(i) as? Element ?: continue
            val id = el.getAttribute("id").trim()
            if (id.isEmpty()) continue
            val nameNodes = el.getElementsByTagName("display-name")
            val name = if (nameNodes.length > 0) nameNodes.item(0).textContent.trim() else id
            channelNames[id] = name
        }

        val programmes = mutableMapOf<String, MutableList<Programme>>()
        val programmeNodes = doc.getElementsByTagName("programme")
        for (i in 0 until programmeNodes.length) {
            val el = programmeNodes.item(i) as? Element ?: continue
            val channel = el.getAttribute("channel").trim()
            val start = TimeUtils.parseXmltvTime(el.getAttribute("start")) ?: continue
            val stop = TimeUtils.parseXmltvTime(el.getAttribute("stop")) ?: continue
            if (stop <= start) continue // endAt <= startAt 的节目丢弃
            val titleNodes = el.getElementsByTagName("title")
            val title = if (titleNodes.length > 0) titleNodes.item(0).textContent.trim() else ""
            programmes.getOrPut(channel) { mutableListOf() }
                .add(Programme(channelId = channel, title = title, startAt = start, endAt = stop))
        }
        val sorted = programmes.mapValues { (_, list) -> list.sortedBy { it.startAt } }
        return EpgData(programmesByChannel = sorted, channelNames = channelNames)
    }

    /** 归一化名字：去空白/连字符/下划线 + 小写。 */
    fun normalizeName(name: String): String =
        name.replace(Regex("[\\s\\-_]+"), "").lowercase()

    /** 正在播的节目（startAt <= now < endAt），先按 tvg-id 精确，对不上按归一化名字匹配。 */
    fun nowPlaying(data: EpgData, tvgId: String?, channelName: String, nowMs: Long): Programme? {
        val byId = tvgId?.let { data.programmesByChannel[it] }
        val list = byId ?: data.programmesByChannel.entries
            .firstOrNull { (channelId, _) ->
                val name = data.channelNames[channelId]
                name != null && normalizeName(name) == normalizeName(channelName)
            }?.value
            ?: return null
        return list.firstOrNull { it.startAt <= nowMs && nowMs < it.endAt }
    }

    /** 稍后播的节目（下一档）。 */
    fun nextProgramme(data: EpgData, tvgId: String?, channelName: String, nowMs: Long): Programme? {
        val byId = tvgId?.let { data.programmesByChannel[it] }
        val list = byId ?: data.programmesByChannel.entries
            .firstOrNull { (channelId, _) ->
                val name = data.channelNames[channelId]
                name != null && normalizeName(name) == normalizeName(channelName)
            }?.value
            ?: return null
        return list.firstOrNull { it.startAt > nowMs }
    }

    /**
     * EPG 缓存新鲜判据（plan §4.3 / 天光 v2.2.20 教训）：
     * 不能只看文件日期，要看缓存里是否还有正在播的节目——没有就强制重拉，
     * 否则凌晨拉到昨天数据会整天无节目。
     */
    fun isCacheFresh(data: EpgData, cachedAtMs: Long, nowMs: Long, ttlMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        if (nowMs - cachedAtMs > ttlMs) return false
        return data.programmesByChannel.values.any { programmes ->
            programmes.any { it.endAt > nowMs }
        }
    }
}
