package com.oriontv.feature.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.onKeyUp
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.core.util.TimeUtils
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.BrandProgressBar
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.ConfirmDialog
import com.oriontv.ui.components.LoadingAnimation
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/** 菜单键（遥控器 Menu）：用原生 KEYCODE_MENU 判断，不依赖 Key.Menu 是否存在。 */
private fun isMenuKey(event: KeyEvent): Boolean =
    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU

/** 面板焦点落点（可单测）：分组含当前台落当前台行，否则落该分组第 0 行。 */
internal fun resolvePanelFocusIndex(
    filtered: List<M3uParser.Channel>,
    current: M3uParser.Channel?,
): Int = current?.let { LiveController.indexOfChannel(filtered, it) }?.coerceAtLeast(0) ?: 0

/** 频道行是否消费按键（可单测）：EPG 弹窗打开时不消费，焦点事件交给弹窗处理。 */
internal fun shouldConsumeChannelRowKey(epgDialogOpen: Boolean): Boolean = !epgDialogOpen

/**
 * 直播页焦点 containment 是否应在预览阶段消费该 KeyDown（可单测，C7-P1-1 /
 * A7-P2-5 同源）：面板打开时左键、EPG 弹窗打开时左/右键——焦点经空间导航会
 * 跑到导航轨，返回键绕过“先关面板/弹窗”层级直接退出直播页，EPG 左键切台
 * 也失效。只拦 KeyDown（焦点移动），不拦 KeyUp：KeyUp 仍到根的 onKeyUp 走
 * 切台/关面板逻辑。EPG 打开时面板必然也开着，左键两条都命中，无冲突。
 */
internal fun shouldContainLiveFocusKey(panelOpen: Boolean, epgDialogOpen: Boolean, key: Key): Boolean =
    (epgDialogOpen && (key == Key.DirectionLeft || key == Key.DirectionRight)) ||
        (panelOpen && key == Key.DirectionLeft)

/**
 * EPG 弹窗焦点陷阱是否应拦截该方向键（可单测，P2-6）：
 * 焦点在首个节目行按上、或在“关闭”按钮按下时拦截，不让焦点移出弹窗落到
 * 背后频道行（OK 会误触选台）；行间移动、末行下移到“关闭”按钮都放行。
 * @param focusedIndex 当前焦点位置：节目行下标 0..n-1，n = “关闭”按钮，-1 = 未知（不拦截）
 * @param programmeCount 节目行数（“关闭”按钮的下标就是它）
 */
internal fun shouldTrapEpgFocusKey(key: Key, focusedIndex: Int, programmeCount: Int): Boolean {
    if (focusedIndex < 0) return false
    return when (key) {
        Key.DirectionUp -> focusedIndex == 0
        Key.DirectionDown -> focusedIndex >= programmeCount
        else -> false
    }
}

/** 直播长按换台节流（P2-7）：按住上/下时每 400ms 最多换一台，免得重复事件
 * 以遥控器原生速率（通常每秒十余次）连续触发 playCurrent、反复重启播放器；
 * 具体速率需真机确认后调。 */
private const val LIVE_REPEAT_CHANGE_MIN_MS = 400L

/**
 * 根部 OK 是否走重试（可单测，A5-P2-4）：
 * A5-P2-4：只在 playbackFailed（自动处理耗尽、失败页已出）时重试；
 * failCount 1..5 时 OK 改重试与设计 §5.4 不符（失败未耗尽时 OK 应开面板），
 * 注释里“有意为之”的旧行为已废弃。
 */
internal fun shouldRetryOnOk(playbackFailed: Boolean, failCount: Int): Boolean =
    playbackFailed

/**
 * 直播页（ui-design.md §5.4）：
 * 全屏视频（Media3 PlayerView）；OK 打开右侧频道面板（420dp，从右滑入 180ms，浮层底）；
 * 面板顶部是分组 chip（★ 收藏在最前），下方频道列表每行台标 + 台名 + 正在播节目名 + 进度条，
 * 正在播的台名前有绿点；面板打开时视频继续播；
 * 上/下换台、左/右切线路；换台后底部浮现信息条（台名 + 正在播 / 稍后播）3 秒后消失；
 * 断流自动切备用线路或下一台（超过 5 次停手并提示）；
 * 菜单键收藏当前台；数字键直接切台；菜单键/长按 OK 收藏面板里的台；
 * EPG 全天节目单弹窗（面板里菜单键打开，左右键切台）；
 * 返回键层级：退出确认 > EPG 弹窗 > 频道面板 > 退出确认。
 */
@Composable
fun LiveScreen(
    onNavigate: (OrionRoute) -> Unit,
    onBack: () -> Unit = {},
    viewModel: LiveViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val rootRequester = remember { FocusRequester() }
    // P2-7：长按上/下换台的状态——某次重复事件换过台时，松手那次 KeyUp 不再多换一台。
    var liveRepeatConsumed by remember { mutableStateOf(false) }
    var lastRepeatChangeMs by remember { mutableLongStateOf(0L) }

    // C5-P1-2：焦点在导航轨上时按返回，根 Box 的 onKeyUp 收不到（焦点不在
    // 本页组合内），旧实现直接走 Activity 默认返回、白白退出直播页。
    // 面板/弹窗/确认框均关闭时才由 BackHandler 接返回、开退出确认；
    // 有一层打开时 BackHandler 让位，返回键仍走根 Box 的层级处理。
    BackHandler(
        enabled = !uiState.panelOpen && uiState.epgDialogChannel == null &&
            !uiState.exitConfirmOpen,
    ) {
        viewModel.openExitConfirm()
    }

    LaunchedEffect(Unit) {
        viewModel.init()
        // 第 6 轮 P1-4：应用内导航返回（导航轨切走再回来）也要恢复——
        // Activity 全程 RESUMED，ON_RESUME 不触发；init() 早退（ViewModel
        // 存活），只靠它恢复不了播放与定时器。首启时频道表还是空的，
        // onPageResumed 直接早退，不干扰 init() 的加载；重进时才真正重起
        // 定时器、重开播。Home/PiP 返回仍走 ON_RESUME，两条路不重叠。
        viewModel.onPageResumed()
        rootRequester.requestFocus()
    }

    // A5-P1-1/A5-P1-2/A5-P2-10：离页/回页的播放与定时器接线。
    // ON_PAUSE（切后台、进画中画）与 onDispose（导航轨切走本页）都走
    // onPagePaused：开关关着即停本页音频，时钟/EPG 刷新定时器随页停；
    // ON_RESUME 走 onPageResumed：定时器重起，播放器被抢走/停掉时重开播。
    // 第 6 轮 P1-4：应用内导航返回不经过 Activity 生命周期，恢复还挂在
    // 上面的 LaunchedEffect(Unit) 里（见 init() 调用处）。
    // P2-9：同时维护前台标志（LiveViewModel.startTicker 的门控），
    // 切后台时停时钟/EPG 刷新，回前台恢复；只看 Activity 前台，与点播页同款接线。
    // ON_RESUME 先开门控再重起定时器，ON_PAUSE 先关门控再停页。
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

    // 关闭面板/弹窗/退出确认、或空态加载出频道后，焦点回根
    //（与 ChannelPanel 面板打开时落焦点的做法一致；面板打开时面板自己管焦点）。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑。
    // A5-P1-3：失败页消失（playbackFailed 由真变假）也重跑——断流失败页上
    // 按上下键/数字键换台后失败页卸载，焦点曾留在已卸载的“重试”按钮上。
    LaunchedEffect(
        uiState.panelOpen,
        uiState.epgDialogChannel,
        uiState.exitConfirmOpen,
        uiState.playbackFailed,
        uiState.channels.isEmpty(),
        focusRestoreTick,
    ) {
        if (uiState.channels.isNotEmpty() && !uiState.panelOpen &&
            uiState.epgDialogChannel == null && !uiState.exitConfirmOpen
        ) {
            rootRequester.requestFocus()
        }
    }

    // 数字键：Compose 的 Key 没有 Digit0..9 常量，用原生 keyCode 映射
    //（KEYCODE_0–9 / KEYCODE_NUMPAD_0–9）。
    fun digitOf(event: KeyEvent): Char? {
        val code = event.nativeKeyEvent.keyCode
        if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
            return '0' + (code - android.view.KeyEvent.KEYCODE_0)
        }
        if (code in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9) {
            return '0' + (code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
        }
        return null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // C4-P2-3：根背景用设计稿墨黑，不用纯黑（设计稿禁用纯黑）。
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onPreviewKeyEvent { event ->
                // C7-P1-1/A7-P2-5：面板/EPG 打开时先做焦点 containment——左/右
                // KeyDown 在隧道阶段消费掉，不让焦点移出面板/弹窗落到导航轨；
                // KeyUp 不拦，仍到下面 onKeyUp 走 EPG 切台/关面板逻辑。
                // 面板打开且焦点已在导航轨上时按返回直接退出的问题也由此根治：
                // 焦点进不了导航轨，返回键一定先经过根的层级处理。
                if (event.type == KeyEventType.KeyDown &&
                    shouldContainLiveFocusKey(
                        uiState.panelOpen,
                        uiState.epgDialogChannel != null,
                        event.key,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                // P2-7：直播长按上/下重复换台——重复 KeyDown 在预览阶段拦截并换台，
                // 一律消费，不让重复事件触发焦点移动（与播放页 P1-C 同类问题）。
                // 首按（repeatCount == 0）不换台，换台仍在松键时触发一次；
                // 面板/弹窗/退出确认打开时上下交给焦点移动，不拦截。
                // 节流见 LIVE_REPEAT_CHANGE_MIN_MS：节流掉的重复事件也消费掉。
                if (event.type != KeyEventType.KeyDown ||
                    event.nativeKeyEvent.repeatCount == 0 ||
                    uiState.panelOpen || uiState.epgDialogChannel != null || uiState.exitConfirmOpen
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val now = System.currentTimeMillis()
                        if (now - lastRepeatChangeMs < LIVE_REPEAT_CHANGE_MIN_MS) return@onPreviewKeyEvent true
                        lastRepeatChangeMs = now
                        liveRepeatConsumed = true
                        viewModel.changeChannel(if (event.key == Key.DirectionUp) -1 else 1)
                        true
                    }
                    else -> false
                }
            }
            .onKeyUp { event ->
                when {
                    // 退出确认打开时：返回取消确认，其他键交给弹窗按钮。
                    uiState.exitConfirmOpen -> {
                        if (event.key == Key.Back) {
                            viewModel.dismissExitConfirm()
                            true
                        } else {
                            false
                        }
                    }
                    // EPG 弹窗打开时：左右切台节目单，OK/返回关闭；上下留给列表焦点。
                    uiState.epgDialogChannel != null -> {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                viewModel.switchEpgDialogChannel(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                viewModel.switchEpgDialogChannel(1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.Back -> {
                                viewModel.closeEpgDialog()
                                true
                            }
                            else -> false
                        }
                    }
                    // 面板打开时：返回关面板，菜单键打开焦点频道的节目单；其余放行给焦点移动。
                    uiState.panelOpen -> {
                        when (event.key) {
                            Key.Back -> {
                                viewModel.closePanel()
                                true
                            }
                            else -> {
                                if (isMenuKey(event)) {
                                    // A4-P2-7：焦点在分组 chip 上时行没消费按键，走到这里；
                                    // focusedChannelIndex 是旧值（上次聚焦的行），用它开 EPG
                                    // 会开到错台，改开当前播放台的节目单。
                                    val current = uiState.channels.getOrNull(uiState.currentIndex)
                                    if (current != null) viewModel.openEpgDialog(current)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                    // 面板关闭时：数字键换台、菜单收藏、OK 开面板/重试、上/下换台、左/右切线路。
                    else -> {
                        val digit = digitOf(event)
                        when {
                            digit != null -> {
                                viewModel.appendDigit(digit)
                                true
                            }
                            isMenuKey(event) -> {
                                viewModel.toggleFavoriteCurrent()
                                true
                            }
                            event.key == Key.DirectionCenter || event.key == Key.Enter -> {
                                if (uiState.channels.isEmpty()) {
                                    false // 空态按钮自带焦点，不抢
                                } else if (shouldRetryOnOk(uiState.playbackFailed, uiState.failCount)) {
                                    viewModel.retryCurrent() // 失败过且未耗尽，或 GiveUp：OK 重试当前台
                                    true
                                } else {
                                    viewModel.openPanel()
                                    true
                                }
                            }
                            event.key == Key.DirectionUp -> {
                                // P2-7：长按换过台时松手不再多换一台。
                                if (liveRepeatConsumed) {
                                    liveRepeatConsumed = false
                                    true
                                } else {
                                    viewModel.changeChannel(-1)
                                    true
                                }
                            }
                            event.key == Key.DirectionDown -> {
                                // P2-7：长按换过台时松手不再多换一台。
                                if (liveRepeatConsumed) {
                                    liveRepeatConsumed = false
                                    true
                                } else {
                                    viewModel.changeChannel(1)
                                    true
                                }
                            }
                            event.key == Key.DirectionLeft -> {
                                viewModel.changeLine(-1)
                                true
                            }
                            event.key == Key.DirectionRight -> {
                                viewModel.changeLine(1)
                                true
                            }
                            event.key == Key.Back -> {
                                viewModel.openExitConfirm()
                                true
                            }
                            else -> false
                        }
                    }
                }
            },
    ) {
        // 全屏视频（面板/弹窗打开时继续播）。
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (uiState.isLoading) {
            // C5-P2-3：全屏加载态改用 LoadingAnimation（真转圈动画）。
            LoadingAnimation(message = "正在加载频道…")
        }

        // A5-P2-5：isBuffering 写而不读，卡顿无提示——频道已出、正在缓冲时
        // 顶部叠一个小提示（复用“正在加载…”样式）；加载频道/失败页时不叠加。
        if (uiState.isBuffering && !uiState.isLoading &&
            uiState.channels.isNotEmpty() && !uiState.playbackFailed
        ) {
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

        // 空态：未配 M3U 地址 → 指引去设置；拉取失败 → 重试。
        if (!uiState.isLoading && uiState.channels.isEmpty()) {
            EmptyState(
                m3uConfigured = uiState.m3uConfigured,
                loadError = uiState.loadError,
                onGoSettings = { onNavigate(OrionRoute.Settings) },
                onRetry = { viewModel.reload() },
            )
        }

        // 断流且自动处理耗尽：失败页（重试 / 打开频道面板手动换台）。
        if (uiState.playbackFailed && uiState.channels.isNotEmpty()) {
            PlaybackFailedOverlay(
                onRetry = {
                    viewModel.retryCurrent()
                    rootRequester.requestFocus()
                },
                onOpenPanel = { viewModel.openPanel() },
            )
        }

        // 换台信息条：台名 + 正在播 / 稍后播，3 秒后消失。
        // P2-8：不再被 Toast 完全压制——信息条与 Toast 分层显示：
        // 信息条照常显示，Toast 有信息条时上移 160dp（信息条约 115–135dp 高），
        // 3 秒计时照常走，Toast 消失后信息条继续留到自己的 3 秒走完。
        val infoIndex = uiState.infoChannelIndex
        val showInfoBar = infoIndex != null &&
            !uiState.panelOpen && uiState.epgDialogChannel == null
        if (showInfoBar && infoIndex != null) {
            val channel = uiState.channels.getOrNull(infoIndex)
            if (channel != null) {
                ChannelInfoBar(
                    number = infoIndex + 1,
                    channel = channel,
                    lineIndex = uiState.lineIndex,
                    epg = uiState.epg,
                    nowMs = uiState.nowMs,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // 提示消息条（底部居中，3 秒消失；信息条在时上移，免得盖住信息条）。
        uiState.toastMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showInfoBar) 160.dp else 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                OrionToast(message = message)
            }
        }

        // 右侧频道面板 420dp，从右滑入 180ms。
        AnimatedVisibility(
            visible = uiState.panelOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(180),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(180),
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ChannelPanel(
                uiState = uiState,
                groups = viewModel.groups(),
                filtered = viewModel.channelsForGroup(uiState.selectedGroup),
                onSelectGroup = { viewModel.selectGroup(it) },
                onSelectChannel = { viewModel.selectChannel(it) },
                onFocusChannel = { viewModel.setFocusedChannel(it) },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onOpenEpg = { viewModel.openEpgDialog(it) },
                onRefreshChannels = { viewModel.refreshChannels() },
            )
        }

        // EPG 全天节目单弹窗。
        uiState.epgDialogChannel?.let { channel ->
            EpgDialog(
                channel = channel,
                channelIndex = LiveController.indexOfChannel(uiState.channels, channel),
                epg = uiState.epg,
                nowMs = uiState.nowMs,
                onClose = { viewModel.closeEpgDialog() },
            )
        }

        // 退出确认（返回键第二层）。
        if (uiState.exitConfirmOpen) {
            ConfirmDialog(
                title = "退出直播",
                message = "确定要退出直播、回到首页吗？",
                confirmText = "退出",
                cancelText = "继续看",
                onConfirm = {
                    viewModel.dismissExitConfirm()
                    onBack()
                },
                onCancel = { viewModel.dismissExitConfirm() },
            )
        }
    }
}

/** 空态：未配地址 → 去设置；拉取失败 → 重试。 */
@Composable
private fun EmptyState(
    m3uConfigured: Boolean,
    loadError: String?,
    onGoSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (!m3uConfigured) {
                    "还没有配置直播源地址"
                } else {
                    loadError ?: "直播源加载失败"
                },
                style = OrionTextStyles.SectionTitle,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (!m3uConfigured) {
                    "请在设置里填写 M3U 地址后回来"
                } else {
                    "请检查 M3U 地址或网络后重试"
                },
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (!m3uConfigured) {
                PrimaryButton(
                    text = "去设置",
                    onClick = onGoSettings,
                    modifier = Modifier.preferredFocus(requester),
                )
            } else {
                PrimaryButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.preferredFocus(requester),
                )
            }
        }
    }
}

/** 断流失败页：自动处理耗尽后，手动重试或开面板换台。 */
@Composable
private fun PlaybackFailedOverlay(
    onRetry: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "信号异常，自动切换已停止", style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "请按上下键手动换台，或重试当前频道",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.preferredFocus(requester),
                )
                SecondaryButton(text = "频道面板", onClick = onOpenPanel)
            }
        }
    }
}

/** 换台信息条（左下角）：台号绿字 + 台名 + 线路徽章 + 正在/稍后播 + 进度条。 */
@Composable
private fun ChannelInfoBar(
    number: Int,
    channel: M3uParser.Channel,
    lineIndex: Int,
    epg: EpgParser.EpgData?,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val now = epg?.let { EpgParser.nowPlaying(it, channel.tvgId, channel.name, nowMs) }
    val next = epg?.let { EpgParser.nextProgramme(it, channel.tvgId, channel.name, nowMs) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrionColors.Background.copy(alpha = 0.7f))
            .padding(horizontal = 48.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$number",
                style = OrionTextStyles.PageTitle,
                color = OrionColors.PlayingGreen,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = channel.name, style = OrionTextStyles.PageTitle)
            if (channel.urls.size > 1) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "线路 ${lineIndex + 1}/${channel.urls.size}",
                    style = OrionTextStyles.Small,
                    color = OrionColors.TextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (now != null) {
            Text(
                text = "正在播：${now.title}  ${TimeUtils.formatClock(now.startAt)}–${TimeUtils.formatClock(now.endAt)}",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            BrandProgressBar(
                fraction = LiveController.programmeProgress(now, nowMs),
                height = 4.dp,
            )
        } else {
            Text(
                text = "正在播：暂无节目单",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }
        if (next != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "稍后播：${next.title}  ${TimeUtils.formatClock(next.startAt)}",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }
    }
}

/** 右侧频道面板（420dp，浮层底）：顶部时钟 + 分组 chip + 频道列表 + 底部当前频道信息。 */
@Composable
private fun ChannelPanel(
    uiState: LiveUiState,
    groups: List<String>,
    filtered: List<M3uParser.Channel>,
    onSelectGroup: (String) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onFocusChannel: (Int) -> Unit,
    onToggleFavorite: (M3uParser.Channel) -> Unit,
    onOpenEpg: (M3uParser.Channel) -> Unit,
    /** A4-P2-3：面板里的“刷新直播源”入口（强制拉网）。 */
    onRefreshChannels: () -> Unit,
) {
    val currentChannel = uiState.channels.getOrNull(uiState.currentIndex)
    val listState = rememberLazyListState()
    val currentRequester = remember { FocusRequester() }

    // 打开面板 / 切换分组时：滚到当前台并把焦点落在当前台（plan §3.3 第 1 条）；
    // 分组里没有当前台时焦点落该分组第 0 行，不丢焦点。
    // C5-P2-1/A5-P2-8：目标行尚未组合完成时单次 requestFocus 静默失败，
    // 改 requestFocusWithRetry；仍失败回退到根（根的 onKeyUp 知道面板开着，
    // 返回键仍能关面板），不再靠固定 delay(100) 猜时序。
    LaunchedEffect(uiState.panelOpen, uiState.selectedGroup) {
        if (uiState.panelOpen) {
            val idx = resolvePanelFocusIndex(filtered, currentChannel)
            listState.scrollToItem(idx)
            currentRequester.requestFocusWithRetry()
        }
    }
    // EPG 弹窗关闭后焦点回落到面板当前台。
    LaunchedEffect(uiState.epgDialogChannel) {
        if (uiState.epgDialogChannel == null && uiState.panelOpen) {
            currentRequester.requestFocusWithRetry()
        }
    }

    Column(
        modifier = Modifier
            .width(OrionDimens.LivePanelWidth)
            .fillMaxHeight()
            .background(OrionColors.Overlay)
            .padding(16.dp),
    ) {
        // 顶栏：标题 + 时钟。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "直播频道", style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = TimeUtils.formatClock(uiState.nowMs),
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 面板打开时断流：常驻“信号异常”提示（3 秒 Toast 消失后仍可见，播好即清）。
        if (uiState.signalError) {
            Text(
                text = "信号异常，请重新选择频道",
                style = OrionTextStyles.Body,
                color = OrionColors.Error,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        // 分组 chip（★ 收藏在最前）。
        LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
            items(groups) { group ->
                CategoryChip(
                    text = group,
                    selected = uiState.selectedGroup == group,
                    onClick = { onSelectGroup(group) },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // 频道列表。
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (uiState.selectedGroup == LiveController.FAV_GROUP) {
                        "还没有收藏的频道\n在直播画面按菜单键收藏当前台"
                    } else {
                        "该分组下暂无频道"
                    },
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        } else {
            val hasCurrentInGroup = currentChannel?.let { current ->
                filtered.any { it === current }
            } ?: false
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                // 行 key 用全表下标：同 URL 出现多次时 url 会撞 key（P2-4），全表下标唯一且稳定。
                itemsIndexed(filtered, key = { _, channel -> LiveController.indexOfChannel(uiState.channels, channel) }) { index, channel ->
                    // 同 URL 出现多次时按对象身份定位全表下标（P2-4），不落到第一条。
                    val globalIndex = LiveController.indexOfChannel(uiState.channels, channel)
                    val isCurrent = globalIndex == uiState.currentIndex
                    ChannelRow(
                        number = "${globalIndex + 1}",
                        channel = channel,
                        isCurrent = isCurrent,
                        isFavorite = uiState.favorites.any { it.url == channel.url },
                        epg = uiState.epg,
                        nowMs = uiState.nowMs,
                        // 当前台在分组里落当前行；不在时落第 0 行（与上面滚动落点一致）。
                        focusRequester = if (isCurrent || (!hasCurrentInGroup && index == 0)) {
                            currentRequester
                        } else {
                            null
                        },
                        epgDialogOpen = uiState.epgDialogChannel != null,
                        onClick = { onSelectChannel(globalIndex) },
                        onFocus = { onFocusChannel(globalIndex) },
                        onLongPress = { onToggleFavorite(channel) },
                        onMenu = { onOpenEpg(channel) },
                    )
                }
            }
        }
        // 底部当前频道信息。
        if (currentChannel != null) {
            Spacer(modifier = Modifier.height(12.dp))
            // A4-P2-3：7 天缓存内手动刷新入口（强制拉网，不断流）。
            SecondaryButton(text = "刷新直播源", onClick = onRefreshChannels)
            Spacer(modifier = Modifier.height(12.dp))
            val now = uiState.epg?.let {
                EpgParser.nowPlaying(it, currentChannel.tvgId, currentChannel.name, uiState.nowMs)
            }
            Column {
                Text(
                    text = "${uiState.currentIndex + 1}  ${currentChannel.name}",
                    style = OrionTextStyles.CardTitle,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = now?.title?.let { "正在播：$it" } ?: "正在播：暂无节目单",
                    style = OrionTextStyles.Small,
                    color = OrionColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * 频道行：台标 + 台名（当前台名前有绿点）+ 正在播节目名 + 进度条。
 * OK 选台；长按 OK 收藏/取消收藏（plan §3.3 第 8 条：长按后松手会跟一次 onClick，需挡掉）；
 * 菜单键打开该台的全天节目单。
 */
@Composable
private fun ChannelRow(
    number: String,
    channel: M3uParser.Channel,
    isCurrent: Boolean,
    isFavorite: Boolean,
    epg: EpgParser.EpgData?,
    nowMs: Long,
    focusRequester: FocusRequester?,
    epgDialogOpen: Boolean = false,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    val now = epg?.let { EpgParser.nowPlaying(it, channel.tvgId, channel.name, nowMs) }
    var rowModifier = Modifier
        .fillMaxWidth()
        .focusable()
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }
        .onKeyEvent { event ->
            // EPG 弹窗打开时行不消费任何键：否则弹窗无可聚焦行、焦点留在行上，
            // OK 会被行优先消费成选台。返回 false 交给根 Box 的弹窗分支处理。
            if (!shouldConsumeChannelRowKey(epgDialogOpen)) return@onKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter -> {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            // 长按：原生层重复发 KeyDown（repeatCount 递增），第一次重复即算长按。
                            if (event.nativeKeyEvent.repeatCount > 0 && !longPressed) {
                                longPressed = true
                                onLongPress()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (longPressed) {
                                // 长按后松手跟一次 onClick，挡掉。
                                longPressed = false
                                true
                            } else {
                                onClick()
                                true
                            }
                        }
                        else -> false
                    }
                }
                else -> {
                    if (isMenuKey(event)) {
                        if (event.type == KeyEventType.KeyUp) {
                            onMenu()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        }
        .orionFocus(focused)
        .background(OrionColors.Surface, RoundedCornerShape(OrionDimens.CardCornerRadius))
        .padding(horizontal = 12.dp, vertical = 10.dp)
    if (focusRequester != null) {
        rowModifier = rowModifier.preferredFocus(focusRequester)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 台标（有 logo 用图，没有用台号兜底）。
        if (!channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OrionColors.Overlay),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OrionColors.Overlay),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = number, style = OrionTextStyles.Small)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(OrionColors.PlayingGreen),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = channel.name, style = OrionTextStyles.CardTitle)
                if (isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "★", style = OrionTextStyles.CardTitle, color = OrionColors.Brand)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = now?.title?.let { "正在播：$it" } ?: "正在播：暂无节目单",
                style = OrionTextStyles.Small,
                color = OrionColors.TextSecondary,
            )
            if (now != null) {
                Spacer(modifier = Modifier.height(6.dp))
                BrandProgressBar(
                    fraction = LiveController.programmeProgress(now, nowMs),
                    height = 4.dp,
                )
            }
        }
    }
}

/** EPG 全天节目单弹窗（640dp 居中卡片）：某台全天的节目表，焦点落在正在播的节目上。 */
@Composable
private fun EpgDialog(
    channel: M3uParser.Channel,
    channelIndex: Int,
    epg: EpgParser.EpgData?,
    nowMs: Long,
    onClose: () -> Unit = {},
) {
    val programmes = remember(epg, channel) {
        epg?.let { EpgParser.programmesFor(it, channel.tvgId, channel.name) }.orEmpty()
    }
    val currentIndex = programmes.indexOfFirst { it.startAt <= nowMs && nowMs < it.endAt }
    val listState = rememberLazyListState()
    val currentRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    // P2-6 焦点陷阱：记录当前焦点在弹窗内的位置——节目行 0..n-1，n = “关闭”按钮；
    // 初始值取将要获焦的行（正在播的节目，无则首行；无节目单时为“关闭”按钮下标 0）。
    // 注意：落焦用 requestFocusWithRetry（见下），不是固定 delay(100)；
    // 焦点入弹窗前仍在背后频道行上，该窗口内按上下最多只影响背后行一次，
    // 陷阱在焦点入弹窗后生效（窗口期需真机确认）。
    var focusedIndex by remember(channel.url) {
        mutableIntStateOf(if (programmes.isEmpty()) 0 else currentIndex.coerceAtLeast(0))
    }

    // 打开 / 切台时滚到正在播的节目并落焦点；无正在播节目时落第一行（plan §3.3 第 2 条）。
    // 无节目单时落“关闭”按钮：否则弹窗无可聚焦行，焦点留在面板行上，OK 会误切台。
    // A5-P2-3：effect 键加上 programmes.isEmpty()——节目单晚到（打开时为空、
    // EPG 随后才到）时重跑落点，不再停在“暂无节目单”的关闭按钮上。
    // C5-P2-1/A5-P2-8：固定 delay(100)+单次请求改 requestFocusWithRetry；
    // 正在播行抢不到时回退到“关闭”按钮。
    LaunchedEffect(channel.url, programmes.isEmpty()) {
        val idx = currentIndex.coerceAtLeast(0)
        if (programmes.isNotEmpty()) {
            listState.scrollToItem(idx)
            if (!currentRequester.requestFocusWithRetry()) {
                closeRequester.requestFocusWithRetry()
            }
        } else {
            closeRequester.requestFocusWithRetry()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f))
            .onPreviewKeyEvent { event ->
                // P2-6 焦点陷阱（静态实现，需真机确认）：首行按上、关闭按钮按下时
                // 在隧道阶段消费掉，不让焦点移出弹窗落到背后频道行；
                // 行间移动与末行下移到“关闭”按钮放行。左/右/OK/返回仍走根部 EPG 分支。
                event.type == KeyEventType.KeyDown &&
                    shouldTrapEpgFocusKey(event.key, focusedIndex, programmes.size)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(OrionDimens.DialogWidth)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                .background(OrionColors.Overlay)
                .padding(24.dp),
        ) {
            Text(
                text = if (channelIndex >= 0) {
                    "${channelIndex + 1}  ${channel.name} · 全天节目单"
                } else {
                    "${channel.name} · 全天节目单"
                },
                style = OrionTextStyles.SectionTitle,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (programmes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无节目单",
                        style = OrionTextStyles.Body,
                        color = OrionColors.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(programmes) { index, programme ->
                        val isNow = index == currentIndex
                        EpgProgrammeRow(
                            programme = programme,
                            isNow = isNow,
                            nowMs = nowMs,
                            focusRequester = if (isNow || (currentIndex < 0 && index == 0)) {
                                currentRequester
                            } else {
                                null
                            },
                            onFocus = { focusedIndex = index },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "← → 切换频道 · OK / 返回 关闭",
                    style = OrionTextStyles.Small,
                    color = OrionColors.TextWeak,
                )
                SecondaryButton(
                    text = "关闭",
                    onClick = onClose,
                    modifier = Modifier
                        .preferredFocus(closeRequester)
                        // P2-6：焦点落在“关闭”按钮时记一笔，陷阱才知道按下要拦截。
                        .onFocusChanged { if (it.isFocused) focusedIndex = programmes.size },
                )
            }
        }
    }
}

/** 节目单行：时间段 + 节目标题 + 正在播进度条。 */
@Composable
private fun EpgProgrammeRow(
    programme: EpgParser.Programme,
    isNow: Boolean,
    nowMs: Long,
    focusRequester: FocusRequester?,
    /** 获焦回调（P2-6 焦点陷阱用）：弹窗据此知道焦点在第几行。 */
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var rowModifier = Modifier
        .fillMaxWidth()
        .focusable()
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }
        .orionFocus(focused)
        .background(
            if (isNow) OrionColors.Surface else Color.Transparent,
            RoundedCornerShape(OrionDimens.CardCornerRadius),
        )
        .padding(horizontal = 12.dp, vertical = 10.dp)
    if (focusRequester != null) {
        rowModifier = rowModifier.preferredFocus(focusRequester)
    }
    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${TimeUtils.formatClock(programme.startAt)}–${TimeUtils.formatClock(programme.endAt)}",
                style = OrionTextStyles.Small,
                color = if (isNow) OrionColors.PlayingGreen else OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = programme.title,
                style = OrionTextStyles.CardTitle,
                modifier = Modifier.weight(1f),
            )
            if (isNow) {
                Text(text = "正在播", style = OrionTextStyles.Small, color = OrionColors.PlayingGreen)
            }
        }
        if (isNow) {
            Spacer(modifier = Modifier.height(6.dp))
            BrandProgressBar(
                fraction = LiveController.programmeProgress(programme, nowMs),
                height = 4.dp,
            )
        }
    }
}
