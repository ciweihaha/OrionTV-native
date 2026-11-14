package com.oriontv.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.play.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** 退出时读设置用：onCleared 时 viewModelScope 已取消，不能用它读库。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            playerManager.backgroundPlaybackEnabled = settingsRepository.currentBackgroundPlayback()
        }
        viewModelScope.launch {
            val favJson = runCatching { settingsRepository.currentLiveFavChannelsJson() }.getOrDefault("")
            val favorites = runCatching {
                json.decodeFromString<List<LiveFavChannel>>(favJson)
            }.getOrDefault(emptyList())
            controller.setFavorites(favorites)
            val lastJson = runCatching { settingsRepository.currentLiveLastChannelJson() }.getOrDefault("")
            val lastChannel = runCatching {
                json.decodeFromString<LiveLastChannel>(lastJson)
            }.getOrNull()

            val m3uUrl = runCatching { settingsRepository.currentM3uUrl() }.getOrDefault("")
            if (m3uUrl.isBlank()) {
                _uiState.update {
                    it.copy(isLoading = false, m3uConfigured = false, favorites = favorites)
                }
                return@launch
            }
            _uiState.update { it.copy(m3uConfigured = true, favorites = favorites) }
            val channels = m3uRepository.loadChannels(m3uUrl)
            if (channels.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadError = "直播源加载失败，请检查 M3U 地址或网络后重试",
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
                )
            }
            playCurrent()
            loadEpg()
            startTicker()
        }
    }

    /** 手动重试加载（空态/失败页的“重试”按钮）。 */
    fun reload() {
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
            val m3uUrl = runCatching { settingsRepository.currentM3uUrl() }.getOrDefault("")
            if (m3uUrl.isBlank()) return@launch
            val wasFailed = _uiState.value.playbackFailed
            val fresh = m3uRepository.loadChannels(m3uUrl, forceRefresh = true)
            if (fresh.isEmpty()) {
                showToast("刷新失败，仍用旧频道表")
                return@launch
            }
            val current = controller.currentChannel
            // A6-P2-7：传正在播的那条线（controller.currentUrl），不是
            // current?.url（恒为 urls[0]）——正在播 2 号线时刷新后应回 2 号线。
            controller.setChannels(fresh, restoreUrl = controller.currentUrl, restoreName = current?.name)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadError = null,
                    channels = fresh,
                    currentIndex = controller.currentIndex,
                    lineIndex = controller.lineIndex,
                    focusedChannelIndex = controller.currentIndex,
                )
            }
            // 当前台还在新表里且之前没失败过：不断流，只更新表；否则重开播。
            if (controller.currentUrl != lastPlayedUrl || wasFailed) playCurrent()
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
                if (stale) loadEpg()
            }
        }
    }

    // ---- 播放 ----

    /** 开播当前台/线路：直播 15 秒就绪超时（plan §5.5），超时按断流处理。 */
    private fun playCurrent() {
        // A4-P2-9：每次开播重新认领播放代际（错误/状态回调核对后再处理）。
        playbackToken = playerManager.claimPlayback()
        // 换台即开启新的稳定窗口：旧台的稳定确认任务不再有效（P1-6）。
        stablePlayJob?.cancel()
        val url = controller.currentUrl ?: return
        playingConfirmed = false
        // 同 URL 重播（OK 重试当前台）：先停掉旧 item 才能真正重挂播放器。
        if (url == lastPlayedUrl) playerManager.stop()
        lastPlayedUrl = url
        // 点播 2× 倍速不泄漏到直播：PlayerManager 单例保留倍速，开播前复位 1×。
        playerManager.setPlaybackSpeed(LIVE_PLAYBACK_SPEED)
        playerManager.playUrl(url)
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
            if (!playingConfirmed) handlePlaybackError()
        }
    }

    /** 断流：按 LiveController 的升级链处理（备用线路 → 下一台 → >5 次停手）。 */
    private fun handlePlaybackError() {
        // A4-P2-9：归属校验——点播页在前台时，直播页的监听器还挂着，
        // 点播的错误不能触发直播切台（会抢走点播的播放器）。
        if (!playerManager.isCurrentOwner(playbackToken)) return
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
                _uiState.update {
                    it.copy(
                        failCount = controller.failCount,
                        playbackFailed = true,
                        isBuffering = false,
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
     */
    fun onPagePaused() {
        if (!playerManager.backgroundPlaybackEnabled) {
            playerManager.pauseIfOwner(playbackToken)
        }
        tickerJob?.cancel()
        epgRefreshJob?.cancel()
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
        if (!initialised || _uiState.value.channels.isEmpty()) return
        startTicker()
        if (!shouldResumePlayback(
                playerManager.isCurrentOwner(playbackToken),
                playerManager.isPlaying,
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
                showToast("输入频道号：${action.text}")
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
            when (val action = controller.commitDigits()) {
                is DigitAction.SwitchTo -> {
                    _uiState.update { it.copy(digitBuffer = "") }
                    selectChannel(action.index)
                }
                is DigitAction.Invalid -> {
                    _uiState.update { it.copy(digitBuffer = "") }
                    showToast("无 ${action.number} 号频道")
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

    fun closePanel() {
        panelTimerJob?.cancel()
        // P2-13：面板打开期间断流（signalError 常驻、就绪超时已取消），
        // 关面板时若仍未在播，重播当前台——让就绪超时/自动换台链重新接管，
        // 否则播放器静置在错误态，需用户手动换台或按 OK 重试。
        // 已在播（流已恢复）或无 signalError 时不动作；失败页（playbackFailed）
        // 的重试仍走遮罩上的重试按钮，不在这里自动重试。
        val hadSignalError = _uiState.value.signalError
        val wasPlaying = _uiState.value.isPlaying
        _uiState.update { it.copy(panelOpen = false, epgDialogChannel = null) }
        if (hadSignalError && !wasPlaying && _uiState.value.channels.isNotEmpty()) {
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
            closePanel()
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
         */
        internal fun shouldResumePlayback(isCurrentOwner: Boolean, isPlaying: Boolean): Boolean =
            !(isCurrentOwner && isPlaying)

        /**
         * 面板定时器是否应挂起（可单测，P1-4）：面板打开且 EPG 弹窗未打开时才挂 15 秒定时器；
         * 弹窗打开期间暂停（不挂新的，旧的由调用方取消），弹窗关闭后恢复。
         */
        internal fun shouldArmPanelTimer(panelOpen: Boolean, epgDialogOpen: Boolean): Boolean =
            panelOpen && !epgDialogOpen
    }
}

data class LiveUiState(
    val isLoading: Boolean = true,
    /** 是否配了 M3U 地址（未配时显示空态指引，不直接报错）。 */
    val m3uConfigured: Boolean = false,
    val loadError: String? = null,
    val channels: List<M3uParser.Channel> = emptyList(),
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
