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

/**
 * 导航轨高亮映射（纯函数，可单测）：
 * 详情/播放不匹配任何顶级项时，导航轨按来源高亮“首页”（最常见的入口）。
 *
 * 注意：这个 when 只有在 currentRoute 能正确解析时才可达。第 5 轮之前
 * currentRoute 恒 null（见 OrionNavHost 的 P1-1 说明），它是一段死代码。
 */
fun railRouteFor(currentRoute: OrionRoute?): OrionRoute? = when (currentRoute) {
    is OrionRoute.Detail, is OrionRoute.Play -> OrionRoute.Home
    else -> currentRoute
}
