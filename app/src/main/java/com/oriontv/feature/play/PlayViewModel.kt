package com.oriontv.feature.play

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailRepository
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.data.repo.PlayRecordsRepository
import com.oriontv.data.repo.PlayerSettingsRepository
import com.oriontv.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** 退出时保存断点用：onCleared 时 viewModelScope 已取消，不能用它写库。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastSavedPositionMs: Long = -1L
    private var lastSavedAtMs: Long = 0L
    private var introSkippedThisEpisode = false
    private var skipIntroShownAtMs: Long = 0L
    private var nextEpisodeShownForIndex: Int = -1
    private var currentKey: String = ""
    private var routePositionMs: Long = 0L
    private var initialised = false

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
        if (initialised && currentKey == "$source+$id") return
        initialised = true
        currentKey = "$source+$id"
        routePositionMs = positionMs
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "加载失败：${e.message ?: "网络错误"}")
                }
            }
        }
        startPolling()
    }

    /** 开播一集：恢复倍速与断点，20 秒就绪超时保护。 */
    private suspend fun startEpisode(result: SearchResult, episodeIndex: Int) {
        val episodes = result.episodes
        if (episodes.isEmpty() || episodeIndex !in episodes.indices) {
            _uiState.update { it.copy(isLoading = false, error = "该源暂无可播剧集") }
            return
        }
        val key = "${result.source}+${result.id.orEmpty()}"
        currentKey = key
        val settings = playerSettingsRepository.get(key)
        val rate = settings?.playbackRate ?: 1f
        // 断点：路由带来的位置优先，否则取该集的播放记录。
        val startPosition = if (routePositionMs > 0) {
            routePositionMs.also { routePositionMs = 0L }
        } else {
            val record = playRecordsRepository.getByKey(key)
            if (record?.index == episodeIndex && record.play_time > 0) {
                record.play_time * 1000
            } else {
                0L
            }
        }
        introSkippedThisEpisode = false
        skipIntroShownAtMs = 0L
        nextEpisodeShownForIndex = -1
        lastSavedPositionMs = -1L
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
                introEndMs = settings?.introEndTime
                    ?: (settingsRepository.introSkipSeconds.first() * 1000L),
                showNextEpisode = false,
                drawer = null,
            )
        }
        armReadyTimeout()
    }

    /** VOD 就绪 20 秒超时：超时未就绪按播放错误走自动换源。 */
    private fun armReadyTimeout() {
        readyTimeoutJob?.cancel()
        readyTimeoutJob = viewModelScope.launch {
            delay(20_000)
            val state = _uiState.value
            if (state.isLoading && state.current != null) {
                handlePlaybackError(null)
            }
        }
    }

    /** 播放错误 → 标记当前源失败 → 自动换源（最多 2 次）→ 再报错。 */
    private fun handlePlaybackError(error: PlaybackException?) {
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
                    startEpisode(next, state.episodeIndex)
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

    /** 播完：有下一集且开启预告时出预告卡（3 秒倒计时），否则停在末尾。 */
    private fun onPlaybackEnded() {
        val state = _uiState.value
        val current = state.current ?: return
        saveRecordNow()
        viewModelScope.launch {
            val overlayEnabled = settingsRepository.nextEpisodeOverlayEnabled.first()
            if (overlayEnabled && state.episodeIndex < current.episodes.size - 1) {
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
                        // 首帧/时长一到就解除加载态（就绪超时随之取消）。
                        isLoading = if (duration > 0 || playing) false else it.isLoading,
                    )
                }
                if (wasLoading && (duration > 0 || playing)) readyTimeoutJob?.cancel()
                // 跳过片头胶囊：进入片头窗口才出现，出现 10 秒后消失。
                updateSkipIntroVisibility(position)
                // 临近播完（剩 10 秒）且有下一集时也出预告（播完兜底在 onPlaybackEnded）。
                if (duration > 0 && state.episodeIndex < (state.current?.episodes?.size ?: 0) - 1 &&
                    nextEpisodeShownForIndex != state.episodeIndex &&
                    duration - position in 1..10_000
                ) {
                    nextEpisodeShownForIndex = state.episodeIndex
                    viewModelScope.launch {
                        if (settingsRepository.nextEpisodeOverlayEnabled.first()) {
                            _uiState.update { it.copy(showNextEpisode = true) }
                        }
                    }
                }
                // 断点 10 秒节流保存。
                if (playing && duration > 0 && position - lastSavedPositionMs >= 10_000) {
                    saveRecordNow()
                }
            }
        }
    }

    private suspend fun updateSkipIntroVisibility(positionMs: Long) {
        val state = _uiState.value
        if (introSkippedThisEpisode) {
            if (state.skipIntroVisible) _uiState.update { it.copy(skipIntroVisible = false) }
            return
        }
        val autoSkip = settingsRepository.autoSkipIntro.first()
        val inWindow = autoSkip && state.introEndMs > 0 && positionMs < state.introEndMs
        if (inWindow && !state.skipIntroVisible) {
            skipIntroShownAtMs = System.currentTimeMillis()
            _uiState.update { it.copy(skipIntroVisible = true) }
        } else if (state.skipIntroVisible &&
            System.currentTimeMillis() - skipIntroShownAtMs > 10_000
        ) {
            _uiState.update { it.copy(skipIntroVisible = false) }
        }
    }

    // ---- 控制操作（供 PlayScreen 调用） ----

    fun togglePlayPause() {
        if (playerManager.isPlaying) playerManager.pause() else playerManager.play()
        _uiState.update { it.copy(isPlaying = playerManager.isPlaying) }
        saveRecordNow()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs.coerceAtLeast(0))
        _uiState.update { it.copy(positionMs = playerManager.currentPositionMs) }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(playerManager.currentPositionMs + deltaMs)
    }

    /** 跳过片头：跳到片头结束点（默认 90 秒或已标记点）。 */
    fun skipIntro() {
        val target = _uiState.value.introEndMs
        if (target > 0) seekTo(target)
        introSkippedThisEpisode = true
        _uiState.update { it.copy(skipIntroVisible = false) }
    }

    fun playNextEpisode() {
        val state = _uiState.value
        val current = state.current ?: return
        val next = state.episodeIndex + 1
        if (next >= current.episodes.size) return
        _uiState.update { it.copy(showNextEpisode = false) }
        viewModelScope.launch { startEpisode(current, next) }
    }

    fun dismissNextEpisode() {
        _uiState.update { it.copy(showNextEpisode = false) }
    }

    fun selectEpisode(index: Int) {
        val state = _uiState.value
        val current = state.current ?: return
        if (index !in current.episodes.indices || index == state.episodeIndex) {
            _uiState.update { it.copy(drawer = null) }
            return
        }
        _uiState.update { it.copy(showNextEpisode = false, drawer = null) }
        viewModelScope.launch { startEpisode(current, index) }
    }

    /** 换源：不丢集数（集号不变），保进度续播。 */
    fun selectSource(source: String) {
        val state = _uiState.value
        val next = state.results.firstOrNull { it.source == source } ?: return
        val episode = state.episodeIndex.coerceAtMost((next.episodes.size - 1).coerceAtLeast(0))
        _uiState.update { it.copy(drawer = null) }
        viewModelScope.launch { startEpisode(next, episode) }
    }

    fun setPlaybackSpeed(rate: Float) {
        playerManager.setPlaybackSpeed(rate)
        _uiState.update { it.copy(playbackRate = rate, drawer = null) }
        viewModelScope.launch {
            playerSettingsRepository.update(currentKey) { it.copy(playbackRate = rate) }
        }
    }

    fun openDrawer(drawer: PlayDrawer?) {
        _uiState.update { it.copy(drawer = drawer) }
    }

    /** 落点预览缩略图：MediaMetadataRetriever 尽力抓帧，m3u8 抓不到时返回 null（用海报兜底）。 */
    suspend fun grabPreviewFrame(url: String, timeMs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
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
            }.getOrNull()
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
                init(
                    currentKey.substringBefore("+"),
                    currentKey.substringAfter("+"),
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
        playerManager.pause()
        playerManager.stop()
        // cleanupScope 不在这里 cancel：断点保存协程要在 onCleared 之后跑完。
        super.onCleared()
    }

    companion object {
        /** 播错自动换源最多 2 次（ui-design.md §5.3）。 */
        const val MAX_AUTO_SWITCH = 2
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
    val autoSwitchAttempts: Int = 0,
    val failedSources: Set<String> = emptySet(),
    val skipIntroVisible: Boolean = false,
    val introEndMs: Long = 90_000L,
    /** 断点标记（毫秒）：进度条上的断点位置。 */
    val breakpointMs: Long = 0L,
    val drawer: PlayDrawer? = null,
)
