package com.oriontv.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.play.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    private var lastPlayedUrl: String? = null
    private var readyTimeoutJob: Job? = null
    private var infoBarJob: Job? = null
    private var toastJob: Job? = null
    private var panelTimerJob: Job? = null
    private var digitCommitJob: Job? = null
    private var tickerJob: Job? = null
    private var epgRefreshJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playingConfirmed = true
                readyTimeoutJob?.cancel()
                controller.onPlaybackSuccess()
                _uiState.update {
                    it.copy(isPlaying = true, isBuffering = false, playbackFailed = false, failCount = 0)
                }
            } else {
                _uiState.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
            controller.setChannels(channels, restoreUrl = lastChannel?.url)
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
        initialised = false
        _uiState.update { LiveUiState(favorites = _uiState.value.favorites) }
        init()
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
                _uiState.update { it.copy(nowMs = System.currentTimeMillis()) }
            }
        }
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch {
            while (true) {
                delay(10 * 60_000)
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
        val url = controller.currentUrl ?: return
        playingConfirmed = false
        // 同 URL 重播（OK 重试当前台）：先停掉旧 item 才能真正重挂播放器。
        if (url == lastPlayedUrl) playerManager.stop()
        lastPlayedUrl = url
        playerManager.playUrl(url)
        _uiState.update {
            it.copy(
                currentIndex = controller.currentIndex,
                lineIndex = controller.lineIndex,
                isPlaying = false,
                isBuffering = true,
                playbackFailed = false,
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
                _uiState.update { it.copy(failCount = controller.failCount) }
                showToast(action.message)
            }
        }
    }

    /** OK 重试当前台（失败过且未耗尽自动跳台上限时，OK 不开面板、改重试）。 */
    fun retryCurrent() {
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
        saveLastChannel()
        _uiState.update {
            it.copy(
                currentIndex = controller.currentIndex,
                lineIndex = controller.lineIndex,
                focusedChannelIndex = controller.currentIndex,
                failCount = controller.failCount,
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
        _uiState.update {
            it.copy(lineIndex = controller.lineIndex, failCount = controller.failCount)
        }
        playCurrent()
    }

    private fun saveLastChannel() {
        val channel = controller.currentChannel ?: return
        val last = LiveLastChannel(url = channel.url, name = channel.name)
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
        _uiState.update { it.copy(panelOpen = false, epgDialogChannel = null) }
    }

    fun setFocusedChannel(index: Int) {
        _uiState.update { it.copy(focusedChannelIndex = index) }
        pokePanelTimer()
    }

    /** 面板 15 秒无操作自动关闭；任何面板内操作都要续命。 */
    fun pokePanelTimer() {
        if (!_uiState.value.panelOpen) return
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
        val idx = state.channels.indexOfFirst { it.url == dialog.url }
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
        digitCommitJob?.cancel()
        playerManager.removeListener(playerListener)
        playerManager.pause()
        playerManager.stop()
        super.onCleared()
    }

    companion object {
        /** 直播播放就绪 15 秒超时（plan §5.5）。 */
        const val LIVE_READY_TIMEOUT_MS = 15_000L
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
    val exitConfirmOpen: Boolean = false,
)
