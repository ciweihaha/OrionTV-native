package com.oriontv.feature.live

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.onKeyUp
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.util.TimeUtils
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.BrandProgressBar
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.ConfirmDialog
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/** 菜单键（遥控器 Menu）：用原生 KEYCODE_MENU 判断，不依赖 Key.Menu 是否存在。 */
private fun isMenuKey(event: KeyEvent): Boolean =
    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU

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
) {
    val uiState by viewModel.uiState.collectAsState()
    val rootRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.init()
        rootRequester.requestFocus()
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
            .background(Color.Black)
            .focusable()
            .focusRequester(rootRequester)
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
                                    val focused = uiState.channels.getOrNull(uiState.focusedChannelIndex)
                                    if (focused != null) viewModel.openEpgDialog(focused)
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
                                } else if (uiState.failCount > 0 &&
                                    uiState.failCount <= LiveController.MAX_AUTO_FAIL
                                ) {
                                    viewModel.retryCurrent() // 失败过且未耗尽：OK 重试当前台
                                    true
                                } else {
                                    viewModel.openPanel()
                                    true
                                }
                            }
                            event.key == Key.DirectionUp -> {
                                viewModel.changeChannel(-1)
                                true
                            }
                            event.key == Key.DirectionDown -> {
                                viewModel.changeChannel(1)
                                true
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在加载频道…",
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
                onRetry = { viewModel.retryCurrent() },
                onOpenPanel = { viewModel.openPanel() },
            )
        }

        // 换台信息条：台名 + 正在播 / 稍后播，3 秒后消失（消息条优先显示）。
        val infoIndex = uiState.infoChannelIndex
        if (infoIndex != null && uiState.toastMessage == null &&
            !uiState.panelOpen && uiState.epgDialogChannel == null
        ) {
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

        // 提示消息条（底部居中，3 秒消失）。
        uiState.toastMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
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
            )
        }

        // EPG 全天节目单弹窗。
        uiState.epgDialogChannel?.let { channel ->
            EpgDialog(
                channel = channel,
                channelIndex = uiState.channels.indexOfFirst { it.url == channel.url },
                epg = uiState.epg,
                nowMs = uiState.nowMs,
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
) {
    val currentChannel = uiState.channels.getOrNull(uiState.currentIndex)
    val listState = rememberLazyListState()
    val currentRequester = remember { FocusRequester() }

    // 打开面板 / 切换分组时：滚到当前台并把焦点落在当前台（plan §3.3 第 1 条）。
    LaunchedEffect(uiState.panelOpen, uiState.selectedGroup) {
        if (uiState.panelOpen) {
            val idx = filtered.indexOfFirst { it.url == currentChannel?.url }.coerceAtLeast(0)
            listState.scrollToItem(idx)
            delay(100)
            currentRequester.requestFocus()
        }
    }
    // EPG 弹窗关闭后焦点回落到面板当前台。
    LaunchedEffect(uiState.epgDialogChannel) {
        if (uiState.epgDialogChannel == null && uiState.panelOpen) {
            delay(100)
            currentRequester.requestFocus()
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
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(filtered, key = { _, channel -> channel.url }) { _, channel ->
                    val globalIndex = uiState.channels.indexOfFirst { it.url == channel.url }
                    val isCurrent = channel.url == currentChannel?.url
                    ChannelRow(
                        number = "${globalIndex + 1}",
                        channel = channel,
                        isCurrent = isCurrent,
                        isFavorite = uiState.favorites.any { it.url == channel.url },
                        epg = uiState.epg,
                        nowMs = uiState.nowMs,
                        focusRequester = if (isCurrent) currentRequester else null,
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
) {
    val programmes = remember(epg, channel) {
        epg?.let { EpgParser.programmesFor(it, channel.tvgId, channel.name) }.orEmpty()
    }
    val currentIndex = programmes.indexOfFirst { it.startAt <= nowMs && nowMs < it.endAt }
    val listState = rememberLazyListState()
    val currentRequester = remember { FocusRequester() }

    // 打开 / 切台时滚到正在播的节目并落焦点；无正在播节目时落第一行（plan §3.3 第 2 条）。
    LaunchedEffect(channel.url) {
        val idx = currentIndex.coerceAtLeast(0)
        if (programmes.isNotEmpty()) {
            listState.scrollToItem(idx)
            delay(100)
            currentRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f)),
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
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "← → 切换频道 · OK / 返回 关闭",
                style = OrionTextStyles.Small,
                color = OrionColors.TextWeak,
            )
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
) {
    var focused by remember { mutableStateOf(false) }
    var rowModifier = Modifier
        .fillMaxWidth()
        .focusable()
        .onFocusChanged { focused = it.isFocused }
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
