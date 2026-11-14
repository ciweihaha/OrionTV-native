package com.oriontv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.oriontv.feature.detail.DetailScreen
import com.oriontv.feature.favorites.FavoritesScreen
import com.oriontv.feature.home.HomeScreen
import com.oriontv.feature.live.LiveScreen
import com.oriontv.feature.play.PlayScreen
import com.oriontv.feature.search.SearchScreen
import com.oriontv.feature.settings.SettingsScreen

/**
 * 全站 NavHost（headerShown: false，TV 无底栏/侧栏，靠 Stack + 各页自画顶栏）。
 * 注意：返回后焦点/滚动位置需各页显式恢复（plan §3.3 第 3 条），不能指望框架自保。
 */
@Composable
fun OrionNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = OrionRoute.Home,
        modifier = modifier,
    ) {
        composable<OrionRoute.Home> {
            HomeScreen(onNavigate = { navController.navigate(it) })
        }
        composable<OrionRoute.Live> {
            LiveScreen(onNavigate = { navController.navigate(it) })
        }
        composable<OrionRoute.Detail> { backStackEntry ->
            val route = backStackEntry.toRoute<OrionRoute.Detail>()
            DetailScreen(
                source = route.source,
                id = route.id,
                onNavigate = { navController.navigate(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<OrionRoute.Play> { backStackEntry ->
            val route = backStackEntry.toRoute<OrionRoute.Play>()
            PlayScreen(
                source = route.source,
                id = route.id,
                title = route.title,
                episodeIndex = route.episodeIndex,
                positionMs = route.positionMs,
                onBack = { navController.popBackStack() },
            )
        }
        composable<OrionRoute.Search> {
            SearchScreen(onNavigate = { navController.navigate(it) })
        }
        composable<OrionRoute.Favorites> {
            FavoritesScreen(onNavigate = { navController.navigate(it) })
        }
        composable<OrionRoute.Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
