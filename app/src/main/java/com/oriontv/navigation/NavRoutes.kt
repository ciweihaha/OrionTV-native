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
    data class Detail(
        val source: String,
        val id: String,
        val title: String = "",
        /**
         * 第 7 轮（Jewel）：首页来源的豆瓣评分透传（HomeRowItem.rate，
         * 如 "8.5"）——只改导航参数 + 详情展示，不新增网络调用、不改数据层
         * 模型。首页 agg/豆瓣卡片自带评分才传；搜索/收藏/深链入口没有评分，
         * 保持 null、不显示（搜索来源仍堵，见审计延期项）。
         * 带默认值的可空字段：旧版保存的回栈（无 rate 参数）解析时落默认 null。
         */
        val rate: String? = null,
    ) : OrionRoute

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
