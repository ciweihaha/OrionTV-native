package com.oriontv.navigation

import kotlinx.serialization.Serializable

/** 路由：与 v1.7.2 Expo Router 的 Stack 语义对齐。 */
@Serializable
sealed interface OrionRoute {
    @Serializable
    data object Home : OrionRoute

    @Serializable
    data object Live : OrionRoute

    @Serializable
    data class Detail(val source: String, val id: String) : OrionRoute

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
