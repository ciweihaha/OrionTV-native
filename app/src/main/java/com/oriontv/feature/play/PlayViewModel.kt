package com.oriontv.feature.play

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailRepository
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.data.repo.PlayRecordsRepository
import com.oriontv.data.repo.PlayerSettingsRepository
import com.oriontv.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 播放抽屉：选集 / 换源 / 倍速（三者互斥，打开时焦点落在当前项）。 */
enum class PlayDrawer { EPISODES, SOURCES, SPEED }

/**
 * 播放 ViewModel（ui-design.md §5.3，plan §4.5）：
 * Media3 播放、断点续播（10 秒节流保存）、倍速按影片记忆、
 * 默认片头跳过（90 秒，可关）、播完自动下一集预告、
 * 播错自动换源（最多 2 次）再报错、VOD 就绪 20 秒超时。
 */
@HiltViewModel
class PlayViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val detailRepository: DetailRepository,
    private val playRecordsRepository: PlayRecordsRepository,
    private val playerSettingsRepository: PlayerSettingsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayUiState(isLoading = true))
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    /** 播放器实例（供 PlayScreen 的 PlayerView 用；单例，点播与直播共用）。 */
    val player: ExoPlayer get() = playerManager.player

    private var pollJob: Job? = null
    private var readyTimeoutJob: Job? = null
    private var searchJob: Job? = null
    /** 抓帧串行化（P2-7）：同一时刻只保留一次 MediaMetadataRetriever 抓帧。 */
    private val previewFrameMutex = Mutex()
    /** 退出时保存断点用：onCleared 时 viewModelScope 已取消，不能用它写库。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastSavedPositionMs: Long = -1L
    private var lastSavedAtMs: Long = 0L
    private var introSkippedThisEpisode = false
    /** 跳过片头胶囊的轮询状态机（P0-1）：“已出现过”与“当前可见”分开管。 */
    private val skipIntroCapsule = SkipIntroCapsule()
    private var nextEpisodeShownForIndex: Int = -1
    /** 用户点过“取消”的下一集预告属于哪集（P1-1）：播完也不再重弹，换集时清除。 */
    private var nextEpisodeDismissedForIndex: Int = -1
    /** 用户手动暂停标志（P2-6）：暂停期间就绪超时不触发自动换源。 */
    private var userPaused = false
    /** 开播时读一次的设置缓存（P2-9）：轮询每 500ms 不再重读 DataStore；设置页返回时刷新。 */
    private var cachedAutoSkipIntro: Boolean = true
    private var cachedIntroSkipSeconds: Int = 90
    private var cachedNextEpisodeOverlayEnabled: Boolean = true
    /** 本片手动标记的片头结束时间（毫秒），null 表示没标过（用默认时长兜底）。 */
    private var markedIntroEndMs: Long? = null
    private var currentKey: String = ""
    private var routePositionMs: Long = 0L
    private var initialised = false
    /** 本页认领的播放代际（onCleared 只停自己那一代，不停直播页的播放）。 */
    private var playbackToken: Long = 0L

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onPlaybackEnded()
            }
        }
    }

    init {
        playerManager.addListener(playerListener)
    }

    fun init(source: String, id: String, title: String, episodeIndex: Int, positionMs: Long) {
        // B4-P1-1：统一键——与 DetailViewModel.init 同源（recordKey，空 id 用标题兜底）。
        if (initialised && currentKey == MoonTvApi.recordKey(source, id, title)) return
        initialised = true
        playbackToken = playerManager.claimPlayback()
        currentKey = MoonTvApi.recordKey(source, id, title)
        routePositionMs = positionMs
        // 后台播放开关读入 PlayerManager：MainActivity.onUserLeaveHint 靠它决定是否进画中画。
        viewModelScope.launch {
            playerManager.backgroundPlaybackEnabled = settingsRepository.currentBackgroundPlayback()
        }
        _uiState.update {
            it.copy(
                isLoading = true,
                episodeIndex = episodeIndex,
                positionMs = positionMs,
                title = title,
            )
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val preferredSource = if (source == "douban") null else source.ifBlank { null }
                detailRepository.searchDetail(title, preferredSource, id.ifBlank { null }) { results, _ ->
                    val state = _uiState.value
                    if (state.current == null && results.isNotEmpty()) {
                        val first = results.firstOrNull { it.source == source } ?: results[0]
                        _uiState.update { it.copy(results = results, current = first) }
                        viewModelScope.launch { startEpisode(first, state.episodeIndex) }
                    } else {
                        _uiState.update { it.copy(results = results) }
                    }
                }
                if (_uiState.value.current == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "没有找到可播放的源")
                    }
                }
            } catch (e: CancellationException) {
                // A4-P2-1：取消必须原样抛出（与 DetailViewModel 同款残留）。
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "加载失败：${e.message ?: "网络错误"}")
                }
            }
        }
        startPolling()
    }

    /** 设置页返回 / 切回前台时刷新开播设置缓存（供 PlayScreen 的 ON_RESUME 调用）。 */
    fun refreshCachedSettings() {
        viewModelScope.launch { loadCachedSettings() }
    }

    /** 开播时读一次开关（autoSkipIntro / 片头时长 / 下一集预告），轮询只读缓存。 */
    private suspend fun loadCachedSettings() {
        cachedAutoSkipIntro = settingsRepository.autoSkipIntro.first()
        cachedIntroSkipSeconds = settingsRepository.introSkipSeconds.first()
        cachedNextEpisodeOverlayEnabled = settingsRepository.nextEpisodeOverlayEnabled.first()
        // A4-P2-5：后台播放开关也在这里同步重读（设置页返回/切回前台时刷新），
        // 不只靠进页时那一次异步读入。
        playerManager.backgroundPlaybackEnabled = settingsRepository.currentBackgroundPlayback()
    }

    /** 开播一集：恢复倍速与断点，20 秒就绪超时保护。 */
    private suspend fun startEpisode(result: SearchResult, episodeIndex: Int, startPositionMs: Long? = null) {
        // A4-P2-8：新一集开播的挂起操作（读设置/断点，服务端模式下还有网络读）
        // 期间旧的就绪超时仍挂着，可能误触自动换源；开播先取消旧超时。
        readyTimeoutJob?.cancel()
        // A4-P2-9：每集开播重新认领播放代际（见 handlePlaybackError 的归属校验）。
        playbackToken = playerManager.claimPlayback()
        val episodes = result.episodes
        if (episodes.isEmpty() || episodeIndex !in episodes.indices) {
            _uiState.update { it.copy(isLoading = false, error = "该源暂无可播剧集") }
            return
        }
        loadCachedSettings()
        // B4-P1-1：断点/倍速记忆用 init 定下的 currentKey（进入时的源/标题），
        // 不再按当前结果重算——否则换源/自动换源后记录存到另一把键下，详情
        // 页按进入键查不到断点/收藏。
        val key = currentKey
        val settings = playerSettingsRepository.get(key)
        // 倍速记忆关掉时一律 1×（阶段 4）；开着才恢复该片保存的倍速。
        val speedMemory = settingsRepository.currentSpeedMemory()
        val rate = if (speedMemory) settings?.playbackRate ?: 1f else 1f
        // 断点：显式起点（换源/自动换源传当前进度）优先，其次路由带来的位置，
        // 最后取该集的播放记录（看完的集从 0 播，见 resolveRecordStartMs）。
        val startPosition = when {
            startPositionMs != null -> startPositionMs
            routePositionMs > 0 -> routePositionMs.also { routePositionMs = 0L }
            else -> {
                val record = playRecordsRepository.getByKey(key)
                if (record?.index == episodeIndex) {
                    resolveRecordStartMs(record.play_time, record.total_time)
                } else {
                    0L
                }
            }
        }
        introSkippedThisEpisode = false
        skipIntroCapsule.resetForEpisode()
        nextEpisodeShownForIndex = -1
        nextEpisodeDismissedForIndex = -1
        userPaused = false
        lastSavedPositionMs = -1L
        markedIntroEndMs = settings?.introEndTime?.takeIf { it > 0 }
        playerManager.setPlaybackSpeed(rate)
        playerManager.playUrl(episodes[episodeIndex], startPosition)
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                current = result,
                episodeIndex = episodeIndex,
                positionMs = startPosition,
                breakpointMs = startPosition,
                playbackRate = rate,
                // 手动标记优先，没标过用设置里的默认片头时长兜底（0=不跳）。
                introEndMs = IntroSkip.resolveIntroEndMs(
                    settings?.introEndTime,
                    cachedIntroSkipSeconds,
                ),
                // 片尾标记：绝对位置，播到该处提前弹下一集预告。
                markedIntroEndMs = settings?.introEndTime?.takeIf { it > 0 },
                outroStartMs = settings?.outroStartTime?.takeIf { it > 0 },
                showNextEpisode = false,
                drawer = null,
            )
        }
        armReadyTimeout()
    }

    /** VOD 就绪 20 秒超时：超时未就绪按播放错误走自动换源；用户手动暂停不算断流（P2-6）。 */
    private fun armReadyTimeout() {
        readyTimeoutJob?.cancel()
        readyTimeoutJob = viewModelScope.launch {
            delay(20_000)
            val state = _uiState.value
            if (state.isLoading && state.current != null && !userPaused) {
                handlePlaybackError(null)
            }
        }
    }

    /** 播放错误 → 标记当前源失败 → 自动换源（最多 2 次）→ 再报错。 */
    private fun handlePlaybackError(error: PlaybackException?) {
        // A4-P2-8：处理错误即作废就绪超时（超时任务本身调进来时已跑完，取消无害；
        // 新一集开播挂起期间误触的超时也到此为止，不再多算一次失败）。
        readyTimeoutJob?.cancel()
        // A4-P2-9：代际/归属校验——播放器是直播与点播共用的单例，直播页的
        // 监听器还挂着时，点播的错误不能由直播页接走（反之亦然）；只有当前
        // 播放所有者才处理自己的错误。
        if (!playerManager.isCurrentOwner(playbackToken)) return
        val state = _uiState.value
        val current = state.current ?: return
        val attempts = state.autoSwitchAttempts
        val failed = state.failedSources + current.source
        if (attempts < MAX_AUTO_SWITCH) {
            val next = DetailSearchLogic.getNextAvailableSource(
                state.results, current.source, state.episodeIndex, failed,
            )
            if (next != null) {
                _uiState.update {
                    it.copy(
                        failedSources = failed,
                        autoSwitchAttempts = attempts + 1,
                        isLoading = true,
                        error = null,
                    )
                }
                viewModelScope.launch {
                    startEpisode(next, state.episodeIndex, playerManager.currentPositionMs)
                }
                return
            }
        }
        _uiState.update {
            it.copy(
                failedSources = failed,
                isLoading = false,
                error = "播放失败" +
                    (error?.let { "（${PlayerManager.classifyError(it).describe()}）" } ?: "") +
                    "，已尝试换源",
            )
        }
    }

    /** 播完：有下一集且开启预告、且本集没被用户取消过时出预告卡（3 秒倒计时），否则停在末尾。 */
    private fun onPlaybackEnded() {
        // A4-P2-9：同 handlePlaybackError，不是自己的播放不弹下一集预告。
        if (!playerManager.isCurrentOwner(playbackToken)) return
        val state = _uiState.value
        val current = state.current ?: return
        saveRecordNow()
        viewModelScope.launch {
            if (shouldShowNextEpisode(
                    cachedNextEpisodeOverlayEnabled,
                    state.episodeIndex,
                    current.episodes.size,
                    nextEpisodeDismissedForIndex,
                )
            ) {
                _uiState.update { it.copy(showNextEpisode = true) }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val position = playerManager.currentPositionMs
                val duration = playerManager.durationMs
                val buffered = playerManager.bufferedPositionMs
                val playing = playerManager.isPlaying
                val state = _uiState.value
                val wasLoading = state.isLoading
                _uiState.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        bufferedMs = buffered,
                        isPlaying = playing,
                        isBuffering = playerManager.isBuffering,
                        // 就绪判据：只有真正开始播（首帧已出）才解除加载态；
                        // 光出时长（duration>0）不算——分片全死的流一直 STATE_BUFFERING，
                        // 旧实现一出时长就清 isLoading 并取消 20 秒超时，永远走不到自动换源。
                        isLoading = if (isPlaybackReady(playing)) false else it.isLoading,
                    )
                }
                if (wasLoading && isPlaybackReady(playing)) readyTimeoutJob?.cancel()
                // 自动跳过片头（每集一次，先于胶囊判断：自动跳开了就不出胶囊）。
                maybeAutoSkipIntro(position)
                // 跳过片头胶囊：进入片头窗口才出现，出现 10 秒后消失，每集只出现一次。
                updateSkipIntroVisibility(position)
                // 临近播完（剩 10 秒）且有下一集时也出预告（播完兜底在 onPlaybackEnded）；
                // 标过片尾的，播到片尾标记处就提前出预告。
                // 用户点过“取消”的本集不再重弹（P1-1）。
                if (duration > 0 && nextEpisodeShownForIndex != state.episodeIndex &&
                    shouldShowNextEpisode(
                        cachedNextEpisodeOverlayEnabled,
                        state.episodeIndex,
                        state.current?.episodes?.size ?: 0,
                        nextEpisodeDismissedForIndex,
                    ) &&
                    (duration - position in 1..10_000 ||
                        IntroSkip.isAtOutro(position, duration, state.outroStartMs))
                ) {
                    nextEpisodeShownForIndex = state.episodeIndex
                    if (cachedNextEpisodeOverlayEnabled) {
                        _uiState.update { it.copy(showNextEpisode = true) }
                    }
                }
                // 断点 10 秒节流保存。
                if (playing && duration > 0 && position - lastSavedPositionMs >= 10_000) {
                    saveRecordNow()
                }
            }
        }
    }

    /**
     * 自动跳过片头（P1-10，总协调已定：补自动跳，不改文案）：
     * 每集一次，当前位置仍在片头内且 autoSkipIntro 开时 seek 到片头结束点；
     * 断点续播位置已过片头的不触发；胶囊保留作手动入口。
     */
    private fun maybeAutoSkipIntro(positionMs: Long) {
        val state = _uiState.value
        if (!IntroSkip.shouldAutoSkip(positionMs, state.introEndMs, cachedAutoSkipIntro, introSkippedThisEpisode)) {
            return
        }
        introSkippedThisEpisode = true
        _uiState.update { it.copy(skipIntroVisible = false) }
        seekTo(state.introEndMs)
    }

    /**
     * 跳过片头胶囊的轮询接线（P0-1）：状态机在 [SkipIntroCapsule] 里，
     * 这里只把“是否在片头窗口内”喂进去、把结果同步到 uiState。
     * 胶囊是否出现只看片头窗口，与自动跳开关解耦。
     */
    private fun updateSkipIntroVisibility(positionMs: Long) {
        val state = _uiState.value
        val inWindow = state.introEndMs > 0 && positionMs < state.introEndMs
        val visible = skipIntroCapsule.update(inWindow, introSkippedThisEpisode)
        if (visible != state.skipIntroVisible) {
            _uiState.update { it.copy(skipIntroVisible = visible) }
        }
    }

    // ---- 控制操作（供 PlayScreen 调用） ----

    fun togglePlayPause() {
        if (playerManager.isPlaying) {
            playerManager.pause()
            userPaused = true
        } else {
            playerManager.play()
            userPaused = false
            // 暂停期间就绪超时不触发（P2-6）；恢复播放后若仍在加载，重新起 20 秒超时。
            if (_uiState.value.isLoading) armReadyTimeout()
        }
        _uiState.update { it.copy(isPlaying = playerManager.isPlaying) }
        saveRecordNow()
    }

    fun seekTo(positionMs: Long) {
        val clamped = clampSeekTarget(positionMs, playerManager.durationMs)
        playerManager.seekTo(clamped)
        _uiState.update { it.copy(positionMs = playerManager.currentPositionMs) }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(playerManager.currentPositionMs + deltaMs)
    }

    /** 跳过片头：跳到片头结束点（手动标记优先，否则默认时长）。 */
    fun skipIntro() {
        val target = _uiState.value.introEndMs
        if (target > 0) seekTo(target)
        introSkippedThisEpisode = true
        _uiState.update { it.copy(skipIntroVisible = false) }
    }

    /**
     * 手动标记片头（RN playerStore.setIntroEndTime 对照）：
     * 没标过 → 把当前位置记为片头结束点，跳过片头从此按标记值走；
     * 已标过 → 再点一次清除标记（恢复默认时长兜底）。
     */
    fun markIntroEnd() {
        val position = playerManager.currentPositionMs
        if (position <= 0) return
        viewModelScope.launch {
            if (markedIntroEndMs != null) {
                markedIntroEndMs = null
                playerSettingsRepository.update(currentKey) { it.copy(introEndTime = null) }
                val fallback = cachedIntroSkipSeconds * 1000L
                _uiState.update {
                    it.copy(introEndMs = fallback, markedIntroEndMs = null, toast = "已清除片头标记")
                }
            } else {
                markedIntroEndMs = position
                playerSettingsRepository.update(currentKey) { it.copy(introEndTime = position) }
                _uiState.update {
                    it.copy(introEndMs = position, markedIntroEndMs = position, toast = "片头已标记为 ${formatTime(position)}")
                }
            }
        }
    }

    /**
     * 手动标记片尾（RN playerStore.setOutroStartTime 对照，语义有改动）：
     * 没标过 → 把当前位置记为片尾开始点，播到此处提前弹下一集预告；
     * 已标过 → 再点一次清除标记。
     * RN 存的是“剩余时长”（duration - position）且从未被消费；
     * 原生版存绝对位置并真实使用（见 IntroSkip）。
     */
    fun markOutroStart() {
        val position = playerManager.currentPositionMs
        val duration = playerManager.durationMs
        if (position <= 0 || duration <= 0) return
        viewModelScope.launch {
            if (_uiState.value.outroStartMs != null) {
                playerSettingsRepository.update(currentKey) { it.copy(outroStartTime = null) }
                _uiState.update { it.copy(outroStartMs = null, toast = "已清除片尾标记") }
            } else {
                playerSettingsRepository.update(currentKey) { it.copy(outroStartTime = position) }
                _uiState.update {
                    it.copy(outroStartMs = position, toast = "片尾已标记为 ${formatTime(position)}")
                }
            }
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    fun playNextEpisode() {
        val state = _uiState.value
        val current = state.current ?: return
        val next = state.episodeIndex + 1
        if (next >= current.episodes.size) return
        nextEpisodeDismissedForIndex = -1
        _uiState.update { it.copy(showNextEpisode = false) }
        viewModelScope.launch { startEpisode(current, next) }
    }

    /** 取消下一集预告：记一笔，播完也不再重弹、不自动播下一集（P1-1）；换集时清除。 */
    fun dismissNextEpisode() {
        nextEpisodeDismissedForIndex = _uiState.value.episodeIndex
        _uiState.update { it.copy(showNextEpisode = false) }
    }

    fun selectEpisode(index: Int) {
        val state = _uiState.value
        val current = state.current ?: return
        if (index !in current.episodes.indices || index == state.episodeIndex) {
            _uiState.update { it.copy(drawer = null) }
            return
        }
        nextEpisodeDismissedForIndex = -1
        _uiState.update { it.copy(showNextEpisode = false, drawer = null) }
        viewModelScope.launch { startEpisode(current, index) }
    }

    /** 换源：不丢集数（集号不变），保进度续播（从当前进度开始，不从 0 播）。 */
    fun selectSource(source: String) {
        val state = _uiState.value
        val next = state.results.firstOrNull { it.source == source } ?: return
        val episode = state.episodeIndex.coerceAtMost((next.episodes.size - 1).coerceAtLeast(0))
        _uiState.update { it.copy(drawer = null) }
        viewModelScope.launch { startEpisode(next, episode, playerManager.currentPositionMs) }
    }

    fun setPlaybackSpeed(rate: Float) {
        playerManager.setPlaybackSpeed(rate)
        _uiState.update { it.copy(playbackRate = rate, drawer = null) }
        // 倍速记忆关掉时只改当次播放，不持久化（阶段 4）。
        viewModelScope.launch {
            if (settingsRepository.currentSpeedMemory()) {
                playerSettingsRepository.update(currentKey) { it.copy(playbackRate = rate) }
            }
        }
    }

    fun openDrawer(drawer: PlayDrawer?) {
        _uiState.update { it.copy(drawer = drawer) }
    }

    /** 落点预览缩略图：MediaMetadataRetriever 尽力抓帧，m3u8 抓不到时返回 null（用海报兜底）。 */
    suspend fun grabPreviewFrame(url: String, timeMs: Long): Bitmap? =
        // 同一时刻只保留一次抓帧（P2-7）：Mutex 串行化，快速连按不在 IO 池里堆 retriever；
        // 取消的等待者直接出队，不执行抓帧。
        previewFrameMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(url, mapOf())
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                    } finally {
                        retriever.release()
                    }
                } catch (e: CancellationException) {
                    // 取消异常必须重抛：runCatching 吞掉它会让已取消的协程继续跑。
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }

    fun retry() {
        val state = _uiState.value
        val current = state.current
        _uiState.update { it.copy(error = null, autoSwitchAttempts = 0, isLoading = true) }
        viewModelScope.launch {
            if (current != null) {
                startEpisode(current, state.episodeIndex)
            } else {
                initialised = false
                // 拆键按最后一个 + 切分（与 RN 一致）：键由两段转义后用 + 连接，
                // 源/ID 自身含 + 时（转义后为 %2B）也不能从第一个 + 切。
                val sep = currentKey.lastIndexOf("+")
                init(
                    if (sep >= 0) currentKey.substring(0, sep) else currentKey,
                    if (sep >= 0) currentKey.substring(sep + 1) else "",
                    state.title,
                    state.episodeIndex,
                    state.positionMs,
                )
            }
        }
    }

    /** 保存播放记录（无痕模式不写；播完/播错/退出时立即存）。 */
    fun saveRecordNow(scope: CoroutineScope = viewModelScope) {
        val state = _uiState.value
        val current = state.current ?: return
        val position = playerManager.currentPositionMs
        val duration = playerManager.durationMs
        if (duration <= 0 || position <= 0) return
        // 看完一集不存断点：否则下次打开 position==duration，立即“播完”。
        if (isNearEnd(position, duration)) return
        lastSavedPositionMs = position
        lastSavedAtMs = System.currentTimeMillis()
        scope.launch {
            if (settingsRepository.incognitoMode.first()) return@launch
            playRecordsRepository.save(
                currentKey,
                PlayRecord(
                    title = current.title,
                    source_name = current.source_name,
                    cover = current.poster,
                    index = state.episodeIndex,
                    total_episodes = current.episodes.size,
                    play_time = position / 1000,
                    total_time = duration / 1000,
                    save_time = System.currentTimeMillis(),
                    year = current.year,
                ),
            )
        }
    }

    override fun onCleared() {
        saveRecordNow(cleanupScope)
        pollJob?.cancel()
        readyTimeoutJob?.cancel()
        searchJob?.cancel()
        playerManager.removeListener(playerListener)
        // 后台播放开着：退出播放页后继续播（画中画/后台音频），不 pause、不 stop；
        // 关着：沿用旧行为，退出即停播。
        cleanupScope.launch {
            if (settingsRepository.currentBackgroundPlayback()) {
                playerManager.backgroundPlaybackEnabled = true
            } else {
                playerManager.backgroundPlaybackEnabled = false
                playerManager.pauseIfOwner(playbackToken)
                playerManager.stopIfOwner(playbackToken)
            }
        }
        // cleanupScope 不在这里 cancel：断点保存协程要在 onCleared 之后跑完。
        super.onCleared()
    }

    companion object {
        /** 播错自动换源最多 2 次（ui-design.md §5.3）。 */
        const val MAX_AUTO_SWITCH = 2

        /** 临近片尾阈值（复用下一集预告的 10 秒窗口）：距片尾不到它即视为看完。 */
        const val END_THRESHOLD_MS = 10_000L

        /**
         * VOD 就绪判据（可单测）：只有真正开始播（首帧已出）才算就绪；
         * 光出时长不算——分片全死的流一直 STATE_BUFFERING。
         */
        internal fun isPlaybackReady(isPlaying: Boolean): Boolean = isPlaying

        /**
         * 断点恢复（可单测）：看完的集（play_time >= total_time）从 0 播，
         * 不恢复到片尾导致打开即“播完”。
         */
        internal fun resolveRecordStartMs(recordPlayTimeSec: Long, recordTotalTimeSec: Long): Long {
            if (recordPlayTimeSec <= 0) return 0L
            if (recordTotalTimeSec > 0 && recordPlayTimeSec >= recordTotalTimeSec) return 0L
            return recordPlayTimeSec * 1000
        }

        /** 是否临近片尾（可单测）：距片尾不到阈值即视为看完，不存断点。 */
        internal fun isNearEnd(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs >= durationMs - END_THRESHOLD_MS

        /**
         * 下一集预告是否应出现（可单测，P1-1）：预告开关开、有下一集、
         * 且本集没被用户点过“取消”（取消后播完也不再弹、不自动播下一集）。
         */
        internal fun shouldShowNextEpisode(
            overlayEnabled: Boolean,
            episodeIndex: Int,
            episodeCount: Int,
            dismissedForIndex: Int,
        ): Boolean =
            overlayEnabled && episodeIndex < episodeCount - 1 && dismissedForIndex != episodeIndex

        /**
         * seek 目标钳制（可单测）：钳制到片尾前 1 秒，免得 seek 到正好片尾立即 STATE_ENDED；
         * 时长未知（直播/未出时长）时只保底 >= 0。
         */
        internal fun clampSeekTarget(positionMs: Long, durationMs: Long): Long {
            if (durationMs <= 0) return positionMs.coerceAtLeast(0)
            return positionMs.coerceIn(0L, (durationMs - SeekPreviewController.SEEK_END_MARGIN_MS).coerceAtLeast(0L))
        }
    }
}

private fun PlayerManager.PlayerError.describe(): String = when (this) {
    PlayerManager.PlayerError.Ssl -> "证书错误"
    PlayerManager.PlayerError.Network -> "网络错误"
    is PlayerManager.PlayerError.Other -> "播放错误"
}

data class PlayUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val title: String = "",
    val results: List<SearchResult> = emptyList(),
    val current: SearchResult? = null,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackRate: Float = 1f,
    val showNextEpisode: Boolean = false,
    /** 手动标记的片头结束时间（毫秒）；null 表示没标过（显示“标记片头”）。 */
    val markedIntroEndMs: Long? = null,
    /** 手动标记的片尾开始时间（毫秒，绝对位置）；null 表示没标过。 */
    val outroStartMs: Long? = null,
    /** 一次性提示（标记成功/清除，Toast，2 秒消失）。 */
    val toast: String? = null,
    val autoSwitchAttempts: Int = 0,
    val failedSources: Set<String> = emptySet(),
    val skipIntroVisible: Boolean = false,
    val introEndMs: Long = 90_000L,
    /** 断点标记（毫秒）：进度条上的断点位置。 */
    val breakpointMs: Long = 0L,
    val drawer: PlayDrawer? = null,
)
