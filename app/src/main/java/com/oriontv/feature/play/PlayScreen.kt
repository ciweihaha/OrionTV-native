package com.oriontv.feature.play

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.LoadingAnimation
import com.oriontv.ui.components.ModalSlideInRight
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.consumeTaps
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/** 控制条是否显示（可单测）：下一集预告为真时强制显示，不被 5 秒自动隐藏门控。 */
internal fun shouldShowPlayerControls(
    controlsVisible: Boolean,
    hasError: Boolean,
    showNextEpisode: Boolean,
): Boolean = (controlsVisible || showNextEpisode) && !hasError

/**
 * 上键是否应把焦点拉回根部（可单测，A9-P1-4）：只在焦点仍在控制条按钮行时回根；
 * 焦点已落在跳过片头胶囊 / 下一集预告卡上时不抢——之前 KeyDown 的焦点搜索刚把
 * 焦点送上去，KeyUp 又一律拉回根部，“取消”按钮永远落不到焦点。
 */
internal fun shouldRestoreRootFocusOnUp(
    drawerOpen: Boolean,
    rootFocused: Boolean,
    controlsVisible: Boolean,
    hasError: Boolean,
    showNextEpisode: Boolean,
    skipIntroFocused: Boolean,
    nextEpisodeFocused: Boolean,
): Boolean = !drawerOpen && !rootFocused &&
    shouldShowPlayerControls(controlsVisible, hasError, showNextEpisode) &&
    !skipIntroFocused && !nextEpisodeFocused

/**
 * NavHost 级弹窗关闭的 tick 是否应把焦点拉回根部（可单测，C12-P1-4）：
 * 抽屉打开时不抢——焦点在抽屉当前项上，拉回根部后抽屉遥控不可达（方向键
 * 唤出的是根部控制条）；抽屉关闭后由抽屉关闭 effect / 控制条自行落焦。
 */
internal fun shouldRestoreRootFocusOnTick(focusRestoreTick: Int, drawerOpen: Boolean): Boolean =
    focusRestoreTick > 0 && !drawerOpen

/**
 * 抽屉内容晚到时是否应落焦点到当前项（可单测，C14-P1-2/C14-P1-3）：
 * 选集总集数 0→N、换源源列表 0→N 时 effect 重跑落焦，全局弹窗开着时不抢——
 * 否则焦点被偷出登录/更新弹窗；弹窗关闭后由 key 里的 tick 重跑补落。
 */
internal fun shouldFocusDrawerOnContentArrival(globalModalOpen: Boolean): Boolean =
    !globalModalOpen

/**
 * 控制条 5 秒无操作是否应自动隐藏（可单测，P2-2，第 6 轮修订）：
 * 控制条显示、抽屉关闭、无待定落点、无下一集预告、无错误页时才隐藏；
 * 错误页上计时器不武装——否则 5 秒后焦点被从“重试”拉回根部。
 * 旧版守卫没有错误页一项：错误出现不会取消已在跑的延时，到点仍抢焦点。
 * C11-P1-1：全局弹窗开着时也不隐藏——弹窗盖在播放页上时 5 秒到点会把
 * 焦点从弹窗偷回播放页根部，返回键直接退出播放页。
 */
internal fun shouldAutoHideControls(
    controlsVisible: Boolean,
    drawerOpen: Boolean,
    hasPendingPreview: Boolean,
    showNextEpisode: Boolean,
    hasError: Boolean,
    globalModalOpen: Boolean = false,
): Boolean =
    controlsVisible && !drawerOpen && !hasPendingPreview && !showNextEpisode && !hasError &&
        !globalModalOpen

/**
 * 落点预览 2 秒无操作是否应自动提交（可单测，P2-3）：
 * 有待定落点、抽屉关闭、无错误页时才提交；抽屉打开时不提交——否则挑集时视频
 * 在抽屉后跳进度。打开抽屉时待定落点另由抽屉 effect 直接取消（同款处理），
 * 这里是兜底（落点在抽屉打开期间才产生时，关抽屉后才起定时器）。
 * 第 8 轮 P2-4：错误页同款——错误出现时不提交，否则 2 秒到点会对已出错的
 * 播放器发 seekTo；错误出现时待定落点由自动提交 effect 直接取消。
 */
internal fun shouldAutoCommitPreview(previewTargetMs: Long, drawerOpen: Boolean, hasError: Boolean = false): Boolean =
    previewTargetMs >= 0 && !drawerOpen && !hasError

/**
 * 方向键/OK 按键是否应重置 5 秒自动隐藏计时器（可单测，P2-1/P2-2）：
 * 控制条显示、抽屉关闭、无错误页时才重置；按钮行内左/右横向移动、
 * 上/下进出按钮行都算用户操作，计时器应从这次按键重算 5 秒——
 * 旧版只在唤出/快进/点按钮时重置，慢慢浏览按钮时控制条会在手底下消失。
 * 错误页上不重置：错误页没有按钮行，按键只移动“重试/返回”焦点，
 * 重置会重新武装计时器、5 秒后把焦点从“重试”拉回根部。
 */
internal fun shouldResetAutoHideTimer(
    controlsVisible: Boolean,
    drawerOpen: Boolean,
    hasError: Boolean,
): Boolean = controlsVisible && !drawerOpen && !hasError

/**
 * 左/右键是否由预览拦截（可单测，P1-2，第 5 轮 P1-B 修订）：
 * 控制条实际显示、抽屉关闭、且焦点不在控制条按钮行上时，左/右恒为快进预览
 * （预览阶段拦截，焦点移动收不到）；焦点已在按钮行上时不拦截，
 * 左/右在 8 个按钮之间横向移动（P1-B：按钮行是唯一横向移动的场景）。
 * 抽屉打开或控制条未显示时不拦截，左/右仍交给焦点移动
 * （抽屉内横向移动、错误页重试/返回切换）。
 */
internal fun shouldInterceptSeekKeys(
    controlsShown: Boolean,
    drawerOpen: Boolean,
    focusOnControls: Boolean = false,
): Boolean = controlsShown && !drawerOpen && !focusOnControls

/** 返回键应执行的动作（可单测，B32-P2-1）。 */
internal enum class PlayBackAction {
    /** 取消落点预览（不退出）。 */
    CancelPreview,
    /** 关闭抽屉（不退出）。 */
    CloseDrawer,
    /** 取消下一集预告（不退出）。 */
    DismissNextEpisode,
    /** 退出播放页（错误页上、或控制条已隐藏时）。 */
    Exit,
    /** 隐藏控制条（不退出）。 */
    HideControls,
}

/**
 * 返回键的决策（可单测，B32-P2-1）：硬件返回（根 Box onKeyUp 的 Key.Back
 * 分支）与手势返回（下面的 BackHandler）共用这一份——预览取消 → 关抽屉 →
 * 错误页退出 → 取消下一集预告 → 隐藏控制条 → 退出；错误页优先于隐藏控制条
 * （控制条在错误页下本来就不显示，落到隐藏分支会第一次按返回无可见变化，
 * 见 A6-P2-1）。A33-P2-1：错误页也优先于下一集预告——播错时三处落错误分支
 * 都只置 error、不清除 showNextEpisode，而错误页下预告卡根本不组合，
 * 预告分支排在前面会让第一次按返回只取消不可见的卡片、需再按一次才退出。
 */
internal fun resolvePlayBackAction(
    previewActive: Boolean,
    drawerOpen: Boolean,
    showNextEpisode: Boolean,
    hasError: Boolean,
    controlsVisible: Boolean,
): PlayBackAction = when {
    previewActive -> PlayBackAction.CancelPreview
    drawerOpen -> PlayBackAction.CloseDrawer
    hasError -> PlayBackAction.Exit
    showNextEpisode -> PlayBackAction.DismissNextEpisode
    controlsVisible -> PlayBackAction.HideControls
    else -> PlayBackAction.Exit
}

/**
 * 抽屉焦点 containment 是否应在预览阶段消费该 KeyDown（可单测，C8-P1-2，
 * 与直播面板 C7-P1-1 同类、播放抽屉漏了）：抽屉打开时，左/下键在抽屉边缘
 * 会把焦点送到被遮罩盖住的控制条按钮上；只在边缘时消费，抽屉内移动放行——
 * 照直播面板那样 blanket 消费左键是错的：选集/倍速是多列网格，左/下是
 * 网格内移动，拦掉会断掉挑集。右/上键不拦：抽屉在右缘，上方无可聚焦项。
 * @param leftEdge 焦点在抽屉当前项是否处于最左列（纵列表恒为真）
 * @param bottomEdge 焦点在抽屉当前项是否处于最末行
 */
internal fun shouldContainPlayDrawerFocusKey(
    drawerOpen: Boolean,
    key: Key,
    leftEdge: Boolean,
    bottomEdge: Boolean,
): Boolean = drawerOpen && ((key == Key.DirectionLeft && leftEdge) ||
    (key == Key.DirectionDown && bottomEdge))

/**
 * 抽屉网格项的焦点是否在边缘（可单测，C8-P1-2）：选集 4 列、倍速 3 列。
 * @param indexInPage 项在当前页内的下标（0 起）
 * @param columns 列数
 * @param count 当前页总项数
 */
internal fun drawerGridFocusEdges(
    indexInPage: Int,
    columns: Int,
    count: Int,
): Pair<Boolean, Boolean> {
    if (count <= 0) return true to false
    val index = indexInPage.coerceIn(0, count - 1)
    val lastRowStart = ((count - 1) / columns) * columns
    return (index % columns == 0) to (index >= lastRowStart)
}

/**
 * 抽屉纵列表项的焦点是否在边缘（可单测，C8-P1-2）：换源是纵列表，
 * 左键永远无列表内移动（恒为左缘），下键只在末项是底缘。
 */
internal fun drawerListFocusEdges(index: Int, count: Int): Pair<Boolean, Boolean> {
    if (count <= 0) return true to false
    return true to (index.coerceIn(0, count - 1) >= count - 1)
}

/**
 * 选集抽屉分组行 chip 是否在左缘（可单测，C10-P1-1）：分组行是横向单行，
 * 只有首个分组 chip 是左缘；其余 chip 按左键应切到上一个分组。纵列表函数
 * drawerListFocusEdges 左缘恒为 true，不能复用到这里——之前复用导致
 * 非首分组 chip 上左键被根部预览拦截消费、变死键，当前集之前的分组在
 * 抽屉里选不到（只能退回详情页选集）。
 */
internal fun drawerGroupRowLeftEdge(index: Int): Boolean = index == 0

/**
 * 播放页 OK 是否应触发播放/暂停（可单测，第 7 轮 P2-6）：抽屉打开、控制条
 * 隐藏、有待定落点时 OK 另有去处（抽屉内按钮/唤出控制条/提交落点）；
 * 错误页上 OK 也不触发——焦点因“重试”落焦失败回退到根部时，旧分支会在
 * 错误页后 pokeControls + togglePlayPause。
 */
internal fun shouldTogglePlayPauseOnOk(
    drawerOpen: Boolean,
    hasPendingPreview: Boolean,
    controlsVisible: Boolean,
    hasError: Boolean,
): Boolean = !drawerOpen && !hasPendingPreview && controlsVisible && !hasError

/**
 * 已播完 settled 态下的落点预览是否应视为 stale 直接丢弃（可单测，A68-P2-1）：
 * 播完的集被别页抢走播放器后回页，A67 跳过 reclaim，页面停在无媒体已播完态；
 * 此时按左/右仍能造出落点预览（预览拦截只看控制条/抽屉/焦点，不看播放器
 * 是否挂着本集媒体），但 commitPreview / 2 秒自动提交走 seekTo 的 A60 URL
 * 门控恒失败、预览原样保留，OK 回落链又被 hasPendingPreview 堵死，主操作键
 * 永久静默失灵。A61-P2-2 的保留只应适用于“挂片在途、稍后可提交”的
 * reclaim/加载窗口；settled 播完态永远不会再挂片（reclaim 有 isEnded 门控、
 * pendingStart 在 A67 后已无“已播完集待补开播”的写者），保留即永久卡死，
 * 故提交失败时直接清掉、再落到 togglePlayPause 的 A67 重播路。只在四项全
 * 成立时丢弃：已播完、预告卡已不在（倒计时仍在走时到 0 即自动切集并清预览，
 * 不构成）、不在加载中（init/重试/自动换源同步置 isLoading=true，开播读数
 * 窗口内保留、可由挂片后的下次 OK 重试）、无错误页（错误页上的预览由自动
 * 提交 effect 的错误分支取消，不在这里重播）。
 * A69-P2-1：三处开播入口（playNextEpisode/selectEpisode/selectSource）现已
 * 同步置 isLoading=true，读数窗口内不再判为 stale；窗口内裸 OK 直达的重播路
 * 另由 replayEndedEpisode 的在途开播守卫拦住，在途的下一集/新源不再被静默取消。
 */
internal fun shouldDiscardStaleEndedPreview(
    isEnded: Boolean,
    showNextEpisode: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
): Boolean = isEnded && !showNextEpisode && !isLoading && !hasError

/**
 * 选集分组行应滚到的下标（可单测，第 7 轮 P2-7）：只有多分组时才滚，
 * 目标是选中分组；单分组/空表不滚（-1）。旧实现滚动 effect 的 key 是 Unit，
 * 抽屉打开期间换源致总集数变化、selectedGroup 重算时滚动不重跑，选中分组
 * 可能留在可视区外。
 */
internal fun resolveGroupScrollIndex(selectedGroup: Int, groupCount: Int): Int =
    if (groupCount <= 1) -1 else selectedGroup.coerceIn(0, groupCount - 1)

/**
 * 播放页（ui-design.md §5.3）：
 * 全屏视频（Media3 PlayerView）；OK/方向键唤出控制条，5 秒无操作自动隐藏；
 * 左右键移动落点预览（T6/T7：10 秒一步，OK 提交，2 秒无操作自动提交），
 * 预览气泡含落点时间 + 抓帧缩略图（抓不到用海报兜底）；
 * 跳过片头胶囊默认 90 秒、可关闭，出现 10 秒后消失；
 * 播完后自动出下一集预告卡，3 秒倒计时，可取消；
 * 播放错误自动换源最多 2 次再报错；选集/换源/倍速为右侧抽屉。
 * 控制条按钮行遥控路径（P1-B）：焦点在根部时左/右恒为快进预览；
 * 按下键进入按钮行（焦点落首个按钮），行内左/右横向移动、OK 执行，
 * 按上键回到根部（左/右恢复快进预览）；抽屉关闭后焦点也落回首个按钮。
 * 返回键层级：抽屉 → 控制条 → 退出。
 */
@Composable
fun PlayScreen(
    source: String,
    id: String,
    title: String,
    episodeIndex: Int,
    positionMs: Long,
    /** B31-P2-1：退出时把本页失败源集合回传给调用方（详情页标灰用）——
     * 详情页从上一级回栈项的 SavedStateHandle 读（见 OrionNavHost）。 */
    onBack: (failedSources: Set<String>) -> Unit,
    viewModel: PlayViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
    /**
     * C11-P1-1：全局弹窗（登录/更新卡片）是否正开着——开着时控制条自动隐藏
     * effect 与错误 effect 不隐藏、不抢焦点（与详情/直播页的 globalModalOpen
     * 门控同款）。
     */
    globalModalOpen: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableLongStateOf(0L) }
    val previewController = remember(uiState.durationMs) {
        // 时长未出时按 durationMs=0 构建：nudge() 返回 null、不产生预览（P1-3），
        // 不再用 coerceAtLeast(1L) 造一个 00:00 落点。
        SeekPreviewController(uiState.durationMs)
    }
    var previewTargetMs by remember { mutableLongStateOf(-1L) }
    // 预览方向（-1 快退 / +1 快进 / 0 无预览）：气泡的方向标识用（P2-1）。
    var previewDirection by remember { mutableIntStateOf(0) }
    var previewThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    val rootRequester = remember { FocusRequester() }
    // 焦点是否在全屏根部（P1-B）：根部获焦时左/右恒为快进预览；
    // 焦点进了控制条按钮行（或胶囊/预告卡按钮）时根部失焦，左/右交给按钮间移动。
    var rootFocused by remember { mutableStateOf(false) }
    // 控制条首选按钮（播放/暂停）的焦点：抽屉关闭后焦点落回这里。
    val controlsFirstButtonRequester = remember { FocusRequester() }
    // 第 18 轮 C18-P2-1：活的门控读数（Jewel 第 9 轮 P2-1 同款）——抽屉关闭后
    // 落焦 effect 的重试窗口内全局弹窗打开时即停，不把焦点偷回弹窗背后的
    // 控制条按钮；LaunchedEffect 块捕获的只是启动时的参数值。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    // C5-P2-2：跳过片头胶囊的焦点锚点与是否获焦（胶囊消失时焦点回根用）。
    val skipIntroRequester = remember { FocusRequester() }
    var skipIntroFocused by remember { mutableStateOf(false) }
    // A9-P1-4：下一集预告卡“取消”按钮是否获焦（与胶囊同款跟踪；上键回根与
    // 卡片消失时的焦点恢复都靠它）。
    var nextEpisodeFocused by remember { mutableStateOf(false) }
    // 播放错误页“重试”按钮的焦点：错误出现时默认落到它（P2-3，与直播失败页一致）。
    val errorRetryRequester = remember { FocusRequester() }
    var previousDrawer by remember { mutableStateOf<PlayDrawer?>(null) }
    // C8-P2-3：错误在抽屉打开时出现，先关抽屉、等抽屉关了再落焦点——
    // 直接落焦会把焦点抢到遮罩下的“重试”按钮上；抽屉关闭 effect 见下。
    var errorPendingAfterDrawerClose by remember { mutableStateOf(false) }
    // C8-P1-2：抽屉内当前焦点项是否在左缘/底缘（各抽屉内容上报，见下）——
    // 根部预览拦截只在边缘时消费左/下键，抽屉内移动放行。
    var drawerLeftEdgeFocused by remember { mutableStateOf(false) }
    var drawerBottomEdgeFocused by remember { mutableStateOf(false) }

    // A6-P2-9（加固）：key 含集号与起点——同一回栈项复用换集时 effect 重跑，
    // init() 的同键分支按新集号开播，不丢参数。
    LaunchedEffect(source, id, episodeIndex, positionMs) {
        viewModel.init(source, id, title, episodeIndex, positionMs)
    }
    LaunchedEffect(Unit) {
        rootRequester.requestFocus()
        // A9-P1-2：应用内导航返回（设置页回来、导航轨切走再回来）也要补开播——
        // Activity 全程 RESUMED，ON_RESUME 不触发；首进时暂缓/中断均为空，是空操作。
        viewModel.onPageResumed()
    }
    // C4-P1-2：NavHost 级弹窗关闭后焦点回根（控制条按钮无焦点时方向键/OK 才能唤出）。
    // C12-P1-4：抽屉打开时不抢——焦点在抽屉当前项上，拉回根部后抽屉遥控不可达。
    LaunchedEffect(focusRestoreTick) {
        if (shouldRestoreRootFocusOnTick(focusRestoreTick, uiState.drawer != null)) {
            rootRequester.requestFocus()
        }
    }

    // 从设置页返回 / 切回前台时刷新开播设置缓存（P2-9：轮询不再每 500ms 重读 DataStore）。
    // 同时维护前台状态供轮询门控用：ON_PAUSE（切后台/PiP）时停 UI 刷新与断点保存，
    // ON_RESUME 时恢复；后台播放开着时轮询仍全量推进（自动跳片头/下一集在后台也要走）。
    // A9-P1-2：ON_PAUSE/onDispose 还走 onPagePaused（取消在途搜源/开播，记下待补），
    // ON_RESUME/重进组合走 onPageResumed（补开播/补搜源）；onPageResumed 内含
    // refreshCachedSettings，不再另调。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setForeground(true)
                    viewModel.onPageResumed()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setForeground(false)
                    viewModel.onPagePaused()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPagePaused()
        }
    }

    // 标记成功/清除提示：Toast 底部居中，2 秒消失（ui-design.md §5.8）。
    // B5-P2-11：以事件 id 为 key——同文案连续两次时 id 不同，effect 一定重跑。
    LaunchedEffect(uiState.toast?.id) {
        if (uiState.toast != null) {
            delay(2000)
            viewModel.consumeToast()
        }
    }

    fun pokeControls() {
        controlsVisible = true
        controlsTick++
    }

    // 抽屉初始落焦重试耗尽时的回退（第 8 轮 P2-6）：焦点从未进过抽屉，
    // 边缘态恒 false，左/下键会把焦点送到遮罩后的控制条按钮上；回退到根时
    // 把边缘态置为“全缘”（左缘 + 底缘均真），containment 在焦点回根期间消费
    // 左/下键；焦点一旦进抽屉，内容上报的边缘值会覆盖这里的回退值（自愈）；
    // 右/上键不拦，右键仍可经焦点搜索进抽屉。
    fun onDrawerFocusFailed() {
        drawerLeftEdgeFocused = true
        drawerBottomEdgeFocused = true
        rootRequester.requestFocus()
    }

    // 控制器随 durationMs 重建时，同步清掉旧落点（P2-6）：
    // 落点曾双份存放，控制器重建丢落点但 previewTargetMs 仍 >=0 会一直残留预览气泡。
    LaunchedEffect(previewController) {
        previewTargetMs = -1L
        previewDirection = 0
        previewThumbnail = null
    }

    // 换集/换源后清掉旧落点（P2-3）：集次版本号在换集/换源时 +1（同集同源重挂不 +1，A62-P2-1），
    // 新集时长与旧集相同时控制器不重建，没有这一环，待定落点与 2 秒提交定时器
    // 会带到新集、到点对新集 seek 旧落点。
    LaunchedEffect(uiState.episodeGeneration) {
        previewController.cancel()
        previewTargetMs = -1L
        previewDirection = 0
        previewThumbnail = null
    }

    // 播放错误页出现时焦点默认落到“重试”（P2-3）：否则焦点停在根部/原控制条按钮上，
    // 首次按 OK 只会 pokeControls，用户需先用方向键盲找按钮。
    // A4-P1-2：错误消失（重试成功）时焦点回控制条首选按钮；控制条未组合时回根。
    // 第 6 轮 P2-7：单次 requestFocus 改 requestFocusWithRetry（与抽屉/面板一致）——
    // 错误页首帧组合未完成时单次请求静默失败，焦点停在根部；仍失败回退到根。
    var hadError by remember { mutableStateOf(false) }
    // C11-P1-1：key 加 globalModalOpen——弹窗开着时不抢焦点（错误出现/消失
    // 都不落焦），弹窗关闭后 effect 重跑、按当前态补落焦点（错误在落“重试”，
    // 已消失落控制条首选按钮）；hadError 的记账不受门控影响。
    LaunchedEffect(uiState.error, globalModalOpen) {
        if (uiState.error != null) {
            hadError = true
            // C8-P2-3：错误在抽屉打开时出现不直接落焦——“重试”按钮在遮罩下，
            // 焦点会被抢过去；先关抽屉，抽屉关闭 effect 再把焦点落到“重试”。
            if (!globalModalOpen) {
                if (uiState.drawer != null) {
                    errorPendingAfterDrawerClose = true
                    viewModel.openDrawer(null)
                } else if (!errorRetryRequester.requestFocusWithRetry()) {
                    rootRequester.requestFocus()
                }
            }
        } else if (hadError) {
            hadError = false
            errorPendingAfterDrawerClose = false
            // 第 7 轮 P2-8：错误消失后焦点回控制条首选按钮也改 requestFocusWithRetry——
            // 与错误出现分支（已改重试版）不对称，单次请求在控制条尚未完成组合时
            // 静默失败，焦点回根，用户需多按一次下键才能进按钮行。
            if (!globalModalOpen) {
                if (!controlsFirstButtonRequester.requestFocusWithRetry()) rootRequester.requestFocus()
            }
        }
    }

    // 下一集预告出时强制唤出控制条（P1-3）：播完时控制条可能已 5 秒自动隐藏，
    // 预告卡挂在控制条里，不唤出就播完无任何卡片/倒计时。
    // A9-P1-4：预告卡消失（点“取消”/倒计时切集/按返回取消）时若焦点在“取消”
    // 按钮上则焦点回根（与跳过片头胶囊消失的 C5-P2-2 同款），不留在已卸载的按钮上。
    LaunchedEffect(uiState.showNextEpisode) {
        if (uiState.showNextEpisode) {
            controlsVisible = true
        } else if (nextEpisodeFocused) {
            nextEpisodeFocused = false
            // C43-P2-1：回根加全局弹窗门控——卡片在全局弹窗开着时消失，
            // 不把焦点偷回弹窗背后的根部；弹窗关闭后 tick effect 补落根部。
            // 本分支在 effect 重启时同步执行，读到的 globalModalOpen 是当前
            // 组合的值（与 onNextEpisode 回调的异步调用不同，那里须读
            // modalOpenState.value）；标记照常复位——卡片已卸载，该事实已
            // 消费，留着会让 shouldRestoreRootFocusOnUp 误判、上键回根被抑制。
            if (!globalModalOpen) rootRequester.requestFocus()
        }
    }

    // 抽屉关闭后焦点落回控制条首选按钮（P1-9）；只在“从打开变关闭”时触发，不抢初始焦点。
    // C5-P2-1：固定 delay(100)+单次请求改 requestFocusWithRetry；仍失败回退到根。
    // 第 6 轮 P2-3：抽屉打开时取消待定落点（与按返回取消同款处理）——
    // 否则 2 秒自动提交到点仍 seek，视频在抽屉后跳进度。
    LaunchedEffect(uiState.drawer) {
        val wasOpen = previousDrawer != null
        previousDrawer = uiState.drawer
        if (uiState.drawer != null) {
            previewController.cancel()
            previewTargetMs = -1L
            previewDirection = 0
            previewThumbnail = null
            // C8-P1-2：边缘态复位，抽屉内容挂载获焦后重新上报；旧抽屉的
            // 边缘值不能带到新抽屉（例如换源纵列表左缘恒真、选集网格不一定）。
            drawerLeftEdgeFocused = false
            drawerBottomEdgeFocused = false
        } else if (wasOpen) {
            // C8-P2-3：抽屉是因错误出现而关的，焦点落到错误页“重试”，不落控制条。
            // 第 18 轮 C18-P2-1：两路重试都带活门控；回退到根是单次 requestFocus()、
            // 本身不复核门控，重试因弹窗打开提前中止时也必须复核、否则仍会偷焦。
            if (errorPendingAfterDrawerClose && uiState.error != null) {
                errorPendingAfterDrawerClose = false
                if (!errorRetryRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) && !modalOpenState.value) {
                    rootRequester.requestFocus()
                }
            } else if (!controlsFirstButtonRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) && !modalOpenState.value) {
                rootRequester.requestFocus()
            }
        }
    }

    // C5-P2-2：跳过片头胶囊 10 秒消失时若焦点在胶囊上则焦点回根——之前
    // 胶囊卸载后焦点留在已卸载的按钮上，遥控器数秒内按什么都没反应。
    LaunchedEffect(uiState.skipIntroVisible) {
        if (!uiState.skipIntroVisible && skipIntroFocused) {
            skipIntroFocused = false
            // C43-P2-2：与 C43-P2-1 同款——胶囊 10 秒计时消失时全局弹窗开着，
            // 不把焦点偷回弹窗背后的根部；弹窗关闭后 tick effect 补落根部。
            // 标记照常复位（shouldRestoreRootFocusOnUp 的误判同款理由）。
            if (!globalModalOpen) rootRequester.requestFocus()
        }
    }

    // 控制条 5 秒无操作自动隐藏（抽屉打开、有待定落点、下一集预告或错误页时不隐藏）；
    // 隐藏后焦点回根，方向键/OK 才能继续唤出控制条（P1-9）。
    // A6-P1-4 / 第 6 轮 P2-2：key 加 error——错误页出现后若定时器已在跑，
    // 旧协程会被取消，不会在 5 秒到点时把焦点从“重试”按钮抢回根；
    // 到点后也再复核 error==null（双保险）。
    // 第 7 轮 P2-5：key 补 previewTargetMs——守卫读它，key 却漏了它；落点在
    // effect 已起跑后出现时守卫取的是旧值。目前无可达路径（每次 nudge 都先
    // pokeControls 重启 effect，2 秒提交远小于 5 秒隐藏），属防御性缺口。
    // C11-P1-1：key 加 globalModalOpen、守卫与到点复核都加弹窗门控——
    // 登录/更新弹窗盖在播放页上时，5 秒到点不能把焦点从弹窗偷回根部，
    // 否则返回键直接退出播放页；弹窗关闭后 effect 重跑、5 秒计时重算。
    LaunchedEffect(controlsTick, uiState.drawer, uiState.showNextEpisode, uiState.error, previewTargetMs, globalModalOpen) {
        if (shouldAutoHideControls(
                controlsVisible,
                uiState.drawer != null,
                previewTargetMs >= 0,
                uiState.showNextEpisode,
                uiState.error != null,
                globalModalOpen,
            )
        ) {
            delay(5000)
            if (uiState.error == null && !globalModalOpen) {
                controlsVisible = false
                rootRequester.requestFocus()
            }
        }
    }
    // 落点预览 2 秒无操作自动提交（T6/T7）。
    // 只有 previewTargetMs >= 0 且抽屉关闭时才起定时器：时长未出时 nudge() 返回 null，
    // 从不产生预览，也就不会启用提交定时器（P1-3）。
    // 第 6 轮 P2-3：key 加抽屉、守卫加抽屉关闭——抽屉打开时不提交（另有
    // 抽屉 effect 在打开时直接取消待定落点，这里是落点在抽屉打开期间才产生的兜底）。
    // 第 8 轮 P2-4：key 加错误、守卫加无错误页——错误出现时取消待定落点
    // （与抽屉打开时取消同款），否则 2 秒到点仍会对已出错的播放器发 seekTo；
    // 到点后也再复核 error==null（与 5 秒自动隐藏 effect 的双保险同款）。
    LaunchedEffect(previewTargetMs, uiState.drawer, uiState.error) {
        if (shouldAutoCommitPreview(previewTargetMs, uiState.drawer != null, uiState.error != null)) {
            delay(SeekPreviewController.AUTO_COMMIT_MS)
            if (uiState.error == null) {
                // A61-P2-2：只有 seek 真正发出才清预览——reclaim 窗口内门控不成立
                // 时 seekTo 返回 false，预览与控制器原样保留（不 cancel、不清
                // previewTargetMs），不静默丢弃；挂片后可由下次 OK（commitPreview）
                // 或再按方向键（nudge 改 previewTargetMs、effect 带新 2 秒计时
                // 重跑）重试。保留后本 effect 不会立即重提交：key（previewTargetMs /
                // drawer / error）未变，LaunchedEffect 不重跑，本协程已过 delay、
                // 到此即结束。失败时不 pokeControls（控制条本就因待定落点不会自动隐藏）。
                // A68-P2-1：settled 播完态例外——reclaim 跳过播完态、永不再挂片，
                // 保留即气泡永久残留，且重播后用户再按 OK 还会对重播中的本集 seek
                // 到旧落点（用户没点过的旧意图）；该态下失败即清掉（只清预览，
                // 不触发重播；重播由用户的下一次 OK 走 togglePlayPause）。
                if (viewModel.seekTo(previewTargetMs)) {
                    previewController.cancel()
                    previewTargetMs = -1L
                    previewDirection = 0
                    previewThumbnail = null
                    pokeControls()
                } else if (shouldDiscardStaleEndedPreview(
                        isEnded = uiState.isEnded,
                        showNextEpisode = uiState.showNextEpisode,
                        isLoading = uiState.isLoading,
                        hasError = uiState.error != null,
                    )
                ) {
                    previewController.cancel()
                    previewTargetMs = -1L
                    previewDirection = 0
                    previewThumbnail = null
                }
            }
        } else if (uiState.error != null) {
            previewController.cancel()
            previewTargetMs = -1L
            previewDirection = 0
            previewThumbnail = null
        }
    }
    // 落点预览缩略图：防抖 300ms 后抓帧，m3u8 抓不到时用海报兜底。
    val currentUrl = uiState.current?.episodes?.getOrNull(uiState.episodeIndex).orEmpty()
    LaunchedEffect(previewTargetMs) {
        if (previewTargetMs >= 0 && currentUrl.isNotBlank()) {
            delay(300)
            previewThumbnail = viewModel.grabPreviewFrame(currentUrl, previewTargetMs)
        } else {
            previewThumbnail = null
        }
    }

    fun commitPreview(): Boolean {
        // A61-P2-2：先读待定落点（不消费），seek 成功才消费控制器与预览态；
        // reclaim 窗口内门控不成立时 seekTo 返回 false，预览与控制器原样保留，
        // 返回 false 表示本次 OK 未提交——OK 回落链上 shouldTogglePlayPauseOnOk
        // 因 previewTargetMs 仍 >=0 而为假，最终落到消费型空操作分支，不会误
        // 暂停/误恢复（togglePlayPause 本身也有同款门控）；返回 false 而非
        // true，是为让调用方知道“未消费”，而不是把一次失败的提交谎报成已消费。
        // A68-P2-1：settled 播完态例外——调用方（OK 链 / 2 秒自动提交 effect）
        // 在返回 false 后按 shouldDiscardStaleEndedPreview 判定，settled 态
        // 直接清掉预览（该态永不再挂片，保留即永久卡死）；非 settled 态仍保留。
        val target = previewController.pendingTargetMs ?: return false
        if (!viewModel.seekTo(target)) return false
        previewController.commit()
        previewTargetMs = -1L
        previewDirection = 0
        previewThumbnail = null
        pokeControls()
        return true
    }

    // 触摸修复：视频区点按回调——错误页不做事，预览进行中提交落点，
    // 抽屉开着时关抽屉，控制条显着时收起（焦点回根），否则唤出控制条。
    // 与遥控器 OK/返回键同款行为。rememberUpdatedState 兜底：点按层的
    // 手势检测器只注册一次，回调总读最新值，不因 recomposition 重启而丢点按。
    val latestVideoTap = rememberUpdatedState {
        when {
            uiState.error != null -> Unit
            previewTargetMs >= 0 -> commitPreview()
            uiState.drawer != null -> viewModel.openDrawer(null)
            controlsVisible -> {
                controlsVisible = false
                rootRequester.requestFocus()
            }
            else -> pokeControls()
        }
    }

    /** 左/右快进预览一步（P1-2/P1-3/P2-2 共用）：时长未出或反向按过起点时清掉预览。 */
    fun nudgePreview(dir: Int) {
        val target = previewController.nudge(uiState.positionMs, dir)
        if (target == null) {
            previewTargetMs = -1L
            previewDirection = 0
            previewThumbnail = null
        } else {
            previewTargetMs = target
            previewDirection = dir
        }
    }

    // B32-P2-1：返回的唯一决策入口——硬件返回（根 Box onKeyUp 的 Key.Back
    // 分支）与手势返回（下面的 BackHandler）都调它，决策见
    // resolvePlayBackAction（预览取消 → 关抽屉 → 错误页退出 → 取消下一集
    // 预告 → 隐藏控制条 → 退出；A33-P2-1：错误页优先于下一集预告）。
    fun handleBack(): Boolean {
        return when (
            resolvePlayBackAction(
                previewActive = previewTargetMs >= 0,
                drawerOpen = uiState.drawer != null,
                showNextEpisode = uiState.showNextEpisode,
                hasError = uiState.error != null,
                controlsVisible = controlsVisible,
            )
        ) {
            PlayBackAction.CancelPreview -> {
                previewController.cancel()
                previewTargetMs = -1L
                previewDirection = 0
                previewThumbnail = null
                // 错误页上不 poke（P2-2）；取消预览本身不受影响。
                if (uiState.error == null) pokeControls()
                true
            }
            PlayBackAction.CloseDrawer -> {
                viewModel.openDrawer(null)
                true
            }
            PlayBackAction.DismissNextEpisode -> {
                // A4-P2-6：下一集预告显示时按返回先取消预告（之前第一次只隐藏
                // 控制条、无可见变化，第二次直接退出）。
                viewModel.dismissNextEpisode()
                // A9-P1-4：焦点在“取消”按钮上时按返回，卡片卸载后焦点回根
                // （与点“取消”同款；effect 兜底见上）。
                if (nextEpisodeFocused) {
                    nextEpisodeFocused = false
                    rootRequester.requestFocus()
                }
                true
            }
            PlayBackAction.Exit -> {
                // A6-P2-1：错误页显示时按返回直接退出播放页——旧实现落到
                // controlsVisible 分支只隐藏控制条（错误页下控制条本来就不
                // 显示），第一次按返回无可见变化。
                onBack(uiState.failedSources)
                true
            }
            PlayBackAction.HideControls -> {
                controlsVisible = false
                // A4-P1-1：隐藏控制条后焦点回根，否则焦点留在已隐藏的按钮上、
                // 方向键/OK 再也唤不出控制条。
                rootRequester.requestFocus()
                true
            }
        }
    }

    // B32-P2-1：手势/系统返回。PlayScreen 全页之前没有任何 BackHandler，
    // 手势导航设备上系统返回手势不产生 KEYCODE_BACK 键事件，直接走
    // OnBackPressedDispatcher、NavController 内部回调 popBackStack()，
    // onBack 全程未被调用，SAVED_FAILED_SOURCES 未写入；电视遥控器不受影响。
    // 双重触发核实：硬件返回的 KeyUp 先分发到焦点视图层（根 Box 的 onKeyUp
    // 消费、返回 true），消费后不再走到 Activity.onKeyUp → dispatcher，
    // BackHandler 收不到；手势返回没有键事件、只走 dispatcher。焦点偶尔落在
    // 本页组合之外时 onKeyUp 收不到，未消费的键事件才走到 dispatcher 由本
    // handler 接——任一按压最多只执行一条路（与直播页自第 8 轮起
    // BackHandler + onKeyUp 同款并存一致；onKeyUp 的 Key.Back 各分支恒返回
    // true，落到本页时 dispatcher 一定收不到）。
    // enabled = !globalModalOpen 门控：本 handler 注册晚于 OrionNavHost 的
    // 弹窗 handler（后注册优先），不门控会抢走登录/更新弹窗的返回处理。
    BackHandler(enabled = !globalModalOpen) { handleBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onFocusChanged { rootFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // C8-P1-2：抽屉打开时焦点 containment——左/下键在抽屉边缘会把
                // 焦点送到被遮罩盖住的控制条按钮上；只在边缘时消费，抽屉内
                // 移动放行（见 shouldContainPlayDrawerFocusKey）。
                if (event.type == KeyEventType.KeyDown &&
                    shouldContainPlayDrawerFocusKey(
                        uiState.drawer != null,
                        event.key,
                        drawerLeftEdgeFocused,
                        drawerBottomEdgeFocused,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                // P1-2：控制条可见、抽屉关闭、焦点在根部时左/右恒为快进预览——
                // 在预览（隧道）阶段拦截，焦点移动收不到左/右 KeyDown。
                // P1-C/P2-10：重复 KeyDown 也拦截并步进（长按连续快进，每次 10 秒、
                // 每次都重置 2 秒自动提交定时器），且一律消费——旧实现把 repeatCount != 0
                // 的事件放行，重复事件会触发焦点移动、把焦点推出播放区（可落到导航轨）。
                // 焦点已在按钮行上时不拦截，左/右在按钮间横向移动（P1-B）。
                if (event.type != KeyEventType.KeyDown ||
                    !shouldInterceptSeekKeys(
                        shouldShowPlayerControls(controlsVisible, uiState.error != null, uiState.showNextEpisode),
                        uiState.drawer != null,
                        focusOnControls = !rootFocused,
                    )
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        pokeControls()
                        nudgePreview(if (event.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    else -> false
                }
            }
            .onKeyUp { event ->
                when (event.key) {
                    // B32-P2-1：与手势返回的 BackHandler 共用 handleBack()，
                    // 同一份返回决策；onKeyUp 消费在先，硬件返回不会再走到
                    // dispatcher，双重触发见 handleBack 上面的核实。
                    Key.Back -> handleBack()
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (uiState.drawer != null) {
                            false // 抽屉内方向键交给焦点移动
                        } else if (!controlsVisible) {
                            // 控制条隐藏时方向键只唤出，不 seek；错误页上不 poke（P2-2）。
                            if (uiState.error == null) pokeControls()
                            true
                        } else {
                            // 控制条可见时左/右已在预览阶段做过快进（P1-2），
                            // 或焦点在按钮行上、已由焦点移动处理横向切换；
                            // 这里只消费、不再重复 nudge。
                            // 第 6 轮 P2-1：按钮行内横向移动也要重置 5 秒计时器，
                            // 否则慢慢浏览按钮时控制条会在手底下消失；根部收到的
                            // KeyUp 是预览 KeyDown 的尾巴（已 poke 过），只在
                            // 焦点不在根部时重置；错误页不重置（P2-2）。
                            if (!rootFocused && shouldResetAutoHideTimer(
                                    controlsVisible,
                                    uiState.drawer != null,
                                    uiState.error != null,
                                )
                            ) {
                                pokeControls()
                            }
                            true
                        }
                    }
                    // P1-B：下键是进入按钮行的确定性路径（根部焦点 → 首个按钮），
                    // 上键是从按钮行回到根部的路径（左/右恢复快进预览）。
                    // 下键能否从全屏根部经焦点搜索进入按钮行、以及上键回根后
                    // 焦点是否稳定落在根部，需真机确认；静态上两条路径都是显式
                    // requestFocus，不依赖焦点搜索的落点。
                    Key.DirectionDown -> {
                        when {
                            uiState.drawer != null -> false // 抽屉内上下交给焦点移动
                            !controlsVisible -> {
                                // 控制条隐藏时下键只唤出，不进按钮行；错误页上不 poke（P2-2）。
                                if (uiState.error == null) pokeControls()
                                true
                            }
                            rootFocused -> {
                                // 第 6 轮 P2-1：进按钮行也是用户操作，重置 5 秒计时器。
                                if (shouldResetAutoHideTimer(
                                        controlsVisible,
                                        uiState.drawer != null,
                                        uiState.error != null,
                                    )
                                ) {
                                    pokeControls()
                                }
                                controlsFirstButtonRequester.requestFocus()
                                true
                            }
                            else -> {
                                // 第 6 轮 P2-1：按钮行/胶囊上按上下移动焦点也要重置计时器。
                                if (shouldResetAutoHideTimer(
                                        controlsVisible,
                                        uiState.drawer != null,
                                        uiState.error != null,
                                    )
                                ) {
                                    pokeControls()
                                }
                                false // 焦点已在按钮行/胶囊上，上下交给焦点移动
                            }
                        }
                    }
                    Key.DirectionUp -> {
                        when {
                            uiState.drawer != null -> false // 抽屉内上下交给焦点移动
                            shouldRestoreRootFocusOnUp(
                                drawerOpen = uiState.drawer != null,
                                rootFocused = rootFocused,
                                controlsVisible = controlsVisible,
                                hasError = uiState.error != null,
                                showNextEpisode = uiState.showNextEpisode,
                                skipIntroFocused = skipIntroFocused,
                                nextEpisodeFocused = nextEpisodeFocused,
                            ) -> {
                                // 第 6 轮 P2-1：从按钮行回根部也要重置 5 秒计时器。
                                if (shouldResetAutoHideTimer(
                                        controlsVisible,
                                        uiState.drawer != null,
                                        uiState.error != null,
                                    )
                                ) {
                                    pokeControls()
                                }
                                rootRequester.requestFocus()
                                true
                            }
                            !rootFocused &&
                                shouldShowPlayerControls(
                                    controlsVisible,
                                    uiState.error != null,
                                    uiState.showNextEpisode,
                                ) -> {
                                // A9-P1-4：焦点已落在胶囊/预告卡上，只重置 5 秒计时器
                                //（控制条别在手底下消失），不抢回根。
                                if (shouldResetAutoHideTimer(
                                        controlsVisible,
                                        uiState.drawer != null,
                                        uiState.error != null,
                                    )
                                ) {
                                    pokeControls()
                                }
                                true
                            }
                            else -> {
                                // 错误页上不 poke（P2-2）。
                                if (uiState.error == null) pokeControls()
                                false
                            }
                        }
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (uiState.drawer != null) {
                            false // 抽屉内 OK 交给聚焦的按钮
                        } else {
                            // A68-P2-1：落点提交只试一次——commitPreview 成功即消费；
                            // 失败时若是 settled 播完态的 stale 预览（见
                            // shouldDiscardStaleEndedPreview），直接清掉，再走下面的
                            // 回落链：此时 hasPendingPreview 已为 false，控制条可见即
                            // 落到 togglePlayPause 的 A67 重播路（replayEndedEpisode），
                            // 不再落到消费型空操作分支。非 settled 态（reclaim/加载
                            // 窗口）保留预览（A61-P2-2），回落链原样。
                            val previewCommitted = if (previewTargetMs >= 0) commitPreview() else false
                            if (!previewCommitted && previewTargetMs >= 0 &&
                                shouldDiscardStaleEndedPreview(
                                    isEnded = uiState.isEnded,
                                    showNextEpisode = uiState.showNextEpisode,
                                    isLoading = uiState.isLoading,
                                    hasError = uiState.error != null,
                                )
                            ) {
                                previewController.cancel()
                                previewTargetMs = -1L
                                previewDirection = 0
                                previewThumbnail = null
                            }
                            when {
                                previewCommitted -> true
                                !controlsVisible -> {
                                    // 控制条隐藏时 OK 只唤出，不暂停；错误页上不 poke（P2-2）。
                                    if (uiState.error == null) pokeControls()
                                    true
                                }
                                shouldTogglePlayPauseOnOk(
                                    drawerOpen = uiState.drawer != null,
                                    hasPendingPreview = previewTargetMs >= 0,
                                    controlsVisible = controlsVisible,
                                    hasError = uiState.error != null,
                                ) -> {
                                    pokeControls()
                                    viewModel.togglePlayPause()
                                    true
                                }
                                else -> {
                                    // 第 7 轮 P2-6：错误页上 OK 只消费、不播放/暂停——
                                    // 焦点因“重试”落焦失败回退到根部时，旧分支会在
                                    // 错误页后 pokeControls + togglePlayPause。
                                    true
                                }
                            }
                        }
                    }
                    else -> {
                        // 错误页上不 poke（P2-2）：错误页按键只移动焦点，不重新武装计时器。
                        if (uiState.error == null) pokeControls()
                        false
                    }
                }
            },
    ) {
        // 全屏视频。
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 触摸修复：视频区点按层——原生 PlayerView 吃不到 Compose 点按，
        // 不叠这一层平板用户没有任何办法唤出控制条。叠在视频之上、
        // 控制条/抽屉/弹窗之下（后组合的层在上），只接住空白视频区的点按；
        // 控制条显着时它被控制条根盖住，点按改由 PlayerControls 的 onVideoTap 接。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { latestVideoTap.value() })
                },
        )

        if (uiState.isLoading) {
            // C5-P2-3：全屏加载态改用 LoadingAnimation（真转圈动画）。
            LoadingAnimation()
        }

        // A5-P2-5：isBuffering 写而不读，卡顿无提示——加载态已过、又开始
        // 缓冲时顶部叠一个小提示（复用“正在加载…”样式）；初始加载不叠加。
        if (uiState.isBuffering && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "正在缓冲…",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        }

        if (uiState.error != null) {
            val errorMessage = uiState.error
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = errorMessage ?: "", style = OrionTextStyles.Body)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrimaryButton(
                            text = "重试",
                            onClick = { viewModel.retry() },
                            modifier = Modifier.preferredFocus(errorRetryRequester),
                        )
                        SecondaryButton(text = "返回", onClick = { onBack(uiState.failedSources) })
                    }
                }
            }
        }

        if (shouldShowPlayerControls(controlsVisible, uiState.error != null, uiState.showNextEpisode)) {
            PlayerControls(
                title = uiState.title,
                episodeLabel = "第 ${uiState.episodeIndex + 1} 集",
                sourceName = uiState.current?.source_name ?: source,
                firstButtonRequester = controlsFirstButtonRequester,
                positionMs = if (previewTargetMs >= 0) previewTargetMs else uiState.positionMs,
                durationMs = uiState.durationMs,
                bufferedMs = uiState.bufferedMs,
                breakpointMs = uiState.breakpointMs,
                isPlaying = uiState.isPlaying,
                // 第 6 轮 P1-1：播完态传进去，倒计时门控靠它与“用户暂停”区分。
                isEnded = uiState.isEnded,
                playbackRate = uiState.playbackRate,
                seekPreviewMs = previewTargetMs,
                seekPreviewDirection = previewDirection,
                seekPreviewThumbnail = previewThumbnail,
                posterUrl = uiState.current?.poster.orEmpty(),
                showSkipIntro = uiState.skipIntroVisible,
                showNextEpisode = uiState.showNextEpisode,
                skipIntroRequester = skipIntroRequester,
                onSkipIntroFocusChanged = { skipIntroFocused = it },
                onNextEpisodeFocusChanged = { nextEpisodeFocused = it },
                drawerOpen = uiState.drawer != null,
                introEndMs = uiState.markedIntroEndMs,
                outroStartMs = uiState.outroStartMs,
                onMarkIntro = { viewModel.markIntroEnd(); pokeControls() },
                onMarkOutro = { viewModel.markOutroStart(); pokeControls() },
                onPlayPause = { viewModel.togglePlayPause(); pokeControls() },
                onSeekBack = {
                    nudgePreview(-1)
                    pokeControls()
                },
                onSeekForward = {
                    nudgePreview(1)
                    pokeControls()
                },
                onSkipIntro = { viewModel.skipIntro(); pokeControls() },
                onNextEpisode = {
                    viewModel.playNextEpisode()
                    // A9-P1-4：倒计时切集时卡片卸载，焦点在“取消”上则回根。
                    // C43-P2-1：回根加全局弹窗门控——倒计时窗口内弹出登录/更新
                    // 弹窗时不把焦点偷回弹窗背后的根部；播集推进不受影响。
                    // 注意读 modalOpenState.value 而非 globalModalOpen：
                    // PlayerControls 的倒计时协程在 effect 启动时就捕获了这个
                    // 回调（其 key 不含弹窗态），弹窗在倒计时中途打开不会重启
                    // 它，plain 参数是旧值 false、门控会被绕过；rememberUpdatedState
                    // 的 State 读到的是最新值（与抽屉关闭 effect :513 同款用法）。
                    // focused 标记照常复位——卡片已卸载，该事实已消费；留着会
                    // 让 shouldRestoreRootFocusOnUp 误判、上键回根被错误抑制。
                    if (nextEpisodeFocused) {
                        nextEpisodeFocused = false
                        if (!modalOpenState.value) rootRequester.requestFocus()
                    }
                    pokeControls()
                },
                onCancelNextEpisode = {
                    viewModel.dismissNextEpisode()
                    // A9-P1-4：点“取消”即卸载卡片，焦点回根（与失败页重试同款）。
                    if (nextEpisodeFocused) {
                        nextEpisodeFocused = false
                        rootRequester.requestFocus()
                    }
                    pokeControls()
                },
                onOpenEpisodes = { viewModel.openDrawer(PlayDrawer.EPISODES); pokeControls() },
                onOpenSpeed = { viewModel.openDrawer(PlayDrawer.SPEED); pokeControls() },
                onOpenSources = { viewModel.openDrawer(PlayDrawer.SOURCES); pokeControls() },
                // 第 70 轮：字幕循环切换（无内嵌字幕轨时按钮禁用）。
                subtitleLabel = subtitleButtonLabel(uiState.subtitleTracks, uiState.selectedSubtitleIndex),
                subtitleEnabled = uiState.subtitleTracks.isNotEmpty(),
                onCycleSubtitle = { viewModel.cycleSubtitle(); pokeControls() },
                // 触摸修复：控制条显着时，视频区空白处点按走视频点按回调
                // （收控制条/关抽屉/提交预览落点，与遥控器同款行为）。
                onVideoTap = { latestVideoTap.value() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 标记提示 Toast。
        if (uiState.toast != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                com.oriontv.ui.components.OrionToast(
                    message = uiState.toast?.message ?: "",
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }

        // 右侧抽屉：选集 / 换源 / 倍速。
        when (uiState.drawer) {
            PlayDrawer.EPISODES -> PlayDrawerPanel(title = "选集") {
                EpisodeDrawerContent(
                    totalEpisodes = uiState.current?.episodes?.size ?: 0,
                    currentEpisode = uiState.episodeIndex,
                    onSelect = { viewModel.selectEpisode(it) },
                    onFocusEdgeChange = { left, bottom ->
                        drawerLeftEdgeFocused = left
                        drawerBottomEdgeFocused = bottom
                    },
                    // 第 7 轮 P2-4：初始落焦重试耗尽时回退到根，不留在遮罩后的控制条按钮上。
                    onFocusFailed = { onDrawerFocusFailed() },
                    globalModalOpen = globalModalOpen,
                    focusRestoreTick = focusRestoreTick,
                )
            }
            PlayDrawer.SOURCES -> PlayDrawerPanel(title = "换源") {
                SourceDrawerContent(
                    sources = uiState.results.map { it.source to it.source_name },
                    currentSource = uiState.current?.source.orEmpty(),
                    failedSources = uiState.failedSources,
                    onSelect = { viewModel.selectSource(it) },
                    onFocusEdgeChange = { left, bottom ->
                        drawerLeftEdgeFocused = left
                        drawerBottomEdgeFocused = bottom
                    },
                    onFocusFailed = { onDrawerFocusFailed() },
                    globalModalOpen = globalModalOpen,
                    focusRestoreTick = focusRestoreTick,
                )
            }
            PlayDrawer.SPEED -> PlayDrawerPanel(title = "倍速") {
                SpeedDrawerContent(
                    currentRate = uiState.playbackRate,
                    onSelect = { viewModel.setPlaybackSpeed(it) },
                    onFocusEdgeChange = { left, bottom ->
                        drawerLeftEdgeFocused = left
                        drawerBottomEdgeFocused = bottom
                    },
                    onFocusFailed = { onDrawerFocusFailed() },
                    globalModalOpen = globalModalOpen,
                    focusRestoreTick = focusRestoreTick,
                )
            }
            null -> Unit
        }
    }
}

/** 右侧抽屉（宽 480dp，全高，浮层底；打开时焦点落在当前项；滑入 180ms，ui-design.md §4）。 */
@Composable
private fun PlayDrawerPanel(
    title: String,
    content: @Composable () -> Unit,
) {
    // C6-P2-1：删掉恒 visible=true 的 AnimatedVisibility——抽屉由调用方
    // `if (drawer != null)` 条件组合，卸载时整棵子树直接移除，exit 动画
    // 永远跑不到；留着只是死代码（enter 滑入也随挂载直接出现）。
    // C11-P2-3：入场滑入 180ms（设计稿 §4）——KDoc 一直写着滑入，代码却没有；
    // 用 ModalSlideInRight（MutableTransitionState false → true），只做入场、不改焦点。
    ModalSlideInRight {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.5f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(480.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .background(OrionColors.Overlay)
                .padding(24.dp)
                // A28-P2-2：抽屉内空白区（标题行、网格间隙）的点按在此消费掉，
                // 不漏到视频点按层关抽屉；只加在内层 Column 上——根 Box 的暗区
                // 点按关抽屉是既定行为（与遥控返回同款），暗区继续漏到视频层。
                .consumeTaps(),
        ) {
            Text(text = title, style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
    }
}

@Composable
private fun EpisodeDrawerContent(
    totalEpisodes: Int,
    currentEpisode: Int,
    onSelect: (Int) -> Unit,
    /** C8-P1-2：焦点项在左缘/底缘时上报，供根部预览拦截做抽屉 containment。 */
    onFocusEdgeChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** 第 7 轮 P2-4：初始落焦重试耗尽时的回退（默认不回退）。 */
    onFocusFailed: () -> Unit = {},
    /**
     * C14-P1-2：全局弹窗（登录/更新卡片）是否正开着——内容晚到（总集数
     * 0→N）时落焦 effect 重跑，无门控会把焦点从全局弹窗偷回抽屉；开着时
     * 不落焦（含回退到根——那同样会偷焦点），关闭后由 tick 重跑补落。
     */
    globalModalOpen: Boolean = false,
    /** C14-P1-2：NavHost 级弹窗关闭后 +1——内容晚到时弹窗开着跳过，关闭后重跑补落。 */
    focusRestoreTick: Int = 0,
) {
    val currentRequester = remember { FocusRequester() }
    // 第 18 轮 C18-P2-1：活的门控读数（Jewel 第 9 轮 P2-1 同款）——内容晚到
    // 落焦 effect 的重试窗口内全局弹窗打开时即停，不偷焦到抽屉。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    val groups = DetailSearchLogic.episodeGroups(totalEpisodes)
    var selectedGroup by remember(totalEpisodes) {
        mutableStateOf((currentEpisode / 20).coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    }
    val (groupStart, groupEnd) = groups.getOrNull(selectedGroup) ?: (1 to totalEpisodes)
    // 第 7 轮 P2-4：单次 requestFocus 改 requestFocusWithRetry——目标尚未组合
    // 完成时单次请求静默失败（LazyVerticalGrid 只组合可见项，当前集在首屏外时
    // 更易失败），焦点留在遮罩后的控制条按钮上，OK 会误触身后按钮；仍失败
    // 回退到根（与错误页同款）。
    // C12-P2-4：key 加总集数——抽屉打开时内容晚到（搜源未回，总集数为 0、
    // 当前集尚未组合），Unit  effect 只跑一次、落焦失败回退到根；总集数到达
    // 时重跑，焦点落在当前集上。
    // C14-P1-2：key 加 tick、落焦加全局弹窗门控——内容晚到时全局弹窗开着，
    // 无条件落焦会把焦点偷出弹窗；弹窗关闭后 tick 重跑补落。
    LaunchedEffect(totalEpisodes, focusRestoreTick) {
        if (shouldFocusDrawerOnContentArrival(globalModalOpen)) {
            // 第 18 轮 C18-P2-1：重试带活门控；回退 onFocusFailed()（落根）前
            // 再复核一次弹窗态——只补 isStillValid 时重试提前中止仍会走回退偷焦。
            if (!currentRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) && !modalOpenState.value) onFocusFailed()
        }
    }
    // 第 6 轮 P1-3：分组行改横向滚动——6 组起超出抽屉宽会被 clip 裁掉、不可见
    // 却仍可获焦（焦点视觉上“消失”）；LazyRow 让超出的 chip 能滚到、能获焦。
    // 当前集在第 6 组及之后时，打开即滚到选中分组，不让它落在可视区外。
    val groupListState = rememberLazyListState()
    // 第 7 轮 P2-7：滚动 effect 的 key 由 Unit 改为分组/选中集号相关状态——
    // 抽屉打开期间换源致总集数变化、selectedGroup 重算时旧 key 不重跑，
    // 选中分组可能留在可视区外。
    LaunchedEffect(selectedGroup, groups.size) {
        val target = resolveGroupScrollIndex(selectedGroup, groups.size)
        if (target >= 0) {
            groupListState.scrollToItem(target)
        }
    }
    Column {
        if (groups.size > 1) {
            LazyRow(
                state = groupListState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(groups.size) { g ->
                    val (start, end) = groups[g]
                    // C10-P1-1：分组行左缘是首个 chip——用 drawerGroupRowLeftEdge，
                    // 不复用纵列表的 drawerListFocusEdges（左缘恒真，非首分组
                    // chip 上左键会被根部预览拦截消费、变死键）。
                    val groupLeftEdge = drawerGroupRowLeftEdge(g)
                    CategoryChip(
                        text = "$start-$end",
                        selected = selectedGroup == g,
                        onClick = { selectedGroup = g },
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) onFocusEdgeChange(groupLeftEdge, false)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        // 第 6 轮 P2-4：5 列时每列 76.8dp，集数 chip（“第 N 集”+40dp 内边距，
        // 约 82dp）放不下、文案折成两行；改 4 列（每列约 99dp），“第 120 集”也放得下。
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items((groupStart..groupEnd).toList()) { ep ->
                val epIndex = ep - 1
                // C8-P1-2：网格边缘上报——左缘（首列）按左、下缘（末行）按下会把
                // 焦点送到遮罩下的控制条按钮，根部预览拦截只在边缘时消费。
                val (gridLeftEdge, gridBottomEdge) = drawerGridFocusEdges(
                    indexInPage = ep - groupStart,
                    columns = 4,
                    count = groupEnd - groupStart + 1,
                )
                CategoryChip(
                    text = "第 $ep 集",
                    selected = currentEpisode == epIndex,
                    onClick = { onSelect(epIndex) },
                    modifier = (if (currentEpisode == epIndex) {
                        Modifier.preferredFocus(currentRequester)
                    } else {
                        Modifier
                    }).onFocusChanged {
                        if (it.isFocused) onFocusEdgeChange(gridLeftEdge, gridBottomEdge)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceDrawerContent(
    sources: List<Pair<String, String>>,
    currentSource: String,
    failedSources: Set<String>,
    onSelect: (String) -> Unit,
    /** C8-P1-2：焦点项在左缘/底缘时上报，供根部预览拦截做抽屉 containment。 */
    onFocusEdgeChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** 第 7 轮 P2-4：初始落焦重试耗尽时的回退（默认不回退）。 */
    onFocusFailed: () -> Unit = {},
    /** C14-P1-3：与选集抽屉同款——全局弹窗开着时内容晚到不落焦，关闭后由 tick 重跑补落。 */
    globalModalOpen: Boolean = false,
    /** C14-P1-3：NavHost 级弹窗关闭后 +1。 */
    focusRestoreTick: Int = 0,
) {
    val currentRequester = remember { FocusRequester() }
    // 第 18 轮 C18-P2-1：活的门控读数（与选集抽屉同款）。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    // 第 7 轮 P2-4：同选集抽屉，单次 requestFocus 改 requestFocusWithRetry，
    // 仍失败回退到根。
    // C12-P2-4：key 加源数量——抽屉打开时内容晚到（搜源未回，源列表为空），
    // Unit effect 只跑一次、落焦失败回退到根；源到达时重跑，焦点落在当前源上。
    // C14-P1-3：key 加 tick、落焦加全局弹窗门控（与选集抽屉同款）。
    LaunchedEffect(sources.size, focusRestoreTick) {
        if (shouldFocusDrawerOnContentArrival(globalModalOpen)) {
            // 第 18 轮 C18-P2-1：重试带活门控；回退前再复核弹窗态。
            if (!currentRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) && !modalOpenState.value) onFocusFailed()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sources.forEachIndexed { index, (src, srcName) ->
            val failed = src in failedSources
            // C8-P1-2：纵列表左键永远无列表内移动（恒为左缘），下键只在末项是底缘。
            val (listLeftEdge, listBottomEdge) = drawerListFocusEdges(index, sources.size)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(
                    text = srcName + if (failed) "（失效）" else "",
                    selected = currentSource == src,
                    onClick = { onSelect(src) },
                    modifier = (if (currentSource == src) {
                        Modifier.preferredFocus(currentRequester)
                    } else {
                        Modifier
                    }).onFocusChanged {
                        if (it.isFocused) onFocusEdgeChange(listLeftEdge, listBottomEdge)
                    },
                )
            }
        }
        if (sources.isEmpty()) {
            Text(text = "暂无其他源", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        }
    }
}

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun SpeedDrawerContent(
    currentRate: Float,
    onSelect: (Float) -> Unit,
    /** C8-P1-2：焦点项在左缘/底缘时上报，供根部预览拦截做抽屉 containment。 */
    onFocusEdgeChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** 第 7 轮 P2-4：初始落焦重试耗尽时的回退（默认不回退）。 */
    onFocusFailed: () -> Unit = {},
    /** 第 9 轮 P2-5（与选集/换源抽屉 C14-P1-2/P1-3 同款）：全局弹窗开着时
     * 不落焦（含回退到根——那同样会偷焦点），关闭后由 tick 重跑补落。
     * 之前是 LaunchedEffect(Unit) + 无条件落焦：倍速抽屉打开期间全局弹窗
     * 开、关各一次后，焦点留在已卸载的弹窗按钮上、需用户自行按方向键找回。 */
    globalModalOpen: Boolean = false,
    /** 第 9 轮 P2-5：NavHost 级弹窗关闭后 +1。 */
    focusRestoreTick: Int = 0,
) {
    val currentRequester = remember { FocusRequester() }
    // 第 18 轮 C18-P2-1：活的门控读数（与选集/换源抽屉同款）。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    // 第 7 轮 P2-4：同选集抽屉，单次 requestFocus 改 requestFocusWithRetry，
    // 仍失败回退到根。
    // 第 9 轮 P2-5：key 加 tick、落焦加全局弹窗门控（与选集/换源抽屉同款）。
    LaunchedEffect(focusRestoreTick) {
        if (shouldFocusDrawerOnContentArrival(globalModalOpen)) {
            // 第 18 轮 C18-P2-1：重试带活门控；回退前再复核弹窗态。
            if (!currentRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) && !modalOpenState.value) onFocusFailed()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(SPEED_OPTIONS) { index, rate ->
            // C8-P1-2：3 列网格边缘上报（与选集网格同款）。
            val (speedLeftEdge, speedBottomEdge) = drawerGridFocusEdges(
                indexInPage = index,
                columns = 3,
                count = SPEED_OPTIONS.size,
            )
            CategoryChip(
                text = "${rate}×",
                selected = currentRate == rate,
                onClick = { onSelect(rate) },
                modifier = (if (currentRate == rate) {
                    Modifier.preferredFocus(currentRequester)
                } else {
                    Modifier
                }).onFocusChanged {
                    if (it.isFocused) onFocusEdgeChange(speedLeftEdge, speedBottomEdge)
                },
            )
        }
    }
}
