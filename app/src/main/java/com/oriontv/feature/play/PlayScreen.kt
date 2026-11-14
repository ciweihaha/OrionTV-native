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
 * 控制条 5 秒无操作是否应自动隐藏（可单测，P2-2，第 6 轮修订）：
 * 控制条显示、抽屉关闭、无待定落点、无下一集预告、无错误页时才隐藏；
 * 错误页上计时器不武装——否则 5 秒后焦点被从“重试”拉回根部。
 * 旧版守卫没有错误页一项：错误出现不会取消已在跑的延时，到点仍抢焦点。
 */
internal fun shouldAutoHideControls(
    controlsVisible: Boolean,
    drawerOpen: Boolean,
    hasPendingPreview: Boolean,
    showNextEpisode: Boolean,
    hasError: Boolean,
): Boolean =
    controlsVisible && !drawerOpen && !hasPendingPreview && !showNextEpisode && !hasError

/**
 * 落点预览 2 秒无操作是否应自动提交（可单测，P2-3）：
 * 有待定落点且抽屉关闭时才提交；抽屉打开时不提交——否则挑集时视频
 * 在抽屉后跳进度。打开抽屉时待定落点另由抽屉 effect 直接取消（同款处理），
 * 这里是兜底（落点在抽屉打开期间才产生时，关抽屉后才起定时器）。
 */
internal fun shouldAutoCommitPreview(previewTargetMs: Long, drawerOpen: Boolean): Boolean =
    previewTargetMs >= 0 && !drawerOpen

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
    LaunchedEffect(focusRestoreTick) {
        if (focusRestoreTick > 0) rootRequester.requestFocus()
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
    // 第 6 轮 P2-7：单次 requestFocus 改 requestFocusWithRetry（与抽屉/面板一致）——
    // 错误页首帧组合未完成时单次请求静默失败，焦点停在根部；仍失败回退到根。
    var hadError by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            hadError = true
            // C8-P2-3：错误在抽屉打开时出现不直接落焦——“重试”按钮在遮罩下，
            // 焦点会被抢过去；先关抽屉，抽屉关闭 effect 再把焦点落到“重试”。
            if (uiState.drawer != null) {
                errorPendingAfterDrawerClose = true
                viewModel.openDrawer(null)
            } else if (!errorRetryRequester.requestFocusWithRetry()) {
                rootRequester.requestFocus()
            }
        } else if (hadError) {
            hadError = false
            errorPendingAfterDrawerClose = false
            if (!controlsFirstButtonRequester.requestFocus()) rootRequester.requestFocus()
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
            rootRequester.requestFocus()
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
            if (errorPendingAfterDrawerClose && uiState.error != null) {
                errorPendingAfterDrawerClose = false
                if (!errorRetryRequester.requestFocusWithRetry()) {
                    rootRequester.requestFocus()
                }
            } else if (!controlsFirstButtonRequester.requestFocusWithRetry()) {
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

    // 控制条 5 秒无操作自动隐藏（抽屉打开、有待定落点、下一集预告或错误页时不隐藏）；
    // 隐藏后焦点回根，方向键/OK 才能继续唤出控制条（P1-9）。
    // A6-P1-4 / 第 6 轮 P2-2：key 加 error——错误页出现后若定时器已在跑，
    // 旧协程会被取消，不会在 5 秒到点时把焦点从“重试”按钮抢回根；
    // 到点后也再复核 error==null（双保险）。
    LaunchedEffect(controlsTick, uiState.drawer, uiState.showNextEpisode, uiState.error) {
        if (shouldAutoHideControls(
                controlsVisible,
                uiState.drawer != null,
                previewTargetMs >= 0,
                uiState.showNextEpisode,
                uiState.error != null,
            )
        ) {
            delay(5000)
            if (uiState.error == null) {
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
    LaunchedEffect(previewTargetMs, uiState.drawer) {
        if (shouldAutoCommitPreview(previewTargetMs, uiState.drawer != null)) {
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
                    Key.Back -> {
                        when {
                            previewTargetMs >= 0 -> {
                                previewController.cancel()
                                previewTargetMs = -1L
                                previewDirection = 0
                                previewThumbnail = null
                                // 错误页上不 poke（P2-2）；取消预览本身不受影响。
                                if (uiState.error == null) pokeControls()
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
                                // A9-P1-4：焦点在“取消”按钮上时按返回，卡片卸载后
                                // 焦点回根（与点“取消”同款；effect 兜底见上）。
                                if (nextEpisodeFocused) {
                                    nextEpisodeFocused = false
                                    rootRequester.requestFocus()
                                }
                                true
                            }
                            // A6-P2-1：错误页显示时按返回直接退出播放页——旧实现
                            // 落到 controlsVisible 分支只隐藏控制条（错误页下
                            // 控制条本来就不显示），第一次按返回无可见变化。
                            uiState.error != null -> {
                                onBack()
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
                        } else if (commitPreview()) {
                            true
                        } else if (!controlsVisible) {
                            // 控制条隐藏时 OK 只唤出，不暂停；错误页上不 poke（P2-2）。
                            if (uiState.error == null) pokeControls()
                            true
                        } else {
                            pokeControls()
                            viewModel.togglePlayPause()
                            true
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
                    if (nextEpisodeFocused) {
                        nextEpisodeFocused = false
                        rootRequester.requestFocus()
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
    /** C8-P1-2：焦点项在左缘/底缘时上报，供根部预览拦截做抽屉 containment。 */
    onFocusEdgeChange: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    val currentRequester = remember { FocusRequester() }
    val groups = DetailSearchLogic.episodeGroups(totalEpisodes)
    var selectedGroup by remember(totalEpisodes) {
        mutableStateOf((currentEpisode / 20).coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    }
    val (groupStart, groupEnd) = groups.getOrNull(selectedGroup) ?: (1 to totalEpisodes)
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
    // 第 6 轮 P1-3：分组行改横向滚动——6 组起超出抽屉宽会被 clip 裁掉、不可见
    // 却仍可获焦（焦点视觉上“消失”）；LazyRow 让超出的 chip 能滚到、能获焦。
    // 当前集在第 6 组及之后时，打开即滚到选中分组，不让它落在可视区外。
    val groupListState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (groups.size > 1) {
            groupListState.scrollToItem(selectedGroup.coerceIn(0, groups.size - 1))
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
                    // C8-P1-2：分组行左缘是首个 chip；下键回到选集网格，不算底缘。
                    val (groupLeftEdge, _) = drawerListFocusEdges(g, groups.size)
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
) {
    val currentRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
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
) {
    val currentRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentRequester.requestFocus() }
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
