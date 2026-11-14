package com.oriontv.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.oriontv.feature.auth.AuthViewModel
import com.oriontv.feature.auth.LoginModal
import com.oriontv.feature.detail.DetailScreen
import com.oriontv.feature.favorites.FavoritesScreen
import com.oriontv.feature.home.HomeScreen
import com.oriontv.feature.live.LiveScreen
import com.oriontv.feature.play.PlayScreen
import com.oriontv.feature.search.SearchScreen
import com.oriontv.feature.settings.SettingsScreen
import com.oriontv.ui.components.NavRail
import com.oriontv.ui.theme.OrionColors

/**
 * 全站布局（ui-design.md §1）：
 * 左侧 96dp 导航轨常驻 + 右侧内容区；播放页全屏视频时不显示导航轨。
 * 顶级页之间切换用 launchSingleTop，不堆栈。
 * 注意：返回后焦点/滚动位置需各页显式恢复（plan §3.3 第 3 条），不能指望框架自保。
 */
@Composable
fun OrionNavHost(
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute: OrionRoute? = backStackEntry?.let {
        runCatching { it.toRoute<OrionRoute>() }.getOrNull()
    }
    val showRail = currentRoute !is OrionRoute.Play
    val showLoginModal by authViewModel.showLoginModal.collectAsState()
    val loginError by authViewModel.loginError.collectAsState()

    // 冷启动检查登录态：有标记直接进，无标记用保存凭据静默重登，失败才弹登录框。
    LaunchedEffect(Unit) { authViewModel.checkLoginStatus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showRail) {
                NavRail(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(OrionRoute.Home) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = OrionRoute.Home,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<OrionRoute.Home> {
                    HomeScreen(onNavigate = { navController.navigate(it) })
                }
                composable<OrionRoute.Live> {
                    LiveScreen(onNavigate = { navController.navigate(it) })
                }
                composable<OrionRoute.Detail> { entry ->
                    val route = entry.toRoute<OrionRoute.Detail>()
                    DetailScreen(
                        source = route.source,
                        id = route.id,
                        title = route.title,
                        onNavigate = { navController.navigate(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<OrionRoute.Play> { entry ->
                    val route = entry.toRoute<OrionRoute.Play>()
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
        }
        // 全局登录弹窗：会话过期才重弹（ui-design.md §5.8）。
        if (showLoginModal) {
            LoginModal(
                onLogin = { username, password -> authViewModel.login(username, password) },
                onDismiss = { authViewModel.hideLogin() },
                error = loginError,
            )
        }
    }
}
