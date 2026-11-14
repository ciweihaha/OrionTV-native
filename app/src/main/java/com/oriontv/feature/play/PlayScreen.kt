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
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
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
    onBack: () -> Unit,
    viewModel: PlayViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
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
    // C5-P2-2：跳过片头胶囊的焦点锚点与是否获焦（胶囊消失时焦点回根用）。
    val skipIntroRequester = remember { FocusRequester() }
    var skipIntroFocused by remember { mutableStateOf(false) }
    // 播放错误页“重试”按钮的焦点：错误出现时默认落到它（P2-3，与直播失败页一致）。
    val errorRetryRequester = remember { FocusRequester() }
    var previousDrawer by remember { mutableStateOf<PlayDrawer?>(null) }

    LaunchedEffect(source, id) {
        viewModel.init(source, id, title, episodeIndex, positionMs)
    }
    LaunchedEffect(Unit) { rootRequester.requestFocus() }
    // C4-P1-2：NavHost 级弹窗关闭后焦点回根（控制条按钮无焦点时方向键/OK 才能唤出）。
    LaunchedEffect(focusRestoreTick) {
        if (focusRestoreTick > 0) rootRequester.requestFocus()
    }

    // 从设置页返回 / 切回前台时刷新开播设置缓存（P2-9：轮询不再每 500ms 重读 DataStore）。
    // 同时维护前台状态供轮询门控用：ON_PAUSE（切后台/PiP）时停 UI 刷新与断点保存，
    // ON_RESUME 时恢复；后台播放开着时轮询仍全量推进（自动跳片头/下一集在后台也要走）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setForeground(true)
                    viewModel.refreshCachedSettings()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // 控制器随 durationMs 重建时，同步清掉旧落点（P2-6）：
    // 落点曾双份存放，控制器重建丢落点但 previewTargetMs 仍 >=0 会一直残留预览气泡。
    LaunchedEffect(previewController) {
        previewTargetMs = -1L
        previewDirection = 0
        previewThumbnail = null
    }

    // 换集/换源后清掉旧落点（P2-3）：集次版本号每开播一次 +1，
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
    var hadError by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            hadError = true
            errorRetryRequester.requestFocus()
        } else if (hadError) {
            hadError = false
            if (!controlsFirstButtonRequester.requestFocus()) rootRequester.requestFocus()
        }
    }

    // 下一集预告出时强制唤出控制条（P1-3）：播完时控制条可能已 5 秒自动隐藏，
    // 预告卡挂在控制条里，不唤出就播完无任何卡片/倒计时。
    LaunchedEffect(uiState.showNextEpisode) {
        if (uiState.showNextEpisode) controlsVisible = true
    }

    // 抽屉关闭后焦点落回控制条首选按钮（P1-9）；只在“从打开变关闭”时触发，不抢初始焦点。
    // C5-P2-1：固定 delay(100)+单次请求改 requestFocusWithRetry；仍失败回退到根。
    LaunchedEffect(uiState.drawer) {
        val wasOpen = previousDrawer != null
        previousDrawer = uiState.drawer
        if (uiState.drawer == null && wasOpen) {
            if (!controlsFirstButtonRequester.requestFocusWithRetry()) {
                rootRequester.requestFocus()
            }
        }
    }

    // C5-P2-2：跳过片头胶囊 10 秒消失时若焦点在胶囊上则焦点回根——之前
    // 胶囊卸载后焦点留在已卸载的按钮上，遥控器数秒内按什么都没反应。
    LaunchedEffect(uiState.skipIntroVisible) {
        if (!uiState.skipIntroVisible && skipIntroFocused) {
            skipIntroFocused = false
            rootRequester.requestFocus()
        }
    }

    // 控制条 5 秒无操作自动隐藏（抽屉打开、有待定落点或下一集预告时不隐藏）；
    // 隐藏后焦点回根，方向键/OK 才能继续唤出控制条（P1-9）。
    LaunchedEffect(controlsTick, uiState.drawer, uiState.showNextEpisode) {
        if (controlsVisible && uiState.drawer == null && previewTargetMs < 0 && !uiState.showNextEpisode) {
            delay(5000)
            controlsVisible = false
            rootRequester.requestFocus()
        }
    }
    // 落点预览 2 秒无操作自动提交（T6/T7）。
    // 只有 previewTargetMs >= 0 才起定时器：时长未出时 nudge() 返回 null，
    // 从不产生预览，也就不会启用提交定时器（P1-3）。
    LaunchedEffect(previewTargetMs) {
        if (previewTargetMs >= 0) {
            delay(SeekPreviewController.AUTO_COMMIT_MS)
            viewModel.seekTo(previewTargetMs)
            previewController.cancel()
            previewTargetMs = -1L
            previewDirection = 0
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
            previewDirection = 0
            previewThumbnail = null
            pokeControls()
            return true
        }
        return false
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onFocusChanged { rootFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
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
                    Key.Back -> {
                        when {
                            previewTargetMs >= 0 -> {
                                previewController.cancel()
                                previewTargetMs = -1L
                                previewDirection = 0
                                previewThumbnail = null
                                pokeControls()
                                true
                            }
                            uiState.drawer != null -> {
                                viewModel.openDrawer(null)
                                true
                            }
                            // A4-P2-6：下一集预告显示时按返回先取消预告（之前第一次
                            // 只隐藏控制条、无可见变化，第二次直接退出）。
                            uiState.showNextEpisode -> {
                                viewModel.dismissNextEpisode()
                                true
                            }
                            controlsVisible -> {
                                controlsVisible = false
                                // A4-P1-1：隐藏控制条后焦点回根，否则焦点留在已
                                // 隐藏的按钮上、方向键/OK 再也唤不出控制条。
                                rootRequester.requestFocus()
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
                            // 控制条可见时左/右已在预览阶段做过快进（P1-2），
                            // 或焦点在按钮行上、已由焦点移动处理横向切换；
                            // 这里只消费、不再重复 nudge。
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
                                pokeControls() // 控制条隐藏时下键只唤出，不进按钮行
                                true
                            }
                            rootFocused -> {
                                controlsFirstButtonRequester.requestFocus()
                                true
                            }
                            else -> false // 焦点已在按钮行/胶囊上，上下交给焦点移动
                        }
                    }
                    Key.DirectionUp -> {
                        when {
                            uiState.drawer != null -> false // 抽屉内上下交给焦点移动
                            !rootFocused &&
                                shouldShowPlayerControls(
                                    controlsVisible,
                                    uiState.error != null,
                                    uiState.showNextEpisode,
                                ) -> {
                                rootRequester.requestFocus()
                                true
                            }
                            else -> {
                                pokeControls()
                                false
                            }
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
                        SecondaryButton(text = "返回", onClick = onBack)
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
                playbackRate = uiState.playbackRate,
                seekPreviewMs = previewTargetMs,
                seekPreviewDirection = previewDirection,
                seekPreviewThumbnail = previewThumbnail,
                posterUrl = uiState.current?.poster.orEmpty(),
                showSkipIntro = uiState.skipIntroVisible,
                showNextEpisode = uiState.showNextEpisode,
                skipIntroRequester = skipIntroRequester,
                onSkipIntroFocusChanged = { skipIntroFocused = it },
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
                onNextEpisode = { viewModel.playNextEpisode(); pokeControls() },
                onCancelNextEpisode = { viewModel.dismissNextEpisode(); pokeControls() },
                onOpenEpisodes = { viewModel.openDrawer(PlayDrawer.EPISODES); pokeControls() },
                onOpenSpeed = { viewModel.openDrawer(PlayDrawer.SPEED); pokeControls() },
                onOpenSources = { viewModel.openDrawer(PlayDrawer.SOURCES); pokeControls() },
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

/** 右侧抽屉（宽 480dp，全高，浮层底；打开时焦点落在当前项；滑入 180ms，ui-design.md §4）。 */
@Composable
private fun PlayDrawerPanel(
    title: String,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(180),
        ) + androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(180),
        ),
        exit = androidx.compose.animation.slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(180),
        ),
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
