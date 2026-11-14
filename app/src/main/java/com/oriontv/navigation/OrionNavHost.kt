package com.oriontv.navigation

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.hasRoute
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
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.theme.OrionColors
import kotlinx.coroutines.delay

/**
 * 全站布局（ui-design.md §1）：
 * 左侧 96dp 导航轨常驻 + 右侧内容区；播放页全屏视频时不显示导航轨。
 * 顶级页之间切换用 launchSingleTop，不堆栈。
 * 注意：返回后焦点/滚动位置需各页显式恢复（plan §3.3 第 3 条），不能指望框架自保。
 *
 * 第 4 轮 C4-P1-6：NavController 由 MainActivity 持有并传入（热启动 onNewIntent
 * 需要它处理深链；冷启动的初始 Intent 由 NavHost 自己接）。
 */
@Composable
fun OrionNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    // P1-1（第 5 轮，用户 2026-11-14 23:21 真机确认）：不要对密封父类型调
    // toRoute<OrionRoute>()——SealedClassSerializer 反序列化要求 arguments
    // 里带类型判别字段，而导航 arguments 里只有各具体子类型的字段（对 data
    // object 子类型甚至是空 Bundle），调用每次都抛异常，又被 runCatching
    // 静默吞掉，于是 currentRoute 在所有页面恒 null：播放页导航轨常驻、
    // 全站高亮失效。
    // 修法（修来源，不再加变通）：用 NavDestination.hasRoute<具体子类型>()
    // 逐个匹配（这是为此设计的 API）；Detail/Play 的参数用
    // toRoute<具体子类型>() 解（本文件 106/123 行已证明具体子类型能正常解析）。
    // 第 4 轮应用壳修复（提交 d583cd6，PR #4）加在恒 null 来源上的 railRoute
    // when 变通是死代码，本轮随来源修复一并去掉，改调纯函数 railRouteFor。
    // 深链 title 含 & 的说明（C5-P2-6）：应用内导航走类型安全路由对象
    // （navigate 传参，框架编码），不受影响；只有外部直接拼原始 URI
    // （adb/语音助手）且 title 未百分号编码时，& 会把 title 截断——调用方
    // 须把 title 写成 %26，框架侧无法从截断后的 URI 还原。
    // C5-P2-6 的另一半意图也保留：解析失败记 Log.w、不静默吞掉（下面
    // Detail/Play 两支的 onFailure），导航轨兜底高亮首页（下面 railRoute
    // 的 ?: OrionRoute.Home）。
    val currentRoute: OrionRoute? = backStackEntry?.let { entry ->
        val destination = entry.destination
        when {
            destination.hasRoute<OrionRoute.Home>() -> OrionRoute.Home
            destination.hasRoute<OrionRoute.Live>() -> OrionRoute.Live
            destination.hasRoute<OrionRoute.Search>() -> OrionRoute.Search
            destination.hasRoute<OrionRoute.Favorites>() -> OrionRoute.Favorites
            destination.hasRoute<OrionRoute.Settings>() -> OrionRoute.Settings
            destination.hasRoute<OrionRoute.Detail>() ->
                runCatching { entry.toRoute<OrionRoute.Detail>() }
                    .onFailure { e ->
                        Log.w("OrionNavHost", "详情路由解析失败，导航轨兜底高亮首页：${e.message}")
                    }.getOrNull()
            destination.hasRoute<OrionRoute.Play>() ->
                runCatching { entry.toRoute<OrionRoute.Play>() }
                    .onFailure { e ->
                        Log.w("OrionNavHost", "播放路由解析失败，导航轨兜底高亮首页：${e.message}")
                    }.getOrNull()
            else -> null
        }
    }
    // 详情/播放不匹配任何顶级项时，导航轨按来源高亮“首页”。
    // currentRoute 为 null（未知路由/解析失败）时同样兜底首页（C5-P2-6）。
    val railRoute = railRouteFor(currentRoute) ?: OrionRoute.Home
    val showRail = currentRoute !is OrionRoute.Play
    // 全局弹窗关闭后的焦点归宿见下面的 focusRestoreTick（C4-P1-2）：各页
    // 监听 tick 回自己默认锚点，不再统一拉到导航轨首项（P1-2 原方案的前提
    // “各页没有统一恢复入口”已被 tick 机制取代，见本次 rebase 说明）。
    val showLoginModal by authViewModel.showLoginModal.collectAsState()
    val loginError by authViewModel.loginError.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    // C4-P1-2：登录与更新卡片互斥——登录开着时更新卡片不渲染（登录关后恢复显示）。
    val showUpdateCard = updateState.showUpdateCard &&
        updateState.latestVersion != null && !showLoginModal
    val context = LocalContext.current

    // 冷启动检查登录态：有标记直接进，无标记用保存凭据静默重登，失败才弹登录框。
    LaunchedEffect(Unit) { authViewModel.checkLoginStatus() }
    // 冷启动 12 小时静默检查更新（plan §5.6/§6 M3）：有新版且未跳过才弹卡。
    LaunchedEffect(Unit) { updateViewModel.checkForUpdate(silent = true) }

    // C4-P1-2：弹窗关闭后焦点还给当前页默认锚点——任一弹窗从开变关时 tick+1，
    // 各页监听 tick 重新跑自己的默认焦点恢复（与首进逻辑同一条路）。
    var focusRestoreTick by remember { mutableIntStateOf(0) }
    var anyModalWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showLoginModal, showUpdateCard) {
        if (showLoginModal || showUpdateCard) {
            anyModalWasOpen = true
        } else if (anyModalWasOpen) {
            anyModalWasOpen = false
            focusRestoreTick++
        }
    }

    // C4-P1-1：首页单按返回不再直接退出（设计 §4 要求双按确认）；
    // 2 秒内再按一次才退出，中间显示“再按一次退出”提示。
    var lastHomeBackMs by remember { mutableLongStateOf(0L) }
    var showExitHint by remember { mutableStateOf(false) }
    LaunchedEffect(showExitHint) {
        if (showExitHint) {
            delay(2000)
            showExitHint = false
        }
    }
    // 返回键优先级（同一时刻最多一个生效）：登录弹窗 > 更新卡片 > 首页双按确认。
    // 注册顺序：后注册的先触发，故按优先级倒序写。
    BackHandler(enabled = currentRoute is OrionRoute.Home && !showLoginModal && !showUpdateCard) {
        val now = System.currentTimeMillis()
        if (now - lastHomeBackMs < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastHomeBackMs = now
            showExitHint = true
        }
    }
    BackHandler(enabled = showUpdateCard) {
        updateViewModel.dismissCard()
    }
    BackHandler(enabled = showLoginModal) {
        authViewModel.hideLogin()
    }

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
                        // C4-P1-5：已在当前页时不导航——popUpTo(Home) 会销毁顶级页
                        //（点已选中直播项重开播放、搜索切走再回丢结果）。
                        if (route == currentRoute) return@NavRail
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
                    HomeScreen(
                        onNavigate = { navController.navigate(it) },
                        focusRestoreTick = focusRestoreTick,
                    )
                }
                composable<OrionRoute.Live> {
                    LiveScreen(
                        onNavigate = { navController.navigate(it) },
                        onBack = { navController.popBackStack() },
                        focusRestoreTick = focusRestoreTick,
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
                        focusRestoreTick = focusRestoreTick,
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
                        focusRestoreTick = focusRestoreTick,
                    )
                }
                composable<OrionRoute.Search> {
                    SearchScreen(
                        onNavigate = { navController.navigate(it) },
                        focusRestoreTick = focusRestoreTick,
                    )
                }
                composable<OrionRoute.Favorites> {
                    FavoritesScreen(
                        onNavigate = { navController.navigate(it) },
                        focusRestoreTick = focusRestoreTick,
                    )
                }
                composable<OrionRoute.Settings> {
                    // P1-6：设置页用 NavHost 持有的同一个 Activity 级 UpdateViewModel
                    // 实例（冷启动检查与手动检查共用一份状态，不再弹两张重叠卡片）。
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        updateViewModel = updateViewModel,
                        focusRestoreTick = focusRestoreTick,
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
        // 只在这里渲染一份（设置页那份已删，见 SettingsScreen 的 C4-P2-1）；
        // 关闭后焦点归宿由上面的 focusRestoreTick 恢复（各页回自己默认锚点）。
        if (showUpdateCard) {
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
        // C4-P1-1：首页双按退出的第一次提示。
        if (showExitHint) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                OrionToast(
                    message = "再按一次返回退出",
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}
