package com.oriontv.feature.live

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.BrandProgressBar
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 直播页（ui-design.md §5.4）：
 * 全屏视频；OK 打开右侧频道面板（420dp，从右滑入，滑入动画 180ms 由 M1 接）；
 * 面板顶部是分组 chip（全部/央视/卫视/收藏…），下方频道列表行
 * （台标 + 台名 + 正在播节目 + 进度条），正在播频道名前有绿点；
 * 上/下键换台，左/右键换线路；换台后底部信息条（台名 + 正在/稍后播）3 秒后消失；
 * 面板打开时视频继续播；返回先关面板，再按出退出确认；
 * 流失败自动换备用线路或下一频道，失败超 5 次停下并提示（M2 接）。
 * M2 补：M3U 频道列表/分组/多线路、EPG 正在/稍后播、上次频道恢复。
 */
@Composable
fun LiveScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    // 占位数据（M2 接 M3U + EPG）。
    val groups = listOf("全部", "央视", "卫视", "收藏")
    val channels = listOf(
        Triple("1", "CCTV-1", "新闻联播"),
        Triple("2", "CCTV-5", "体育世界"),
        Triple("3", "广东卫视", "广东新闻联播"),
    )
    val lines = listOf("线路一", "线路二")

    var panelOpen by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf("全部") }
    var selectedChannel by remember { mutableStateOf(0) }
    var selectedLine by remember { mutableStateOf(0) }
    var infoVisible by remember { mutableStateOf(true) }
    val rootRequester = remember { FocusRequester() }

    // 换台后信息条 3 秒后消失。
    LaunchedEffect(selectedChannel, selectedLine) {
        infoVisible = true
        delay(3000)
        infoVisible = false
    }
    LaunchedEffect(Unit) { rootRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onKeyUp { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (!panelOpen) {
                            panelOpen = true
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionUp -> {
                        if (!panelOpen) {
                            selectedChannel = (selectedChannel - 1 + channels.size) % channels.size
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> {
                        if (!panelOpen) {
                            selectedChannel = (selectedChannel + 1) % channels.size
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (!panelOpen) {
                            selectedLine = (selectedLine + 1) % lines.size
                            true
                        } else {
                            false
                        }
                    }
                    Key.Back -> {
                        if (panelOpen) {
                            panelOpen = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        // 全屏视频占位。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "${channels[selectedChannel].second} · ${lines[selectedLine]}（直播画面占位，M2 接 Media3）",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }

        // 换台信息条：台名 + 正在/稍后播，3 秒后消失。
        if (infoVisible && !panelOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(OrionColors.Background.copy(alpha = 0.7f))
                    .padding(horizontal = 48.dp, vertical = 16.dp),
            ) {
                Text(text = channels[selectedChannel].second, style = OrionTextStyles.PageTitle)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "正在播：${channels[selectedChannel].third} · 稍后播：天气预报",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        }

        // 右侧频道面板 420dp。
        if (panelOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(OrionDimens.LivePanelWidth)
                    .fillMaxHeight()
                    .background(OrionColors.Overlay)
                    .padding(16.dp),
            ) {
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                    items(groups) { group ->
                        CategoryChip(
                            text = group,
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TvLazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channels.size) { index ->
                        val (_, name, now) = channels[index]
                        ChannelRow(
                            number = "${index + 1}",
                            name = name,
                            nowPlaying = now,
                            isCurrent = index == selectedChannel,
                            onClick = {
                                selectedChannel = index
                                panelOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 频道行：台标 + 台名 + 正在播节目 + 进度条；正在播频道名前有绿点。 */
@Composable
private fun ChannelRow(
    number: String,
    name: String,
    nowPlaying: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused)
            .background(OrionColors.Surface, RoundedCornerShape(OrionDimens.CardCornerRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(OrionColors.Overlay),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number, style = OrionTextStyles.Small)
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
                Text(text = name, style = OrionTextStyles.CardTitle)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = "正在播：$nowPlaying", style = OrionTextStyles.Small)
            Spacer(modifier = Modifier.height(6.dp))
            BrandProgressBar(fraction = 0.5f, height = 4.dp)
        }
    }
}
