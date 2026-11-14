package com.oriontv.navigation

import kotlinx.serialization.Serializable

/** 路由（ui-design.md §1：5 个顶级页在导航轨，详情/播放为推送页）。 */
@Serializable
sealed interface OrionRoute {
    @Serializable
    data object Home : OrionRoute

    @Serializable
    data object Live : OrionRoute

    @Serializable
    data class Detail(val source: String, val id: String, val title: String = "") : OrionRoute

    @Serializable
    data class Play(
        val source: String,
        val id: String,
        val title: String,
        val episodeIndex: Int = 0,
        val positionMs: Long = 0L,
    ) : OrionRoute

    @Serializable
    data object Search : OrionRoute

    @Serializable
    data object Favorites : OrionRoute

    @Serializable
    data object Settings : OrionRoute
}
