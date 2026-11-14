package com.oriontv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1（第 5 轮）：导航轨高亮映射的纯函数测试。
 * 注意：hasRoute 的目的地匹配本身依赖 Navigation 运行时，JVM 单测覆盖不到，
 * 这里只测“currentRoute 能正确解析的前提下，映射规则正确”——即第 4 轮
 * 变通里那个恒 null 下永不可达的 when 块，现在是可达逻辑。
 */
class RouteResolutionTest {

    @Test
    fun `top level routes highlight themselves`() {
        assertEquals(OrionRoute.Home, railRouteFor(OrionRoute.Home))
        assertEquals(OrionRoute.Live, railRouteFor(OrionRoute.Live))
        assertEquals(OrionRoute.Search, railRouteFor(OrionRoute.Search))
        assertEquals(OrionRoute.Favorites, railRouteFor(OrionRoute.Favorites))
        assertEquals(OrionRoute.Settings, railRouteFor(OrionRoute.Settings))
    }

    @Test
    fun `detail and play highlight home`() {
        assertEquals(
            OrionRoute.Home,
            railRouteFor(OrionRoute.Detail(source = "agg", id = "1", title = "片名")),
        )
        assertEquals(
            OrionRoute.Home,
            railRouteFor(
                OrionRoute.Play(
                    source = "agg",
                    id = "1",
                    title = "片名",
                    episodeIndex = 2,
                    positionMs = 1000L,
                ),
            ),
        )
    }

    @Test
    fun `null stays null`() {
        assertNull(railRouteFor(null))
    }

    @Test
    fun `top level nav options pop up to home with state kept`() {
        // 第 7 轮 P2-1：导航轨与收藏页空态“去首页”共用的顶层选项——
        // launchSingleTop 不重复压栈、restoreState 恢复首页状态、
        // popUpTo(Home) 存状态、不把 Home 也弹掉（inclusive 默认 false）。
        val options = topLevelNavOptions()
        assertTrue(options.shouldLaunchSingleTop())
        assertTrue(options.shouldRestoreState())
        assertTrue(options.shouldPopUpToSaveState())
        assertFalse(options.isPopUpToInclusive())
    }

    @Test
    fun `detail route carries home rating only when given`() {
        // 第 7 轮豆瓣透传：首页来源的评分经导航参数带进详情；其他入口
        // 不传时默认 null，不显示评分。
        assertEquals(
            "8.5",
            OrionRoute.Detail(source = "agg", id = "1", title = "片名", rate = "8.5").rate,
        )
        assertNull(OrionRoute.Detail(source = "agg", id = "1", title = "片名").rate)
    }
}
