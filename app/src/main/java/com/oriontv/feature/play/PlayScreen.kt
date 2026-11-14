package com.oriontv.feature.play

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.core.focus.preferredFocus
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 播放页（ui-design.md §5.3）：
 * 全屏视频（Media3 PlayerView）；OK/方向键唤出控制条，5 秒无操作自动隐藏；
 * 左右键移动落点预览（T6/T7：10 秒一步，OK 提交，2 秒无操作自动提交），
 * 预览气泡含落点时间 + 抓帧缩略图（抓不到用海报兜底）；
 * 跳过片头胶囊默认 90 秒、可关闭，出现 10 秒后消失；
 * 播完后自动出下一集预告卡，3 秒倒计时，可取消；
 * 播放错误自动换源最多 2 次再报错；选集/换源/倍速为右侧抽屉。
 * 返回键层级：抽屉 → 控制条 → 退出。
 */
@Composable
fun PlayScreen(
    source: String,
    id: String,
    title: String,
    episodeIndex: Int,
    positionMs: Long,
    onBack: () -> Unit,
    viewModel: PlayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableLongStateOf(0L) }
    val previewController = remember(uiState.durationMs) {
        SeekPreviewController(uiState.durationMs.coerceAtLeast(1L))
    }
    var previewTargetMs by remember { mutableLongStateOf(-1L) }
    var previewThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    val rootRequester = remember { FocusRequester() }

    LaunchedEffect(source, id) {
        viewModel.init(source, id, title, episodeIndex, positionMs)
    }
    LaunchedEffect(Unit) { rootRequester.requestFocus() }

    fun pokeControls() {
        controlsVisible = true
        controlsTick++
    }

    // 控制条 5 秒无操作自动隐藏（抽屉打开或有待定落点时不隐藏）。
    LaunchedEffect(controlsTick, uiState.drawer) {
        if (controlsVisible && uiState.drawer == null && previewTargetMs < 0) {
            delay(5000)
            controlsVisible = false
        }
    }
    // 落点预览 2 秒无操作自动提交（T6/T7）。
    LaunchedEffect(previewTargetMs) {
        if (previewTargetMs >= 0) {
            delay(SeekPreviewController.AUTO_COMMIT_MS)
            viewModel.seekTo(previewTargetMs)
            previewController.cancel()
            previewTargetMs = -1L
            previewThumbnail = null
            pokeControls()
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
        val target = previewController.commit()
        if (target != null) {
            viewModel.seekTo(target)
            previewTargetMs = -1L
            previewThumbnail = null
            pokeControls()
            return true
        }
        return false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onKeyUp { event ->
                when (event.key) {
                    Key.Back -> {
                        when {
                            previewTargetMs >= 0 -> {
                                previewController.cancel()
                                previewTargetMs = -1L
                                previewThumbnail = null
                                pokeControls()
                                true
                            }
                            uiState.drawer != null -> {
                                viewModel.openDrawer(null)
                                true
                            }
                            controlsVisible -> {
                                controlsVisible = false
                                true
                            }
                            else -> {
                                onBack()
                                true
                            }
                        }
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (uiState.drawer != null) {
                            false // 抽屉内方向键交给焦点移动
                        } else if (!controlsVisible) {
                            pokeControls() // 控制条隐藏时方向键只唤出，不 seek
                            true
                        } else {
                            pokeControls()
                            val dir = if (event.key == Key.DirectionLeft) -1 else 1
                            previewTargetMs = previewController.nudge(
                                uiState.positionMs,
                                dir,
                            )
                            true
                        }
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (uiState.drawer != null) {
                            false // 抽屉内 OK 交给聚焦的按钮
                        } else if (commitPreview()) {
                            true
                        } else if (!controlsVisible) {
                            pokeControls() // 控制条隐藏时 OK 只唤出，不暂停
                            true
                        } else {
                            pokeControls()
                            viewModel.togglePlayPause()
                            true
                        }
                    }
                    else -> {
                        pokeControls()
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

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在加载…",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        }

        if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = uiState.error, style = OrionTextStyles.Body)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrimaryButton(text = "重试", onClick = { viewModel.retry() })
                        SecondaryButton(text = "返回", onClick = onBack)
                    }
                }
            }
        }

        if (controlsVisible && uiState.error == null) {
            PlayerControls(
                title = uiState.title,
                episodeLabel = "第 ${uiState.episodeIndex + 1} 集",
                sourceName = uiState.current?.source_name ?: source,
                positionMs = if (previewTargetMs >= 0) previewTargetMs else uiState.positionMs,
                durationMs = uiState.durationMs,
                bufferedMs = uiState.bufferedMs,
                breakpointMs = uiState.breakpointMs,
                isPlaying = uiState.isPlaying,
                playbackRate = uiState.playbackRate,
                seekPreviewMs = previewTargetMs,
                seekPreviewThumbnail = previewThumbnail,
                posterUrl = uiState.current?.poster.orEmpty(),
                showSkipIntro = uiState.skipIntroVisible,
                showNextEpisode = uiState.showNextEpisode,
                onPlayPause = { viewModel.togglePlayPause(); pokeControls() },
                onSeekBack = {
                    previewTargetMs = previewController.nudge(uiState.positionMs, -1)
                    pokeControls()
                },
                onSeekForward = {
                    previewTargetMs = previewController.nudge(uiState.positionMs, 1)
                    pokeControls()
                },
                onSkipIntro = { viewModel.skipIntro(); pokeControls() },
                onNextEpisode = { viewModel.playNextEpisode(); pokeControls() },
                onCancelNextEpisode = { viewModel.dismissNextEpisode(); pokeControls() },
                onOpenEpisodes = { viewModel.openDrawer(PlayDrawer.EPISODES); pokeControls() },
                onOpenSpeed = { viewModel.openDrawer(PlayDrawer.SPEED); pokeControls() },
                onOpenSources = { viewModel.openDrawer(PlayDrawer.SOURCES); pokeControls() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 右侧抽屉：选集 / 换源 / 倍速。
        when (uiState.drawer) {
            PlayDrawer.EPISODES -> PlayDrawerPanel(title = "选集") {
                EpisodeDrawerContent(
                    totalEpisodes = uiState.current?.episodes?.size ?: 0,
                    currentEpisode = uiState.episodeIndex,
                    onSelect = { viewModel.selectEpisode(it) },
                )
            }
            PlayDrawer.SOURCES -> PlayDrawerPanel(title = "换源") {
                SourceDrawerContent(
                    sources = uiState.results.map { it.source to it.source_name },
                    currentSource = uiState.current?.source.orEmpty(),
                    failedSources = uiState.failedSources,
                    onSelect = { viewModel.selectSource(it) },
                )
            }
            PlayDrawer.SPEED -> PlayDrawerPanel(title = "倍速") {
                SpeedDrawerContent(
                    currentRate = uiState.playbackRate,
                    onSelect = { viewModel.setPlaybackSpeed(it) },
                )
            }
            null -> Unit
        }
    }
}

/** 右侧抽屉（宽 480dp，全高，浮层底；打开时焦点落在当前项）。 */
@Composable
private fun PlayDrawerPanel(
    title: String,
    content: @Composable () -> Unit,
) {
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
                .padding(24.dp),
        ) {
            Text(text = title, style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun EpisodeDrawerContent(
    totalEpisodes: Int,
    currentEpisode: Int,
    onSelect: (Int) -> Unit,
) {
    val currentRequester = remember { FocusRequester() }
    val groups = DetailSearchLogic.episodeGroups(totalEpisodes)
    var selectedGroup by remember(totalEpisodes) {
        mutableStateOf((currentEpisode / 20).coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    }
    val (groupStart, groupEnd) = groups.getOrNull(selectedGroup) ?: (1 to totalEpisodes)
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
    Column {
        if (groups.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEachIndexed { g, (start, end) ->
                    CategoryChip(
                        text = "$start-$end",
                        selected = selectedGroup == g,
                        onClick = { selectedGroup = g },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items((groupStart..groupEnd).toList()) { ep ->
                val epIndex = ep - 1
                CategoryChip(
                    text = "第 $ep 集",
                    selected = currentEpisode == epIndex,
                    onClick = { onSelect(epIndex) },
                    modifier = if (currentEpisode == epIndex) {
                        Modifier.preferredFocus(currentRequester)
                    } else {
                        Modifier
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
) {
    val currentRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sources.forEach { (src, srcName) ->
            val failed = src in failedSources
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(
                    text = srcName + if (failed) "（失效）" else "",
                    selected = currentSource == src,
                    onClick = { onSelect(src) },
                    modifier = if (currentSource == src) {
                        Modifier.preferredFocus(currentRequester)
                    } else {
                        Modifier
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
) {
    val currentRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(SPEED_OPTIONS) { rate ->
            CategoryChip(
                text = "${rate}×",
                selected = currentRate == rate,
                onClick = { onSelect(rate) },
                modifier = if (currentRate == rate) {
                    Modifier.preferredFocus(currentRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}
