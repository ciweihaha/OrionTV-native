package com.oriontv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
