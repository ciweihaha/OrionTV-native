package com.oriontv.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.core.util.AppLog
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.play.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 直播 ViewModel（ui-design.md §5.4，plan §5.3）：
 * M3U 拉取/缓存/恢复上次频道、EPG 拉取/缓存（“仍有正在播节目”判据）、
 * 上/下换台、左/右切线路、数字键换台、收藏分组/频道、断流自动切备用线路或下一台（>5 次停手）、
 * 换台信息条 3 秒、频道面板 15 秒无操作自动关闭、EPG 全天节目单弹窗。
 * 换台/切线路/断流/数字键的纯逻辑在 [LiveController]，这里只做状态机与播放器接线。
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val m3uRepository: M3uRepository,
    private val epgRepository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    private val playerManager: PlayerManager,
    private val json: Json,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    /** 播放器实例（供 LiveScreen 的 PlayerView 用；单例，点播与直播共用）。 */
    val player: ExoPlayer get() = playerManager.player

    private val controller = LiveController()
    private var initialised = false
    private var playingConfirmed = false
    /** 本页认领的播放代际（onCleared 只停自己那一代，不停点播页的播放）。 */
    private var playbackToken: Long = 0L
    private var lastPlayedUrl: String? = null
    private var readyTimeoutJob: Job? = null
    private var infoBarJob: Job? = null
    private var toastJob: Job? = null
    private var panelTimerJob: Job? = null
    private var digitCommitJob: Job? = null
    /** 稳定播出确认任务（P1-6）：播出满 10 秒才调 confirmStablePlayback 清零失败计数。 */
    private var stablePlayJob: Job? = null
    private var tickerJob: Job? = null
    private var epgRefreshJob: Job? = null
    /** A6-P2-5：“刷新直播源”在途任务——连点时第二次直接返回，不再并发拉网、
     * 两次 setChannels 交错写表。 */
    private var refreshJob: Job? = null
    /** A9-P2-5：init() 在途任务——reload() 的守卫（快速双击失败页“重试”
     * 不再并发跑两路 M3U 加载、两次 playCurrent()，与 refreshJob 同款）。 */
    private var initJob: Job? = null
    /** A9-P1-1：页面是否在前台（onPagePaused/onPageResumed 维护；应用内导航
     * 切走不经过 Activity 生命周期，只看 isForeground 不够）。playCurrent()
     * 开播前复核它，后台的新开播暂缓、回前台时 onPageResumed() 补开播。 */
    @Volatile
    private var pageActive: Boolean = true
    /** A9-P1-1：本页是否已开播过一次——后台继续播只允许已开播过的延续
     *（PiP 内断流恢复、开关开着的继续播）；从未开播过时后台开播即无窗口
     * 后台音频（开关开）或违背开关语义（开关关）。 */
    private var playbackStarted: Boolean = false
    /** 退出时读设置用：onCleared 时 viewModelScope 已取消，不能用它读库。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 是否已离页（第 7 轮 P1-1）：onPagePaused 置真、onPageResumed 置假；待定播放
     * 任务（就绪超时/数字键提交/面板定时器）到点时凭它二次核对——取消是协作式的，
     * 已越过 delay 的任务仍会恢复执行，不能只靠取消。 */
    @Volatile
    private var pagePaused: Boolean = false

    /** 是否在前台（LiveScreen 的 ON_RESUME/ON_PAUSE 观察者维护；时钟门控见 startTicker，P2-9）。 */
    @Volatile
    var isForeground: Boolean = true
        private set

    /** 前台状态变化（供 LiveScreen 的生命周期观察者调用）。 */
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A4-P2-9：归属校验——点播页在前台时，直播页的监听器还挂着，
            // 点播的播放状态不能清掉直播的失败计数/稳定窗口。
            if (!playerManager.isCurrentOwner(playbackToken)) return
            if (isPlaying) {
                playingConfirmed = true
                readyTimeoutJob?.cancel()
                controller.onPlaybackSuccess()
                _uiState.update {
                    it.copy(isPlaying = true, isBuffering = false, playbackFailed = false, signalError = false)
                }
                // 失败计数不立即清零：只有连续稳定播出满 10 秒才清（P1-6），
                // 否则短暂播出 1 秒就断流的台会永远绕开“>5 次停手”。
                armStablePlaybackConfirm()
            } else {
                stablePlayJob?.cancel()
                _uiState.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A4-P2-9：同上，不是自己的播放不改直播的缓冲态。
            if (!playerManager.isCurrentOwner(playbackToken)) return
            _uiState.update {
                it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
            }
        }
    }

    init {
        playerManager.addListener(playerListener)
    }

    /** 进页加载一次：收藏 → 上次频道 → M3U（缓存优先）→ 恢复并开播 → EPG（缓存优先）。 */
    fun init() {
        if (initialised) return
        initialised = true
        // A5-P2-9：init() 里的 claim 已删——playCurrent() 开播时自会认领，
        // 这里先认领一次纯属多余（首个代际从未播过任何内容就被下一代取代）。
        // 后台播放开关读入 PlayerManager：MainActivity.onUserLeaveHint 靠它决定是否进画中画；
        // 之前只有点播页读这个开关，直播页按 Home 从不进 PiP、回首页必停播。
        viewModelScope.launch {
            // B9-P2-1：DataStore 读失败不让协程未捕获崩溃；失败即保持现有值。
            runCatching { settingsRepository.currentBackgroundPlayback() }
                .onSuccess { playerManager.backgroundPlaybackEnabled = it }
                .onFailure {
                    // B17-P2-1：取消异常原样重抛，不当成普通读失败沿用旧值。
                    if (it is CancellationException) throw it
                    AppLog.w("LiveViewModel", "后台播放开关读入失败，沿用现有值：${it.message}")
                }
        }
        initJob = viewModelScope.launch {
            val favJson = runCatching { settingsRepository.currentLiveFavChannelsJson() }
                // B17-P2-1：取消异常原样重抛，不当成读失败回落空串。
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault("")
            val favorites = runCatching {
                json.decodeFromString<List<LiveFavChannel>>(favJson)
            }.getOrDefault(emptyList())
            controller.setFavorites(favorites)
            val lastJson = runCatching { settingsRepository.currentLiveLastChannelJson() }
                // B17-P2-1：取消异常原样重抛，不当成读失败回落空串。
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault("")
            val lastChannel = runCatching {
                json.decodeFromString<LiveLastChannel>(lastJson)
            }.getOrNull()

            val m3uUrl = runCatching { settingsRepository.currentM3uUrl() }
                // B17-P2-1：取消异常原样重抛，不当成读失败回落空串。
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault("")
            if (m3uUrl.isBlank()) {
                // A9-P1-3：未配地址不置 initialised——否则 onPageResumed() 遇频道表
                // 为空直接早退，用户在设置里填好 M3U 回来永远停在空态，只能杀进程。
                initialised = false
                _uiState.update {
                    it.copy(isLoading = false, m3uConfigured = false, favorites = favorites)
                }
                return@launch
            }
            _uiState.update { it.copy(m3uConfigured = true, favorites = favorites) }
            val channels = m3uRepository.loadChannels(m3uUrl)
            if (channels.isEmpty()) {
                // 第 10 轮 L10-P2-2（D9-P2-8 残口）：空表不再恒当失败——
                // 仓库层已区分“拉取失败”与“成功但空表”，调用点也要分：
                // loadFailed 为真才是加载失败；否则是源本身 0 个频道，
                // 置 emptySource 态（EmptyState 另分支提示），不误报失败。
                val failed = m3uRepository.loadFailed.value
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        emptySource = !failed,
                        loadError = if (failed) "直播源加载失败，请检查 M3U 地址或网络后重试" else null,
                    )
                }
                return@launch
            }
            controller.setChannels(channels, restoreUrl = lastChannel?.url, restoreName = lastChannel?.name)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    channels = channels,
                    currentIndex = controller.currentIndex,
                    lineIndex = controller.lineIndex,
                    focusedChannelIndex = controller.currentIndex,
                    // 第 10 轮 L10-P2-2：成功加载即清掉旧的空源/失败态——
                    // 之前某次空源后源里又有了频道，不清会一直挂空态文案。
                    emptySource = false,
                    loadError = null,
                )
            }
            playCurrent()
            loadEpg()
            // A15-P2-2：首启 EPG 拉取失败也要轻提示（与 10 分钟 ticker 刷新
            // 同款 epgRefreshFailedMessage 文案）：loadEpg() 失败回落旧缓存、
            // state.epg 非空即仍在用旧节目单，有旧缓存时不误报成“无节目单”；
            // 无旧缓存时只说刷新失败，与“该台真无节目单”可区分（后者不弹提示）。
            epgRefreshFailedMessage(
                epgRepository.loadFailed.value,
                _uiState.value.epg != null,
            )?.let { showToast(it) }
            startTicker()
        }
    }

    /** 手动重试加载（空态/失败页的“重试”按钮）。 */
    fun reload() {
        // A9-P2-5：在途守卫——上一路加载还没跑完时直接返回，不并发跑两路
        // M3U 加载、两次 playCurrent()（与 refreshJob 同款）。
        if (initJob?.isActive == true) return
        // 第 7 轮 P2-13：重载先取消旧定时任务（防御性）——当前仅空态可达，
        // 定时器无从武装；若将来 reload 在有频道时可达，即与 P1-1 同类泄漏。
        cancelPendingPlaybackJobs()
        // A4-P2-4：重载先停掉旧播放——否则失败时旧台音频继续播、UI 却显示加载失败。
        // 第 6 轮 P2-9：无归属的 stop() 改 stopIfOwner——本页没认领过播放（token 0）
        // 时不碰别人的播放（例如后台播放开着时点播页正在播）；只停自己那一代。
        playerManager.stopIfOwner(playbackToken)
        initialised = false
        _uiState.update { LiveUiState(favorites = _uiState.value.favorites) }
        init()
    }

    /** 手动刷新直播源（A4-P2-3）：7 天缓存内无刷新入口时强制拉网。 */
    fun refreshChannels() {
        // A6-P2-5：在途守卫——上一次刷新还没跑完时直接返回，不并发拉网。
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val m3uUrl = runCatching { settingsRepository.currentM3uUrl() }
                // B17-P2-1：取消异常原样重抛，不当成读失败回落空串。
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault("")
            if (m3uUrl.isBlank()) return@launch
            val wasFailed = _uiState.value.playbackFailed
            val fresh = m3uRepository.loadChannels(m3uUrl, forceRefresh = true)
            // A8-P1-2：拉网失败时 loadChannels 回落旧缓存、返回非空旧表，
            // 只判空就当刷新成功、静默无事发生；loadFailed 标记是唯一的失败信号。
            // 第 10 轮 L10-P2-2（D9-P2-8 残口）：空表不再恒当失败——合法空源
            // （成功拉取、0 个频道）不再误报“刷新失败”；失败只看 loadFailed。
            if (shouldReportRefreshFailed(m3uRepository.loadFailed.value)) {
                showToast("刷新失败，仍用旧频道表")
                return@launch
            }
            val current = controller.currentChannel
            // A6-P2-7：传正在播的那条线（controller.currentUrl），不是
            // current?.url（恒为 urls[0]）——正在播 2 号线时刷新后应回 2 号线。
            controller.setChannels(fresh, restoreUrl = controller.currentUrl, restoreName = current?.name)
            // 第 7 轮 P2-12：EPG 弹窗持的旧频道对象在新表里已不存在——按新表
            // 重建（标题带台号、左右切台按新列表走）；旧对象继续持着，
            // indexOfChannel 按身份/（url, name）都匹配不到，标题无台号、切台失效。
            // 弹窗没开时为 null，不动。
            val dialogChannel = _uiState.value.epgDialogChannel
            val resolvedDialogChannel = dialogChannel?.let {
                resolveRefreshedEpgChannel(fresh, it, controller.currentChannel)
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadError = null,
                    channels = fresh,
                    currentIndex = controller.currentIndex,
                    lineIndex = controller.lineIndex,
                    focusedChannelIndex = controller.currentIndex,
                    epgDialogChannel = resolvedDialogChannel,
                    // 第 10 轮 L10-P2-2：合法空源刷新成功即置空源态（EmptyState
                    // 另分支提示），不滞留旧的失败文案；非空即清掉旧空源态。
                    emptySource = fresh.isEmpty(),
                    // A11-P2-3：频道表版本号 +1——面板打开时刷新若重排了频道表，
                    // 焦点所在的旧行可能已被卸载/换位；面板落焦 effect 的 key
                    // 只有 (panelOpen, selectedGroup)，表变化时不重跑、焦点丢失
                    // 且无恢复。版本号进 effect key，刷新后重落当前台行。
                    channelsVersion = it.channelsVersion + 1,
                )
            }
            // 第 10 轮 L10-P2-2：合法空源刷新成功后，正在播的旧台已不在
            // 源里——停掉它，不留 EmptyState 盖着屏幕、旧台音频仍在播的
            // 不一致态（失败刷新才保留旧表不断流，成功空源不适用）。
            if (fresh.isEmpty()) {
                playerManager.stopIfOwner(playbackToken)
            }
            // 当前台还在新表里且之前没失败过：不断流，只更新表；否则重开播。
            // 空源时上面已停播，playCurrent 取不到 url 会直接返回，不再调。
            if (fresh.isNotEmpty() && (controller.currentUrl != lastPlayedUrl || wasFailed)) playCurrent()
        }
    }

    private suspend fun loadEpg() {
        val epg = epgRepository.loadEpg(System.currentTimeMillis())
        if (epg != null) {
            _uiState.update { it.copy(epg = epg) }
        }
    }

    /** 时钟 30 秒一跳（驱动面板右上角时钟与节目进度条刷新）；EPG 每 10 分钟后台刷新一次。 */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                // P2-9 前台门控：切后台时不刷时钟 UI；恢复前台后下一跳继续。
                if (!shouldRunTicker(isForeground)) continue
                _uiState.update { it.copy(nowMs = System.currentTimeMillis()) }
            }
        }
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch {
            while (true) {
                delay(10 * 60_000)
                // P2-9 前台门控：切后台时不拉 EPG；直播本身不靠这个循环推进，
                // 只停 UI 时钟与后台刷新，与后台播放开关无关。
                if (!shouldRunTicker(isForeground)) continue
                val now = System.currentTimeMillis()
                // 缓存里已无正在播的节目才值得重拉（天光 v2.2.20 教训的反向运用）。
                val epg = _uiState.value.epg
                val stale = epg == null ||
                    epg.programmesByChannel.values.none { list -> list.any { it.endAt > now } }
                if (stale) {
                    // 第 7 轮跟进 D7-P2-2：EPG 后台刷新失败时轻提示（读
                    // epgRepository.loadFailed，与 M3U 刷新失败提示同款文案风格）；
                    // loadEpg() 失败时回落旧缓存，state.epg 非空即仍在用旧节目单。
                    // A16-P2-3：首启加载与 10 分钟 ticker 刷新都走这里；初始空态仍另有 loadError。
                    loadEpg()
                    epgRefreshFailedMessage(
                        epgRepository.loadFailed.value,
                        _uiState.value.epg != null,
                    )?.let { showToast(it) }
                }
            }
        }
    }

    // ---- 播放 ----

    /** 开播当前台/线路：直播 15 秒就绪超时（plan §5.5），超时按断流处理。 */
    private fun playCurrent() {
        // A9-P1-1：开播前复核页面仍在前台——加载期间按 Home 后，init 完成、
        // 数字键 1.5 秒提交定时器到点、刷新完成都可能在后台调到这里；后台的
        // 新开播暂缓（不认领、不碰播放器），回前台时 onPageResumed() 补开播；
        // 已开播过的延续（PiP 内断流恢复、开关开着的继续播）不受影响。
        if (!shouldStartPlayback(pageActive, playerManager.backgroundPlaybackEnabled, playbackStarted)) return
        // A4-P2-9：每次开播重新认领播放代际（错误/状态回调核对后再处理）。
        playbackToken = playerManager.claimPlayback()
        // 换台即开启新的稳定窗口：旧台的稳定确认任务不再有效（P1-6）。
        stablePlayJob?.cancel()
        val url = controller.currentUrl ?: return
        playingConfirmed = false
        // A12-P2-1：回页时同台仍在缓冲——直接 play() 续播，不 stop()+playUrl()：
        // stop() 内 clearMediaItems() 会丢掉已缓冲的分片，白白从头重缓；
        // play() 只置 playWhenReady（缓冲中本来就已是 true，基本是空操作），
        // 已缓冲的数据原地接上。只认“同 URL 且正在缓冲”：已暂停就绪态 /
        // 未加载 / 换台仍走下面现有重播（回看语义不变）；播放器里挂的不是
        // 同一条 URL 时 currentUrl 对不上，同样走重播，保守、不误判。
        // 就绪超时照常重 arm（下面共用尾巴）：缓冲继续走、要么播出来、要么
        // 超时进断流链，不借反复切后台给死流续命。
        playerManager.setPlaybackSpeed(LIVE_PLAYBACK_SPEED)
        if (shouldResumeBuffering(url, lastPlayedUrl, playerManager.currentUrl, playerManager.isBuffering)) {
            playerManager.play()
        } else {
            // 同 URL 重播（OK 重试当前台）：先停掉旧 item 才能真正重挂播放器。
            if (url == lastPlayedUrl) playerManager.stop()
            lastPlayedUrl = url
            playerManager.playUrl(url)
        }
        playbackStarted = true
        _uiState.update {
            it.copy(
                currentIndex = controller.currentIndex,
                lineIndex = controller.lineIndex,
                isPlaying = false,
                isBuffering = true,
                playbackFailed = false,
                signalError = false,
            )
        }
        showInfoBar()
        readyTimeoutJob?.cancel()
        readyTimeoutJob = viewModelScope.launch {
            delay(LIVE_READY_TIMEOUT_MS)
            // 第 7 轮 P1-1：离页后到点也不处理——取消是协作式的，已越过 delay
            // 的任务仍会恢复执行，凭 pagePaused 二次核对，不在别的页面上恢复音频。
            if (shouldFirePendingPlaybackTask(pagePaused) && !playingConfirmed) handlePlaybackError()
        }
    }

    /** 断流：按 LiveController 的升级链处理（备用线路 → 下一台 → >5 次停手）。 */
    private fun handlePlaybackError() {
        // A4-P2-9：归属校验——点播页在前台时，直播页的监听器还挂着，
        // 点播的错误不能触发直播切台（会抢走点播的播放器）。
        if (!playerManager.isCurrentOwner(playbackToken)) return
        // 第 8 轮 P2-3：离页后播放器错误回调不推进失败计数、不弹 Toast——
        // playCurrent 的 shouldStartPlayback 门控已拦住重播（无音频泄漏），
        // 但门控之前的账已算了（controller.onPlaybackError 推进 failCount/
        // lineIndex/currentIndex，TryLine/NextChannel 还弹“正在尝试备用线路”
        // Toast、GiveUp 还置 playbackFailed）；后台播放开着时按“已开播过的
        // 延续”放行（与 playCurrent 同谓词），回页时 onPageResumed 按
        // shouldResumePlayback 重开播当前台，不丢恢复。
        if (!shouldHandlePlaybackError(pageActive, playerManager.backgroundPlaybackEnabled, playbackStarted)) return
        // 断流即作废稳定窗口：稳定确认任务不再跑（P1-6）。
        stablePlayJob?.cancel()
        val state = _uiState.value
        if (state.channels.isEmpty()) return
        when (val action = controller.onPlaybackError(state.panelOpen)) {
            is LiveErrorAction.TryLine -> {
                _uiState.update { it.copy(failCount = controller.failCount) }
                showToast(action.message)
                playCurrent()
            }
            is LiveErrorAction.NextChannel -> {
                saveLastChannel()
                _uiState.update {
                    it.copy(
                        currentIndex = controller.currentIndex,
                        lineIndex = controller.lineIndex,
                        failCount = controller.failCount,
                    )
                }
                showToast(action.message)
                playCurrent()
            }
            is LiveErrorAction.GiveUp -> {
                // 终端分支：就绪超时已无意义，取消它，免得 15 秒后多算一次失败并重复 Toast。
                readyTimeoutJob?.cancel()
                // C9-P2-1：失败页出现时若面板开着先关面板（再落焦点到“重试”）——
                // 之前面板留在失败页底下，失败页的重试焦点与面板焦点打架；
                // 与 C8-P2-3（播放页错误关抽屉）同款。
                panelTimerJob?.cancel()
                _uiState.update {
                    it.copy(
                        failCount = controller.failCount,
                        playbackFailed = true,
                        isBuffering = false,
                        panelOpen = false,
                        epgDialogChannel = null,
                    )
                }
                showToast(action.message)
            }
            is LiveErrorAction.NotifyOnly -> {
                // 面板打开时只提示不跳台：同样取消就绪超时；并在面板内留常驻“信号异常”提示，
                // 不只靠 3 秒 Toast（播好后由 onIsPlayingChanged 清掉）。
                readyTimeoutJob?.cancel()
                _uiState.update { it.copy(failCount = controller.failCount, signalError = true) }
                showToast(action.message)
            }
        }
    }

    /** 播出 10 秒后确认是否稳定：稳定才清零失败计数（P1-6，见 LiveController.confirmStablePlayback）。 */
    private fun armStablePlaybackConfirm() {
        stablePlayJob?.cancel()
        stablePlayJob = viewModelScope.launch {
            delay(LiveController.STABLE_PLAYBACK_MS)
            // A5-P2-1：回调里复核归属——10 秒内切到点播页开播后，本页的旧
            // 确认任务不能清掉点播页的失败计数（归属已易主时直接作废）。
            if (!playerManager.isCurrentOwner(playbackToken)) return@launch
            if (!playerManager.isPlaying) return@launch
            if (controller.confirmStablePlayback(System.currentTimeMillis())) {
                _uiState.update { it.copy(failCount = 0) }
            }
        }
    }

    /**
     * 离页（A5-P1-2/A5-P2-10，供 LiveScreen 的 ON_PAUSE 与 onDispose 调用）：
     * 后台播放开关关着时暂停本页的播放（导航轨切走直播页也一样，之前音频
     * 继续播）；时钟与 EPG 刷新定时器随页停掉，切走后不再后台空转。
     * 开关开着时不暂停（画中画/后台音频继续），但定时器同样停——回页时重起。
     * 第 7 轮 P1-1：待定播放任务（就绪超时/数字键提交/面板定时器）一并取消——
     * 旧实现只停 ticker/EPG，15 秒就绪超时、数字键提交、面板定时器到点仍会
     * playCurrent()，音频泄漏到别的页面；到点回调另凭 pagePaused 二次核对。
     */
    fun onPagePaused() {
        pageActive = false
        pagePaused = true
        if (!playerManager.backgroundPlaybackEnabled) {
            playerManager.pauseIfOwner(playbackToken)
        }
        tickerJob?.cancel()
        epgRefreshJob?.cancel()
        // A9-P1-1 + 第 7 轮 P1-1：离页即取消待定播放任务——就绪超时/数字键提交/
        // 面板定时器到点都会调 playCurrent()，不取消会在后台切台开播
        //（cancelPendingPlaybackJobs 含 cancelDigitCommit，与
        // afterManualChannelChange/changeLine/openPanel 的取消同款）。
        cancelPendingPlaybackJobs()
    }

    /**
     * 回页（A5-P1-1，供 LiveScreen 的 ON_RESUME 与应用内导航返回调用）：
     * ViewModel 因 saveState 存活、init() 早退，而播放器已被点播页停掉/抢走，
     * 旧实现直接黑屏。定时器重起；当前不是本页 token 所有者或未在播时，
     * 重新认领并开播当前台。
     * 第 6 轮 P1-4：之前只挂在 Activity ON_RESUME 上——应用内导航（导航轨
     * 切走再回来）不经过 Activity 生命周期，ON_RESUME 不触发；现在
     * LiveScreen 的 LaunchedEffect(Unit) 在每次重进组合时也调这里
     * （init() 早退分支里不调，保持 init() 只负责首启加载）。
     */
    fun onPageResumed() {
        pageActive = true
        // 第 7 轮 P1-1：先清离页标志再早退——首启时频道表还是空的，init() 的
        // 加载完成后才开播、才武装就绪超时；若标志不清，那个超时会被误拦。
        pagePaused = false
        if (_uiState.value.channels.isEmpty()) {
            // A9-P1-3：频道表为空时重走 init()——首装未配 M3U 时 init() 早退后
            // initialised 已复位（见 init 的未配分支），用户填好地址回来能自动加载；
            // 加载失败过（initialised 仍 true）时不自动重拉，失败页“重试”仍是入口。
            if (!initialised) init()
            return
        }
        startTicker()
        if (!shouldResumePlayback(
                playerManager.isCurrentOwner(playbackToken),
                playerManager.isPlaying,
                // A10-P2-2：失败页切走再回不自动重播——失败时播放器不在播，
                // 旧谓词恒为真，回页即悄悄重播一次；留给失败页“重试”按钮。
                _uiState.value.playbackFailed,
            )
        ) {
            return
        }
        playCurrent()
    }

    /** OK 重试当前台（失败过且未耗尽自动跳台上限时，OK 不开面板、改重试）。 */
    fun retryCurrent() {
        // 第 6 轮 P2-5：重试也取消数字键提交定时器（与 afterManualChannelChange /
        // changeLine / openPanel 对齐）——否则失败页上按数字键、1.5 秒内点重试，
        // 定时器到点仍会把刚重试的台切到数字键那个台。
        cancelDigitCommit()
        controller.resetFailures()
        _uiState.update { it.copy(failCount = 0, playbackFailed = false) }
        playCurrent()
    }

    // ---- 换台 / 切线路 ----

    fun changeChannel(delta: Int) {
        val state = _uiState.value
        if (state.channels.isEmpty()) return
        if (delta < 0) controller.prevChannel() else controller.nextChannel()
        afterManualChannelChange()
    }

    fun selectChannel(index: Int) {
        val state = _uiState.value
        if (index !in state.channels.indices) return
        // A9-P2-3：点当前台只关面板、不重播——之前经 afterManualChannelChange →
        // playCurrent() 无条件重开，同 URL 还先 stop() 再 playUrl()，从头重播。
        // closePanel 的信号异常重播（P2-13）保留：信号断着时点当前台是恢复意图。
        if (index == controller.currentIndex) {
            closePanel()
            return
        }
        controller.selectChannel(index)
        afterManualChannelChange(closePanel = true)
    }

    private fun afterManualChannelChange(closePanel: Boolean = false) {
        // P2-2：任何手动换台都取消数字键提交定时器并清缓冲——否则 1.5 秒内
        // 按数字键再按上/下换台，定时器到点仍会把频道切回数字键那个台。
        cancelDigitCommit()
        saveLastChannel()
        _uiState.update {
            it.copy(
                currentIndex = controller.currentIndex,
                lineIndex = controller.lineIndex,
                focusedChannelIndex = controller.currentIndex,
                failCount = controller.failCount,
                // 手动换台即清失败遮罩（P1-5，与 signalError 同款处理；playCurrent 里也有，
                // 这里再清一次是为覆盖 playCurrent 因无 URL 提前返回的路径）。
                playbackFailed = false,
                panelOpen = if (closePanel) false else it.panelOpen,
                epgDialogChannel = if (closePanel) null else it.epgDialogChannel,
            )
        }
        if (closePanel) panelTimerJob?.cancel()
        playCurrent()
    }

    fun changeLine(delta: Int) {
        val state = _uiState.value
        if (state.channels.isEmpty()) return
        if (!controller.changeLine(delta)) return // 单线路频道无动作
        // P2-2：手动切线路同样取消数字键提交定时器（同换台）。
        cancelDigitCommit()
        // A7-P1-2：切线路也要保存上次频道——之前只在换台时保存，重启后
        // 永远回 1 号线；保存时存正在播的线路（见 saveLastChannel）。
        saveLastChannel()
        _uiState.update {
            it.copy(lineIndex = controller.lineIndex, failCount = controller.failCount)
        }
        playCurrent()
    }

    private fun saveLastChannel() {
        val channel = controller.currentChannel ?: return
        // A7-P1-2：存正在播的那条线（controller.currentUrl），不是 channel.url
        //（恒为 urls[0]）——正在播 2 号线时重启后应回 2 号线。
        val last = LiveLastChannel(url = controller.currentUrl ?: channel.url, name = channel.name)
        viewModelScope.launch {
            runCatching {
                settingsRepository.setLiveLastChannelJson(json.encodeToString(last))
            }
                // B17-P2-1：附属写失败不推翻主流程，但取消异常原样重抛。
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    // ---- 数字键换台 ----

    /** 按一位数字键；唯一匹配立即切台，否则显示缓冲并起 1500ms 提交定时器。 */
    fun appendDigit(digit: Char) {
        val state = _uiState.value
        if (state.channels.isEmpty()) return
        when (val action = controller.appendDigit(digit)) {
            is DigitAction.Buffer -> {
                _uiState.update { it.copy(digitBuffer = action.text) }
                // 第 9 轮 P2-6：缓冲分支不再发 Toast——LiveScreen 按 digitBuffer
                // 常驻显示回显（随缓冲清掉而消失）；再发 Toast 会与回显在同一
                // 位置叠出两条相同文案，且 Toast 3 秒比缓冲 1500ms 多活一倍。
                armDigitCommit()
            }
            is DigitAction.SwitchTo -> {
                _uiState.update { it.copy(digitBuffer = "") }
                digitCommitJob?.cancel()
                selectChannel(action.index)
            }
            else -> Unit
        }
    }

    private fun armDigitCommit() {
        digitCommitJob?.cancel()
        digitCommitJob = viewModelScope.launch {
            delay(LiveController.DIGIT_COMMIT_MS)
            // 第 7 轮 P1-1：离页后到点也不提交换台（同就绪超时，取消后仍可能
            // 已在恢复执行，凭 pagePaused 二次核对）。
            if (!shouldFirePendingPlaybackTask(pagePaused)) return@launch
            when (val action = controller.commitDigits()) {
                is DigitAction.SwitchTo -> {
                    _uiState.update { it.copy(digitBuffer = "") }
                    selectChannel(action.index)
                }
                is DigitAction.Invalid -> {
                    _uiState.update { it.copy(digitBuffer = "") }
                    showToast("无 ${action.number} 号频道")
                    // 第 8 轮 P1-1（A10-P2-3 的回归）：数字键路径关面板时跳过信号重播，
                    // 前提是随后数字键切台又调一次 playCurrent()；台号无效时根本没有
                    // 切台，就绪超时已取消、面板定时器已取消，播放器会静置在无提示的
                    // 断流态（signalError 的常驻提示随面板关闭一起消失）。无效台号时
                    // 按关面板原语义补一次重播（与 shouldReplaySignalOnClosePanel
                    // 同谓词、skip=false），让就绪超时/自动换台链重新接管。
                    if (shouldReplaySignalAfterInvalidDigit(
                            _uiState.value.signalError,
                            _uiState.value.isPlaying,
                            _uiState.value.channels.isNotEmpty(),
                            pagePaused,
                        )
                    ) {
                        playCurrent()
                    }
                }
                else -> _uiState.update { it.copy(digitBuffer = "") }
            }
        }
    }

    /**
     * 取消数字键提交定时器并清缓冲（P2-2）：手动换台/切线路/开面板时调用，
     * 否则 1500ms 定时器到点会把频道切回数字键缓冲的那个台。
     * 注意：提交任务自身调 selectChannel 时也会走到这里（自取消），
     * 取消是协作式的，其后无挂起点，不影响本次提交。
     */
    private fun cancelDigitCommit() {
        digitCommitJob?.cancel()
        digitCommitJob = null
        controller.clearDigitBuffer()
        _uiState.update { it.copy(digitBuffer = "") }
    }

    /**
     * 取消全部待定播放任务（第 7 轮 P1-1/P2-13）：就绪超时、数字键提交、
     * 面板定时器到点都会调 playCurrent()；离页（onPagePaused）与重载
     * （reload）时不取消，超时后会在别的页面上恢复音频、或与重载后的
     * 新任务交错。数字键提交走 cancelDigitCommit，缓冲一并清掉。
     */
    private fun cancelPendingPlaybackJobs() {
        readyTimeoutJob?.cancel()
        cancelDigitCommit()
        panelTimerJob?.cancel()
    }

    // ---- 收藏 ----

    /** 菜单键收藏/取消收藏当前台。 */
    fun toggleFavoriteCurrent() {
        val channel = controller.currentChannel ?: return
        toggleFavorite(channel)
    }

    /** 收藏/取消收藏指定台（面板长按 OK 用）。 */
    fun toggleFavorite(channel: M3uParser.Channel) {
        val added = controller.toggleFavorite(channel)
        val favorites = controller.favorites
        _uiState.update { it.copy(favorites = favorites) }
        showToast("${channel.name}  ${if (added) "★ 已收藏" else "已取消收藏"}")
        viewModelScope.launch {
            runCatching {
                settingsRepository.setLiveFavChannelsJson(json.encodeToString(favorites))
            }
                // B17-P2-1：附属写失败不推翻主流程，但取消异常原样重抛。
                .onFailure { if (it is CancellationException) throw it }
        }
        pokePanelTimer()
    }

    fun isFavorite(channel: M3uParser.Channel): Boolean = controller.isFavorite(channel)

    // ---- 分组 / 面板 ----

    fun groups(): List<String> = controller.groups()

    fun channelsForGroup(group: String): List<M3uParser.Channel> =
        controller.channelsForGroup(group)

    fun selectGroup(group: String) {
        _uiState.update { it.copy(selectedGroup = group) }
        pokePanelTimer()
    }

    fun openPanel() {
        // P2-2：开面板挑台时也取消数字键提交定时器，免得 1.5 秒后误切台。
        cancelDigitCommit()
        _uiState.update {
            it.copy(
                panelOpen = true,
                focusedChannelIndex = controller.currentIndex,
            )
        }
        hideInfoBar()
        pokePanelTimer()
    }

    fun closePanel(skipSignalReplay: Boolean = false) {
        panelTimerJob?.cancel()
        // P2-13：面板打开期间断流（signalError 常驻、就绪超时已取消），
        // 关面板时若仍未在播，重播当前台——让就绪超时/自动换台链重新接管，
        // 否则播放器静置在错误态，需用户手动换台或按 OK 重试。
        // 已在播（流已恢复）或无 signalError 时不动作；失败页（playbackFailed）
        // 的重试仍走遮罩上的重试按钮，不在这里自动重试。
        // 第 7 轮 P1-1：离页后面板定时器到点调进来时不重播（到点回调已凭
        // pagePaused 核对过一次，这里是第二道——closePanel 也可由返回键调入，
        // 在页时标志为假，不影响正常关面板重播）。
        // A10-P2-3：数字键换台先关面板只是为清面板/弹窗，随后数字键切台
        // 又调一次 playCurrent()——关面板这一次重播是白白浪费，调用方传
        // skipSignalReplay = true 跳过；面板定时器到期自动关面板时的重播
        // 是有意设计（定时器调用点不传参，保持原行为）。
        val hadSignalError = _uiState.value.signalError
        val wasPlaying = _uiState.value.isPlaying
        _uiState.update { it.copy(panelOpen = false, epgDialogChannel = null) }
        if (shouldReplaySignalOnClosePanel(
                hadSignalError,
                wasPlaying,
                _uiState.value.channels.isNotEmpty(),
                pagePaused,
                skipSignalReplay,
            )
        ) {
            playCurrent()
        }
    }

    fun setFocusedChannel(index: Int) {
        _uiState.update { it.copy(focusedChannelIndex = index) }
        pokePanelTimer()
    }

    /** 面板 15 秒无操作自动关闭；任何面板内操作都要续命。 */
    fun pokePanelTimer() {
        if (!shouldArmPanelTimer(_uiState.value.panelOpen, _uiState.value.epgDialogChannel != null)) {
            // EPG 弹窗打开期间暂停面板定时器（P1-4）：弹窗内上/下浏览节目单不经过根 handler，
            // 不续命也会 15 秒后连弹窗带面板一起关掉。先取消已挂起的定时器，弹窗关闭时再重起。
            panelTimerJob?.cancel()
            return
        }
        panelTimerJob?.cancel()
        panelTimerJob = viewModelScope.launch {
            delay(LiveController.PANEL_AUTO_CLOSE_MS)
            // 第 7 轮 P1-1：离页后到点不关面板、更不触发关面板后的重播
            //（同就绪超时/数字键提交，凭 pagePaused 二次核对）。
            if (shouldFirePendingPlaybackTask(pagePaused)) closePanel()
        }
    }

    // ---- EPG 全天节目单弹窗 ----

    fun openEpgDialog(channel: M3uParser.Channel) {
        _uiState.update { it.copy(epgDialogChannel = channel) }
        pokePanelTimer()
    }

    fun closeEpgDialog() {
        _uiState.update { it.copy(epgDialogChannel = null) }
        pokePanelTimer()
    }

    /** 弹窗内左/右键切换频道节目单。 */
    fun switchEpgDialogChannel(delta: Int) {
        val state = _uiState.value
        val dialog = state.epgDialogChannel ?: return
        if (state.channels.isEmpty()) return
        // 按对象身份定位（P2-4）：同 URL 出现多次时不落到第一条。
        val idx = LiveController.indexOfChannel(state.channels, dialog)
        if (idx < 0) return
        val next = state.channels[(idx + delta + state.channels.size) % state.channels.size]
        _uiState.update { it.copy(epgDialogChannel = next) }
        pokePanelTimer()
    }

    // ---- 信息条 / 提示 / 退出确认 ----

    /** 换台信息条：显示 3 秒后消失（ui-design.md §5.4）。 */
    private fun showInfoBar() {
        infoBarJob?.cancel()
        _uiState.update { it.copy(infoChannelIndex = controller.currentIndex) }
        infoBarJob = viewModelScope.launch {
            delay(LiveController.INFO_BAR_MS)
            hideInfoBar()
        }
    }

    private fun hideInfoBar() {
        infoBarJob?.cancel()
        _uiState.update { it.copy(infoChannelIndex = null) }
    }

    /** 提示消息条：显示 3 秒后消失。 */
    private fun showToast(message: String) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = message) }
        toastJob = viewModelScope.launch {
            delay(LiveController.MESSAGE_MS)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    fun openExitConfirm() {
        _uiState.update { it.copy(exitConfirmOpen = true) }
    }

    fun dismissExitConfirm() {
        _uiState.update { it.copy(exitConfirmOpen = false) }
    }

    override fun onCleared() {
        tickerJob?.cancel()
        epgRefreshJob?.cancel()
        readyTimeoutJob?.cancel()
        infoBarJob?.cancel()
        toastJob?.cancel()
        panelTimerJob?.cancel()
        stablePlayJob?.cancel()
        digitCommitJob?.cancel()
        playerManager.removeListener(playerListener)
        // 后台播放开着：退出直播页后继续播（画中画/后台音频），不 pause、不 stop；
        // 关着：沿用旧行为，退出即停播。与 PlayViewModel.onCleared 同款逻辑。
        cleanupScope.launch {
            // A7-P2-4：清理协程读 DataStore 也要包 runCatching——偏好损坏时
            // currentBackgroundPlayback 抛错，之前会让清理协程崩溃（与
            // PlayViewModel.onCleared 同款）。
            val backgroundOn = runCatching { settingsRepository.currentBackgroundPlayback() }
                // B17-P2-1：取消异常原样重抛，不当成读失败按关处理。
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            if (backgroundOn) {
                playerManager.backgroundPlaybackEnabled = true
            } else {
                playerManager.backgroundPlaybackEnabled = false
                playerManager.pauseIfOwner(playbackToken)
                playerManager.stopIfOwner(playbackToken)
            }
        }
        // cleanupScope 不在这里 cancel：后台判断协程要在 onCleared 之后跑完。
        super.onCleared()
    }

    companion object {
        /** 直播播放就绪 15 秒超时（plan §5.5）。 */
        const val LIVE_READY_TIMEOUT_MS = 15_000L

        /** 直播永远 1× 倍速（点播倍速不泄漏到直播）。 */
        const val LIVE_PLAYBACK_SPEED = 1f

        /**
         * 直播时钟/EPG 刷新是否应跑一跳（可单测，P2-9）：只在前台跑；
         * 切后台时时钟与 EPG 刷新都停（直播本身不靠这个循环推进，
         * 与后台播放开关无关；恢复前台后下一跳继续）。
         */
        internal fun shouldRunTicker(isForeground: Boolean): Boolean = isForeground

        /**
         * 回页是否应重开播（可单测，P1-4）：已是本页 token 所有者且在播时不动；
         * 播放器被抢走（点播页开播）或停掉（离页时暂停）时重新认领并开播当前台。
         * A10-P2-2：失败页（playbackFailed）不自动重播——失败时播放器不在播，
         * 旧谓词恒为真，回页会悄悄重播一次；重试仍走失败页“重试”按钮。
         */
        internal fun shouldResumePlayback(
            isCurrentOwner: Boolean,
            isPlaying: Boolean,
            playbackFailed: Boolean = false,
        ): Boolean = !(isCurrentOwner && isPlaying) && !playbackFailed

        /**
         * 回页时同台缓冲是否应直接 play() 续播、不 stop()+playUrl() 重挂
         * （可单测，A12-P2-1）：目标台就是上次播的台、播放器里挂的也是同一条
         * URL、且正在缓冲时才续播——play() 只置 playWhenReady，已缓冲的分片
         * 原地接上；已暂停就绪态（isBuffering=false）/ 未加载（currentUrl=null）
         * / 换台时一律走现有重播。Uri 往返的编码差异导致对不上时也走重播，
         * 宁可多缓一次，不误判。
         */
        internal fun shouldResumeBuffering(
            targetUrl: String?,
            lastPlayedUrl: String?,
            currentPlayerUrl: String?,
            isBuffering: Boolean,
        ): Boolean = targetUrl != null && targetUrl == lastPlayedUrl &&
            targetUrl == currentPlayerUrl && isBuffering

        /**
         * 面板定时器是否应挂起（可单测，P1-4）：面板打开且 EPG 弹窗未打开时才挂 15 秒定时器；
         * 弹窗打开期间暂停（不挂新的，旧的由调用方取消），弹窗关闭后恢复。
         */
        internal fun shouldArmPanelTimer(panelOpen: Boolean, epgDialogOpen: Boolean): Boolean =
            panelOpen && !epgDialogOpen

        /**
         * 后台是否允许开播（可单测，A9-P1-1）：页面在前台时一律允许；
         * 后台时只允许已开播过的延续（PiP 内断流恢复、开关开着的继续播），
         * 从未开播过的新开播一律暂缓（开关开时是无 PiP 窗口的后台音频，
         * 开关关时违背开关语义）；暂缓的开播由 onPageResumed() 回前台时补上。
         */
        internal fun shouldStartPlayback(
            pageActive: Boolean,
            backgroundPlaybackEnabled: Boolean,
            playbackStarted: Boolean,
        ): Boolean = pageActive || (backgroundPlaybackEnabled && playbackStarted)

        /**
         * 离页后播放器错误是否应处理（可单测，第 8 轮 P2-3）：与开播门控同谓词——
         * 前台一律处理；后台只处理已开播过的延续（PiP 内断流恢复）；离页且后台
         * 播放关着时不处理（不推进失败计数、不弹 Toast、不落失败态），回页时
         * onPageResumed 重开播当前台。
         */
        internal fun shouldHandlePlaybackError(
            pageActive: Boolean,
            backgroundPlaybackEnabled: Boolean,
            playbackStarted: Boolean,
        ): Boolean = shouldStartPlayback(pageActive, backgroundPlaybackEnabled, playbackStarted)

        /**
         * 关面板时是否应重播当前台（可单测，A10-P2-3）：信号异常且未在播、
         * 有频道、未离页时重播，让就绪超时/自动换台链重新接管；数字键换台
         * 先关面板时跳过（随后数字键切台还会调一次 playCurrent()，关面板
         * 这一次是白白浪费）。
         */
        internal fun shouldReplaySignalOnClosePanel(
            signalError: Boolean,
            isPlaying: Boolean,
            hasChannels: Boolean,
            pagePaused: Boolean,
            skipSignalReplay: Boolean,
        ): Boolean = signalError && !isPlaying && hasChannels &&
            shouldFirePendingPlaybackTask(pagePaused) && !skipSignalReplay

        /**
         * 无效台号提交后是否应重播当前台（可单测，第 8 轮 P1-1）：与关面板信号重播
         * 同谓词、skip=false——A10-P2-3 让数字键关面板时跳过重播，台号无效时
         * 随后没有切台，跳过的那一次重播必须在这里补回来，否则恢复链彻底断掉。
         */
        internal fun shouldReplaySignalAfterInvalidDigit(
            signalError: Boolean,
            isPlaying: Boolean,
            hasChannels: Boolean,
            pagePaused: Boolean,
        ): Boolean = shouldReplaySignalOnClosePanel(
            signalError, isPlaying, hasChannels, pagePaused, skipSignalReplay = false,
        )

        /**
         * 待定播放任务到点时是否应执行（可单测，第 7 轮 P1-1）：离页后就绪超时/
         * 数字键提交/面板定时器到点也不应再播放——取消是协作式的，已越过
         * delay 的任务仍会恢复执行，须凭 pagePaused 二次核对。
         */
        internal fun shouldFirePendingPlaybackTask(pagePaused: Boolean): Boolean = !pagePaused

        /**
         * 刷新后 EPG 弹窗应持的新频道对象（可单测，第 7 轮 P2-12）：在新表里按
         * (url, name) → url → name 的顺序找（与 LiveController.setChannels
         * 的回退顺序一致），找不到回落到当前播放台；新表里完全无着落时返回
         * null（调用方据此关闭弹窗，不持旧对象）。旧实现一直持刷新前的旧
         * 对象，indexOfChannel 按身份/（url, name）都匹配不到，标题无台号、
         * 左右切台直接 early return。
         */
        internal fun resolveRefreshedEpgChannel(
            fresh: List<M3uParser.Channel>,
            dialogChannel: M3uParser.Channel,
            currentChannel: M3uParser.Channel?,
        ): M3uParser.Channel? {
            fresh.firstOrNull { it.url == dialogChannel.url && it.name == dialogChannel.name }
                ?.let { return it }
            fresh.firstOrNull { it.url == dialogChannel.url }?.let { return it }
            fresh.firstOrNull { it.name == dialogChannel.name }?.let { return it }
            return currentChannel
        }

        /**
         * 手动刷新后是否应按“刷新失败”提示（可单测，A8-P1-2）：拉网失败时
         * loadChannels 回落旧缓存、返回非空旧表，只判表空会把失败当成功、
         * 静默无事发生；失败标记为真时必须提示、保留旧表。
         * 第 10 轮 L10-P2-2（D9-P2-8 残口）：失败只看 loadFailed——合法空源
         * （成功拉取、0 个频道）不是失败，旧签名 `freshIsEmpty || loadFailed`
         * 会误报“刷新失败，仍用旧频道表”；空表与否由调用方按 emptySource 态处理。
         */
        internal fun shouldReportRefreshFailed(loadFailed: Boolean): Boolean =
            loadFailed

        /**
         * 第 7 轮跟进 D7-P2-2：EPG 后台刷新失败的轻提示文案（可单测，与 M3U
         * 的 shouldReportRefreshFailed 同款接线）：loadFailed 为真才提示；
         * 有旧节目单可回落时说明“仍用旧节目单”，无旧节目单时只说刷新失败。
         * A16-P2-3：首启加载与 10 分钟 ticker 刷新都走这里；初始空态仍另有 loadError。
         */
        internal fun epgRefreshFailedMessage(loadFailed: Boolean, hasOldEpg: Boolean): String? =
            if (!loadFailed) null
            else if (hasOldEpg) "节目单刷新失败，仍用旧节目单"
            else "节目单刷新失败"
    }
}

data class LiveUiState(
    val isLoading: Boolean = true,
    /** 是否配了 M3U 地址（未配时显示空态指引，不直接报错）。 */
    val m3uConfigured: Boolean = false,
    val loadError: String? = null,
    /**
     * 第 10 轮 L10-P2-2：合法空源态——M3U 拉取成功但 0 个频道（与拉取失败
     * 区分：失败置 loadError，空源置本标志；EmptyState 另分支提示）。
     */
    val emptySource: Boolean = false,
    val channels: List<M3uParser.Channel> = emptyList(),
    /** A11-P2-3：频道表版本号——refreshChannels 成功即 +1，供面板落焦
     * effect 作 key；表重排后面板重落当前台行，焦点不丢失。 */
    val channelsVersion: Int = 0,
    val currentIndex: Int = 0,
    val lineIndex: Int = 0,
    val selectedGroup: String = LiveController.ALL_GROUP,
    val favorites: List<LiveFavChannel> = emptyList(),
    val epg: EpgParser.EpgData? = null,
    /** 当前时间戳（30 秒一跳，驱动时钟与节目进度刷新）。 */
    val nowMs: Long = System.currentTimeMillis(),
    val panelOpen: Boolean = false,
    /** 面板里最后聚焦的频道下标（菜单键打开它的节目单）。 */
    val focusedChannelIndex: Int = 0,
    /** EPG 全天节目单弹窗的频道（null = 关闭）。 */
    val epgDialogChannel: M3uParser.Channel? = null,
    /** 换台信息条显示的频道下标（null = 隐藏）。 */
    val infoChannelIndex: Int? = null,
    /** 底部提示消息（null = 隐藏）。 */
    val toastMessage: String? = null,
    /** 数字键缓冲（显示用）。 */
    val digitBuffer: String = "",
    /** 连续失败计数（>5 停手；OK 重试的门控用）。 */
    val failCount: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 断流且自动处理耗尽：显示失败页（重试/手动换台）。 */
    val playbackFailed: Boolean = false,
    /** 面板打开时断流：面板内常驻“信号异常”提示（播好即清，不只靠 3 秒 Toast）。 */
    val signalError: Boolean = false,
    val exitConfirmOpen: Boolean = false,
)
