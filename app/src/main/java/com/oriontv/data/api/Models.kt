package com.oriontv.data.api

import kotlinx.serialization.Serializable

/**
 * 数据模型，照抄 plan §5（字段名与服务端契约一字不差）。
 */
@Serializable
data class AggVideoItem(
    val vod_id: String,
    val vod_name: String,
    val vod_pic: String,
    val vod_year: String? = null,
    val vod_area: String? = null,
    val vod_lang: String? = null,
    val vod_class: String? = null,
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
    val id: String,
    val title: String,
    val poster: String,
    val episodes: List<String> = emptyList(),
    val source: String,
    val source_name: String,
    val class_: String? = null,
    val year: String? = null,
    val desc: String? = null,
    val type_name: String? = null,
)

@Serializable
data class Favorite(
    val cover: String,
    val title: String,
    val source_name: String,
    val total_episodes: Int,
    val search_title: String,
    val year: String? = null,
    val save_time: Long? = null,
)

@Serializable
data class PlayRecord(
    val title: String,
    val source_name: String,
    val cover: String,
    val index: Int,
    val total_episodes: Int,
    /** 已播秒数。 */
    val play_time: Long,
    /** 总时长秒数。 */
    val total_time: Long,
    val save_time: Long,
    val year: String? = null,
)

@Serializable
data class ServerConfig(
    val SiteName: String? = null,
    val StorageType: String? = null,
)

@Serializable
data class VersionResponse(
    val version: String,
)
