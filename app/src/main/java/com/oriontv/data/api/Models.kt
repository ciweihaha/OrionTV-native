package com.oriontv.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 数据模型，照抄 plan §5（字段名与服务端契约一字不差）。
 *
 * 宽容说明：服务端个别字符串字段偶尔以数字下发（如搜索结果 id、vod_year、
 * vod_douban_score），RN 的 JSON.parse 不区分，Kotlin 严格类型会直接解码失败。
 * 因此这些字段用 [LenientStringSerializer]（字符串或数字都收，统一成字符串）。
 */

/** 接受字符串或数字、统一解成字符串的序列化器（数字 7 与 "7" 等价）。 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonPrimitive) {
            if (element.isString) return element.content
            element.longOrNull?.let { return it.toString() }
            element.doubleOrNull?.let { return it.toString() }
            element.booleanOrNull?.let { return it.toString() }
        }
        return null
    }
}

@Serializable
data class AggVideoItem(
    @Serializable(with = LenientStringSerializer::class)
    val vod_id: String? = null,
    /** 展示字段给默认值：服务端漏字段时整条不至于解码失败（P1-8）。 */
    val vod_name: String = "",
    val vod_pic: String = "",
    @Serializable(with = LenientStringSerializer::class)
    val vod_year: String? = null,
    val vod_area: String? = null,
    val vod_lang: String? = null,
    val vod_class: String? = null,
    @Serializable(with = LenientStringSerializer::class)
    val vod_douban_score: String? = null,
    val vod_remarks: String? = null,
    val type_name: String? = null,
    /** 客户端固定写 "agg"。 */
    val source: String = "agg",
)

@Serializable
data class AggListResponse(
    val code: Int,
    val msg: String? = null,
    val page: Int? = null,
    val pagecount: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val list: List<AggVideoItem>? = null,
)

@Serializable
data class SearchResult(
    @Serializable(with = LenientStringSerializer::class)
    val id: String? = null,
    val title: String = "",
    val poster: String = "",
    val episodes: List<String> = emptyList(),
    val source: String = "",
    val source_name: String = "",
    @SerialName("class")
    val class_: String? = null,
    @Serializable(with = LenientStringSerializer::class)
    val year: String? = null,
    val desc: String? = null,
    val type_name: String? = null,
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>? = null,
)

@Serializable
data class DoubanItem(
    val title: String = "",
    val poster: String = "",
    val rate: String? = null,
)

@Serializable
data class DoubanResponse(
    val code: Int,
    val message: String? = null,
    val list: List<DoubanItem>? = null,
)

@Serializable
data class ApiSite(
    val key: String = "",
    val api: String = "",
    val name: String = "",
    val detail: String? = null,
)

@Serializable
data class Favorite(
    val cover: String = "",
    val title: String = "",
    val source_name: String = "",
    val total_episodes: Int = 0,
    val search_title: String = "",
    val year: String? = null,
    val save_time: Long? = null,
)

@Serializable
data class PlayRecord(
    val title: String = "",
    val source_name: String = "",
    val cover: String = "",
    val index: Int = 0,
    val total_episodes: Int = 0,
    /** 已播秒数。 */
    val play_time: Long = 0,
    /** 总时长秒数。 */
    val total_time: Long = 0,
    val save_time: Long = 0,
    val year: String? = null,
)

/**
 * 播放器个性化设置（plan §5.4 mytv_player_settings 的单条值）：
 * 只存自定义过的条目；introEndTime/outroStartTime 单位毫秒，playbackRate 如 1.25。
 */
@Serializable
data class PlayerSettings(
    val introEndTime: Long? = null,
    val outroStartTime: Long? = null,
    val playbackRate: Float? = null,
)

@Serializable
data class ServerConfig(
    val SiteName: String? = null,
    val StorageType: String? = null,
)

@Serializable
data class VersionResponse(
    /**
     * 第 9 轮 D9-P2-9（D6-P2-16 闭环）：coerceInputValues 只对有默认值
     * 的字段生效，version 原来是必填标量、缺字段时整表解码失败报
     * “版本接口返回 JSON 损坏”；给默认值 ""，缺字段时由 isValidVersion
     * 报“版本号格式非法”，文案更准。
     */
    val version: String = "",
)

@Serializable
data class LoginResponse(
    val ok: Boolean = false,
)

@Serializable
data class SuccessResponse(
    val success: Boolean = false,
)
