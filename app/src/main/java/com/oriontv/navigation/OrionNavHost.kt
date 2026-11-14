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
import androidx.navigation.navDeepLink
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
import com.oriontv.feature.update.UpdateCard
import com.oriontv.feature.update.UpdateViewModel
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
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute: OrionRoute? = backStackEntry?.let {
        runCatching { it.toRoute<OrionRoute>() }.getOrNull()
    }
    // P2-3：详情/播放不匹配任何顶级项时，导航轨按来源高亮“首页”（最常见的入口）。
    val railRoute: OrionRoute? = when (currentRoute) {
        is OrionRoute.Detail, is OrionRoute.Play -> OrionRoute.Home
        else -> currentRoute
    }
    val showRail = currentRoute !is OrionRoute.Play
    val showLoginModal by authViewModel.showLoginModal.collectAsState()
    val loginError by authViewModel.loginError.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()

    // 冷启动检查登录态：有标记直接进，无标记用保存凭据静默重登，失败才弹登录框。
    LaunchedEffect(Unit) { authViewModel.checkLoginStatus() }
    // 冷启动 12 小时静默检查更新（plan §5.6/§6 M3）：有新版且未跳过才弹卡。
    LaunchedEffect(Unit) { updateViewModel.checkForUpdate(silent = true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showRail) {
                NavRail(
                    currentRoute = railRoute,
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
                    LiveScreen(
                        onNavigate = { navController.navigate(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
                // P2-4：深层链接（adb/语音助手/外部调用可直达详情/播放）。
                composable<OrionRoute.Detail>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = "oriontv://detail?source={source}&id={id}&title={title}"
                        },
                    ),
                ) { entry ->
                    val route = entry.toRoute<OrionRoute.Detail>()
                    DetailScreen(
                        source = route.source,
                        id = route.id,
                        title = route.title,
                        onNavigate = { navController.navigate(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<OrionRoute.Play>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = "oriontv://play?source={source}&id={id}&title={title}" +
                                "&episodeIndex={episodeIndex}&positionMs={positionMs}"
                        },
                    ),
                ) { entry ->
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
                    // P1-6：设置页用 NavHost 持有的同一个 Activity 级 UpdateViewModel
                    // 实例（冷启动检查与手动检查共用一份状态，不再弹两张重叠卡片）。
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        updateViewModel = updateViewModel,
                    )
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
        // 全局更新卡片：冷启动静默检查发现新版时弹，与设置页手动检查共用
        // NavHost 持有的同一个 Activity 级 UpdateViewModel 实例（P1-6）。
        // 关闭后焦点由各页自行恢复：冷启动卡片无触发点，设置页卡片由设置页恢复。
        if (updateState.showUpdateCard && updateState.latestVersion != null) {
            UpdateCard(
                currentVersion = updateState.currentVersion,
                latestVersion = updateState.latestVersion ?: "",
                apkSizeBytes = updateState.apkSizeBytes,
                downloading = updateState.downloading,
                downloadProgress = updateState.downloadProgress,
                downloaded = updateState.downloadedFilePath != null,
                error = updateState.error,
                onUpdate = { updateViewModel.startDownload() },
                onInstall = { updateViewModel.installUpdate() },
                onSkip = { updateViewModel.skipVersion() },
                onDismiss = { updateViewModel.dismissCard() },
            )
        }
    }
}
