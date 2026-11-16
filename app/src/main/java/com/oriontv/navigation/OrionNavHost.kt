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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavOptions
import androidx.navigation.navDeepLink
import androidx.navigation.navOptions
import androidx.navigation.toRoute
import com.oriontv.feature.auth.AuthViewModel
import com.oriontv.feature.auth.LoginModal
import com.oriontv.feature.detail.DetailScreen
import com.oriontv.feature.detail.DetailViewModel
import com.oriontv.feature.favorites.FavoritesScreen
import com.oriontv.feature.home.HomeScreen
import com.oriontv.feature.live.LiveScreen
import com.oriontv.feature.play.PlayScreen
import com.oriontv.feature.search.SearchScreen
import com.oriontv.feature.settings.SettingsScreen
import com.oriontv.feature.update.UpdateCard
import com.oriontv.feature.update.UpdateViewModel
import com.oriontv.feature.update.isDownloadedForLatestVersion
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
    // Jewel 第 9 轮 P2-4：导航轨是否正持有焦点——搜索页的结果区 latch 需在焦点
    // 移到轨上时清掉（否则下一次搜索启动会把焦点从轨上拉回搜索框）；只透传给
    // 搜索页，其他页不受影响。
    var navRailHasFocus by remember { mutableStateOf(false) }
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
    // P2-5 的 NavHost 部分已被下面两处覆盖（同行为：卡片在上先关卡片，
    // 否则关登录弹窗；卡片/登录互斥，不会同时生效）。
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

    // C7-P2-4：详情/播放页导航去重——各页 onNavigate 共用它：快速双按 OK 或
    // 重复深链时，栈顶已是同一路由（同参）不再堆叠。
    // 第 7 轮 P2-8 核对（已验证无问题，见 NavControllerImpl.launchSingleTopInternal
    // 源码）：launchSingleTop 下同目的地异参不是“新页”——库会把栈顶原条目
    // 原位替换成带新参数的新条目（参数不丢、不压栈），再调
    // navigator.onLaunchSingleTop；PlayScreen 的 LaunchedEffect(source, id,
    // episodeIndex, positionMs) 与 DetailScreen 的 LaunchedEffect(source, id)
    // 都会因参数变化重跑 init，换集/换片正常生效。旧注释“不同参仍是新页”
    // 说法不准，已按库行为改写；壳内无同目的地异参导航，只影响外部热启动深链。
    val navigateSingleTop: (OrionRoute) -> Unit = { route ->
        navController.navigate(route) { launchSingleTop = true }
    }

    // 第 7 轮 P2-1：回首页（收藏页空态“去首页”）走导航轨同款 popUpTo(Home)
    // 路径，不留 Home→Favorites→Home 重复栈。
    val navigateHome: () -> Unit = {
        navController.navigate(OrionRoute.Home, topLevelNavOptions())
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
                        navController.navigate(route, topLevelNavOptions())
                    },
                    // Jewel 第 9 轮 P2-4：轨获焦态透传给搜索页清结果区 latch。
                    onRailFocusChanged = { navRailHasFocus = it },
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
                        onNavigate = navigateSingleTop,
                        focusRestoreTick = focusRestoreTick,
                        // C11-P1-2：全局弹窗开着时行到达的焦点 effect 不抢焦。
                        globalModalOpen = showLoginModal || showUpdateCard,
                    )
                }
                composable<OrionRoute.Live> {
                    LiveScreen(
                        onNavigate = navigateSingleTop,
                        onBack = { navController.popBackStack() },
                        focusRestoreTick = focusRestoreTick,
                        globalModalOpen = showLoginModal || showUpdateCard,
                    )
                }
                // P2-4：深层链接（adb/语音助手/外部调用可直达详情/播放）。
                // 第 7 轮 P2-9 核对（已验证无问题，见 NavController 源码）：
                // 缺参/非法参深链在 navigate(uri)/handleDeepLink 层就被拦住——
                // 深链匹配不到（getMatchingArguments 要求必填参数齐全且可解析）
                // 时 handleDeepLink 返回 false（冷启动留在首页），navigate(uri)
                // 抛 IllegalArgumentException（热启动由 MainActivity 的
                // runCatching 接住记日志丢弃），根本到不了组合体内的 toRoute()。
                // 这里的 runCatching 是纵深防御：万一某条路径仍把坏参数带进来，
                // 记日志后退回首页，不在组合体内崩溃（与本文件 currentRoute 解析
                // 的 C5-P2-6 兜底同款做法）。
                composable<OrionRoute.Detail>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = "oriontv://detail?source={source}&id={id}&title={title}"
                        },
                    ),
                ) { entry ->
                    val route = runCatching { entry.toRoute<OrionRoute.Detail>() }
                        .onFailure { e ->
                            Log.w("OrionNavHost", "详情路由解析失败，退回首页：${e.message}")
                        }.getOrNull()
                    if (route == null) {
                        LaunchedEffect(Unit) {
                            navController.popBackStack(OrionRoute.Home, inclusive = false)
                        }
                        return@composable
                    }
                    DetailScreen(
                        source = route.source,
                        id = route.id,
                        title = route.title,
                        onNavigate = navigateSingleTop,
                        focusRestoreTick = focusRestoreTick,
                        // 第 7 轮 P1-1：全局弹窗开着时详情数据/错误到达不抢焦点。
                        globalModalOpen = showLoginModal || showUpdateCard,
                        // 第 7 轮豆瓣透传：首页来源的评分（搜索/收藏/深链无评分，null）。
                        rate = route.rate,
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
                    val route = runCatching { entry.toRoute<OrionRoute.Play>() }
                        .onFailure { e ->
                            Log.w("OrionNavHost", "播放路由解析失败，退回首页：${e.message}")
                        }.getOrNull()
                    if (route == null) {
                        LaunchedEffect(Unit) {
                            navController.popBackStack(OrionRoute.Home, inclusive = false)
                        }
                        return@composable
                    }
                    PlayScreen(
                        source = route.source,
                        id = route.id,
                        title = route.title,
                        episodeIndex = route.episodeIndex,
                        positionMs = route.positionMs,
                        // B31-P2-1：退出播放页时把本页失败源回传给详情页标灰——
                        // 写进上一级回栈项的 SavedStateHandle（DetailViewModel
                        // 的 init 块收集后消费掉）；深链直达播放页时上一级是
                        // 首页、从不读这个键，写了也无害。写完再 pop。
                        onBack = { failedSources ->
                            navController.previousBackStackEntry
                                ?.savedStateHandle[DetailViewModel.SAVED_FAILED_SOURCES] =
                                failedSources.toList()
                            navController.popBackStack()
                        },
                        focusRestoreTick = focusRestoreTick,
                        // C11-P1-1：全局弹窗开着时自动隐藏与错误 effect 不抢焦点。
                        globalModalOpen = showLoginModal || showUpdateCard,
                    )
                }
                composable<OrionRoute.Search> {
                    SearchScreen(
                        onNavigate = navigateSingleTop,
                        focusRestoreTick = focusRestoreTick,
                        // C11-P2-1/P2-2：全局弹窗开着时各焦点 effect 不抢焦，
                        // 扫码弹窗的返回处理让位给全局弹窗。
                        globalModalOpen = showLoginModal || showUpdateCard,
                        // Jewel 第 9 轮 P2-4：导航轨获焦时清结果区 latch。
                        navRailHasFocus = navRailHasFocus,
                    )
                }
                composable<OrionRoute.Favorites> {
                    FavoritesScreen(
                        onNavigate = navigateSingleTop,
                        focusRestoreTick = focusRestoreTick,
                        // C11-P2-1：全局弹窗开着时恢复 effect 不抢焦。
                        globalModalOpen = showLoginModal || showUpdateCard,
                        // 第 7 轮 P2-1：空态“去首页”回栈到已有的 Home，不压新 Home。
                        onGoHome = navigateHome,
                    )
                }
                composable<OrionRoute.Settings> {
                    // P1-6：设置页用 NavHost 持有的同一个 Activity 级 UpdateViewModel
                    // 实例（冷启动检查与手动检查共用一份状态，不再弹两张重叠卡片）。
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        updateViewModel = updateViewModel,
                        focusRestoreTick = focusRestoreTick,
                        // C11-P2-2：全局弹窗开着时二维码弹窗的返回处理让位。
                        // Jewel 第 8 轮 P2-8：同一标志也门控设置页 tick 恢复——
                        // 登录框关闭后更新卡片重现时，卡片挂载抢焦赢，设置页不争。
                        globalModalOpen = showLoginModal || showUpdateCard,
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
                downloaded = isDownloadedForLatestVersion(
                    updateState.downloadedFilePath,
                    updateState.downloadedVersion,
                    updateState.latestVersion,
                ),
                error = updateState.error,
                onUpdate = { updateViewModel.startDownload() },
                onInstall = { updateViewModel.installUpdate() },
                onSkip = { updateViewModel.skipVersion() },
                onDismiss = { updateViewModel.dismissCard() },
                // B6-P1-3：下载中卡片上的“取消下载”入口（之前 cancelDownload() 零调用方）。
                onCancelDownload = { updateViewModel.cancelDownload() },
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

/**
 * 第 7 轮 P2-1：顶级页切换的导航选项（导航轨与收藏页空态“去首页”共用）：
 * 回栈到已有的 Home（saveState + restoreState 保状态）、栈顶不重复。
 * 纯构造，可单测。
 */
internal fun topLevelNavOptions(): NavOptions = navOptions {
    popUpTo(OrionRoute.Home) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
