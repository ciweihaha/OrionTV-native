package com.oriontv.feature.live

import com.oriontv.core.util.TimeUtils
import kotlinx.serialization.Serializable
import java.io.StringReader
import com.oriontv.core.util.AppLog
import javax.xml.XMLConstants
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

    /**
     * 内部实体声明上限（D-P1-3）：真实 EPG 的 DOCTYPE 是外部
     * `<!DOCTYPE tv SYSTEM "xmltv.dtd">`，不含内部 `<!ENTITY`；
     * 超过上限即按投毒处理、拒绝解析（billion laughs 式膨胀的第一道关）。
     * 第二道是 FEATURE_SECURE_PROCESSING（解析器自带膨胀上限兜底）。
     */
    internal const val MAX_ENTITY_DECLARATIONS = 5

    /**
     * 第 9 轮 D9-P2-4：解析出的节目数上限——与 M3uParser.MAX_CHANNELS=20_000
     * 对称（M3U 第 7 轮已堵同款口，EPG 漏了）。只有 3MB 体上限时，按当前
     * 密度（413KB ≈ 2,569 条）3MB 约 1.8 万条，未超 2 万；但标题短、台多
     * 的源可塞数万条 programme，全进内存与缓存编解码；超限截断并记日志，
     * 不静默丢数据。
     */
    const val MAX_PROGRAMMES = 20_000

    /** EPG 安全拒绝：防护不可用或疑似实体膨胀攻击时抛，调用方按“拉取失败”回落旧缓存。 */
    class EpgSecurityException(message: String) : Exception(message)

    /** 预扫描：内部 `<!ENTITY` 声明超过上限直接拒绝，不进解析器。 */
    internal fun checkEntityDeclarations(xml: String) {
        var count = 0
        var idx = xml.indexOf("<!ENTITY")
        while (idx >= 0) {
            count++
            if (count > MAX_ENTITY_DECLARATIONS) {
                throw EpgSecurityException(
                    "EPG 含 $count 条内部实体声明（上限 $MAX_ENTITY_DECLARATIONS），" +
                        "疑似实体膨胀攻击，拒绝解析",
                )
            }
            idx = xml.indexOf("<!ENTITY", idx + 1)
        }
    }

    @Serializable
    data class Programme(
        val channelId: String,
        val title: String,
        val startAt: Long,
        val endAt: Long,
    )

    @Serializable
    data class EpgData(
        /** channel id → 节目列表（按开始时间排序）。 */
        val programmesByChannel: Map<String, List<Programme>>,
        /** channel id → 台名。 */
        val channelNames: Map<String, String>,
    )

    fun parse(xml: String): EpgData {
        checkEntityDeclarations(xml)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isXIncludeAware = false
        // 防 XXE：真实节目单以 `<!DOCTYPE tv SYSTEM "xmltv.dtd">` 开头，
        // disallow-doctype-decl 会直接拒掉整份 EPG（P0-1），故不禁用 DOCTYPE；
        // 改为禁止拉取外部 DTD/Schema（JAXP 属性名用字符串常量——Android 的
        // javax.xml.XMLConstants 没有 ACCESS_EXTERNAL_DTD/SCHEMA 这两个字段），
        // 再关外部实体与外部 DTD 加载。
        //
        // D-P1-3：FEATURE_SECURE_PROCESSING 是必选项（JAXP 标准特性），
        // 设不上说明本机解析器不可信，抛 EpgSecurityException 拒绝解析，
        // 不再包在 runCatching 里只记一条日志就继续裸奔；
        // Oracle 的 entityExpansionLimit 等属性名 Android 实现不支持，
        // 不硬编进来，靠 FSP 自带膨胀上限 + 上面的声明数预扫描兜底。
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        } catch (e: Exception) {
            throw EpgSecurityException(
                "EPG 解析器不支持 FEATURE_SECURE_PROCESSING，拒绝解析：${e.message}",
            )
        }
        val features = linkedMapOf<String, Boolean>()
        features["ACCESS_EXTERNAL_DTD"] = runCatching {
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            true
        }.getOrDefault(false)
        features["ACCESS_EXTERNAL_SCHEMA"] = runCatching {
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            true
        }.getOrDefault(false)
        features["external-general-entities"] = runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            true
        }.getOrDefault(false)
        features["external-parameter-entities"] = runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            true
        }.getOrDefault(false)
        features["load-external-dtd"] = runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            true
        }.getOrDefault(false)
        // 逐项失败记 warning，防护失效时线上能排查（真机行为仍待用户验证）。
        features.filterValues { !it }.keys.forEach { name ->
            AppLog.w("EpgParser", "EPG 解析安全特性设置失败：$name（本机解析器不支持）")
        }
        AppLog.i("EpgParser", "EPG 解析安全特性：$features（ACCESS_EXTERNAL_DTD=\"\" 拦外部 DTD 拉取、不拒 DOCTYPE）")
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
        var totalProgrammes = 0
        val programmeNodes = doc.getElementsByTagName("programme")
        for (i in 0 until programmeNodes.length) {
            // D9-P2-4：节目数上限——超限截断并记日志（与 M3U 的 MAX_CHANNELS 同口径）。
            if (totalProgrammes >= MAX_PROGRAMMES) {
                AppLog.w(
                    "EpgParser",
                    "EPG 节目数 ${programmeNodes.length} 超上限 $MAX_PROGRAMMES，只保留前 $MAX_PROGRAMMES 条",
                )
                break
            }
            val el = programmeNodes.item(i) as? Element ?: continue
            val channel = el.getAttribute("channel").trim()
            val start = TimeUtils.parseXmltvTime(el.getAttribute("start")) ?: continue
            val stop = TimeUtils.parseXmltvTime(el.getAttribute("stop")) ?: continue
            if (stop <= start) continue // endAt <= startAt 的节目丢弃
            val titleNodes = el.getElementsByTagName("title")
            val title = if (titleNodes.length > 0) titleNodes.item(0).textContent.trim() else ""
            programmes.getOrPut(channel) { mutableListOf() }
                .add(Programme(channelId = channel, title = title, startAt = start, endAt = stop))
            totalProgrammes++
        }
        val sorted = programmes.mapValues { (_, list) -> list.sortedBy { it.startAt } }
        return EpgData(programmesByChannel = sorted, channelNames = channelNames)
    }

    /** 归一化名字：去空白/连字符/下划线 + 小写。 */
    fun normalizeName(name: String): String =
        name.replace(Regex("[\\s\\-_]+"), "").lowercase()

    /**
     * 取某台的节目列表：tvg-id 精确（大小写不敏感；M3U 写 CCTV1、EPG 写 cctv1 时
     * 之前直接对不上）→ 归一化名字匹配。三处查找共用同一逻辑（P2-14）。
     */
    private fun resolveProgrammes(data: EpgData, tvgId: String?, channelName: String): List<Programme>? {
        if (tvgId != null) {
            data.programmesByChannel[tvgId]?.let { return it }
            data.programmesByChannel.entries
                .firstOrNull { it.key.equals(tvgId, ignoreCase = true) }?.value
                ?.let { return it }
        }
        return data.programmesByChannel.entries
            .firstOrNull { (channelId, _) ->
                val name = data.channelNames[channelId]
                name != null && normalizeName(name) == normalizeName(channelName)
            }?.value
    }

    /** 正在播的节目（startAt <= now < endAt），先按 tvg-id 精确，对不上按归一化名字匹配。 */
    fun nowPlaying(data: EpgData, tvgId: String?, channelName: String, nowMs: Long): Programme? {
        val list = resolveProgrammes(data, tvgId, channelName) ?: return null
        return list.firstOrNull { it.startAt <= nowMs && nowMs < it.endAt }
    }

    /** 稍后播的节目（下一档）。 */
    fun nextProgramme(data: EpgData, tvgId: String?, channelName: String, nowMs: Long): Programme? {
        val list = resolveProgrammes(data, tvgId, channelName) ?: return null
        return list.firstOrNull { it.startAt > nowMs }
    }

    /** 某台全天节目单（EPG 弹窗用，按开始时间排序；先 tvg-id 精确，对不上按归一化名字匹配）。 */
    fun programmesFor(data: EpgData, tvgId: String?, channelName: String): List<Programme> {
        return resolveProgrammes(data, tvgId, channelName).orEmpty()
    }

    /**
     * 第 9 轮 D9-P1-1：数据里是否还有未播完的节目（任一 endAt > now）——
     * “新鲜数据”的判据，供 [isCacheFresh] 与 EpgRepository 的落盘/失败
     * 判定共用。实测 epg.xml 仍只覆盖昨天：拉回的过期文件解析成功、节目
     * 非空，旧实现只判非空就当成功落盘、全天无节目单且无任何失败信号；
     * 现在没有任何 endAt > now 的节目时按失败处理，不落盘、置 loadFailed。
     */
    fun hasFutureProgramme(data: EpgData, nowMs: Long): Boolean =
        data.programmesByChannel.values.any { programmes ->
            programmes.any { it.endAt > nowMs }
        }

    /**
     * EPG 缓存新鲜判据（plan §4.3 / 天光 v2.2.20 教训）：
     * 不能只看文件日期，要看缓存里是否还有正在播的节目——没有就强制重拉，
     * 否则凌晨拉到昨天数据会整天无节目。
     */
    fun isCacheFresh(data: EpgData, cachedAtMs: Long, nowMs: Long, ttlMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        if (nowMs - cachedAtMs > ttlMs) return false
        return hasFutureProgramme(data, nowMs)
    }
}
