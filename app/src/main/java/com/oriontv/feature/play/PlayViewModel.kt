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
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** A5-P1-5：开播串行化——startEpisode 六处原来都是 fire-and-forget，
     * 并发开播会让 UI 与播放器演到不同集、断点存错集。同一时刻只留一次开播。 */
    private var startEpisodeJob: Job? = null
    /** A5-P1-5：开播代际——开播时快照，收尾（播流、写状态）前复核；
     * 旧开播在同步尾巴里没被取消拦住时，不再覆盖新开播的状态。 */
    private var startEpisodeGeneration = 0
    /** 抓帧串行化（P2-7）：同一时刻只保留一次 MediaMetadataRetriever 抓帧。 */
    private val previewFrameMutex = Mutex()
    /** 退出时保存断点用：onCleared 时 viewModelScope 已取消，不能用它写库。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastSavedPositionMs: Long = -1L
    private var introSkippedThisEpisode = false
    /** 跳过片头胶囊的轮询状态机（P0-1）：“已出现过”与“当前可见”分开管。 */
    private val skipIntroCapsule = SkipIntroCapsule()
    private var nextEpisodeShownForIndex: Int = -1
    /** A7-P2-2：本集开播起点（毫秒）——新源时长短于起点时 ExoPlayer 会把 seek
     * 钳到片尾、直接 STATE_ENDED；播完回调与轮询凭它识别“起点溢出”，按播错
     * 走自动换源，而不是当成“看完一集”弹下一集预告、删断点。 */
    private var episodeStartPositionMs: Long = 0L
    /** A7-P2-2：本集起点溢出是否已按播错处理过（轮询每 500ms 只触发一次）。 */
    private var startPositionOverflowHandled: Boolean = false
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

    /** 是否在前台（PlayScreen 的 ON_RESUME/ON_PAUSE 观察者维护；轮询门控见 startPolling，P2-9）。 */
    @Volatile
    var isForeground: Boolean = true
        private set

    /** 前台状态变化（供 PlayScreen 的生命周期观察者调用）。 */
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

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
        if (initialised && currentKey == MoonTvApi.recordKey(source, id, title)) {
            // A6-P2-9（加固）：同一 Play 回栈项被复用、换集参数又进来时（正常导航
            // 每次都是新回栈项+新 ViewModel，走不到这里；深链重发可能复用），
            // 集号/起点变了就开新集，不丢参数；只变了起点且本集还在搜源，
            // 更新 state 里的集号/位置，让挂起的搜源回调用新值开播。
            if (episodeIndex != _uiState.value.episodeIndex) {
                val current = _uiState.value.current
                if (current != null) {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    launchStartEpisode(current, episodeIndex, positionMs.takeIf { it > 0 })
                } else {
                    _uiState.update { it.copy(episodeIndex = episodeIndex, positionMs = positionMs) }
                    routePositionMs = positionMs
                }
            }
            return
        }
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
                        launchStartEpisode(first, state.episodeIndex)
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

    /** A5-P1-5：开播的唯一入口——取消旧开播再起新的（旧开播在挂起点被取消；
     * 已跑进同步尾巴的旧开播由 startEpisode 内的代际校验拦住）。 */
    private fun launchStartEpisode(
        result: SearchResult,
        episodeIndex: Int,
        startPositionMs: Long? = null,
    ) {
        startEpisodeJob?.cancel()
        startEpisodeJob = viewModelScope.launch {
            startEpisode(result, episodeIndex, startPositionMs)
        }
    }

    /** 开播一集：恢复倍速与断点，20 秒就绪超时保护。 */
    private suspend fun startEpisode(result: SearchResult, episodeIndex: Int, startPositionMs: Long? = null) {
        val generation = ++startEpisodeGeneration
        // A4-P2-8：新一集开播的挂起操作（读设置/断点，服务端模式下还有网络读）
        // 期间旧的就绪超时仍挂着，可能误触自动换源；开播先取消旧超时。
        readyTimeoutJob?.cancel()
        // A4-P2-9：每集开播重新认领播放代际（见 handlePlaybackError 的归属校验）。
        playbackToken = playerManager.claimPlayback()
        val episodes = result.episodes
        if (episodes.isEmpty() || episodeIndex !in episodes.indices) {
            // A7-P2-6：首选源集数不足时不直接报错——试一次其他源（它本身已校验
            // episodes.size > episodeIndex）；无候选才报错。集数校验会拦住
            // 已试过的不够集的源，不会来回兜圈。
            val next = DetailSearchLogic.getNextAvailableSource(
                _uiState.value.results, result.source, episodeIndex, _uiState.value.failedSources,
            )
            if (next != null) {
                _uiState.update { it.copy(failedSources = it.failedSources + result.source) }
                launchStartEpisode(next, episodeIndex)
                return
            }
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
        // P2-5：按集重置的状态只在集号真的变了时重置——同集换源/自动换源/重试
        // 也走 startEpisode，之前无条件重置会让“每集一次”的胶囊在换源后再次出现、
        // 点过“取消”的下一集预告在换源后重弹。开播首集（current == null）按换集处理。
        val episodeChanged = _uiState.value.current == null ||
            episodeIndex != _uiState.value.episodeIndex
        if (episodeChanged) {
            introSkippedThisEpisode = false
            skipIntroCapsule.resetForEpisode()
            nextEpisodeShownForIndex = -1
            nextEpisodeDismissedForIndex = -1
            // A7-P1-1：换集后自动换源次数与失败源集合也要重置——之前只清预告
            // 状态，上一集换源 2 次后下一集首次播错直接报错。
            _uiState.update { it.copy(autoSwitchAttempts = 0, failedSources = emptySet()) }
        }
        userPaused = false
        lastSavedPositionMs = -1L
        markedIntroEndMs = settings?.introEndTime?.takeIf { it > 0 }
        playerManager.setPlaybackSpeed(rate)
        // A7-P2-2：记下本集开播起点，供播完回调/轮询识别“起点溢出”。
        episodeStartPositionMs = startPosition
        startPositionOverflowHandled = false
        // A5-P1-5：播流前复核代际——挂起读数期间已有新开播时，旧开播不再碰播放器。
        if (generation != startEpisodeGeneration) return
        playerManager.playUrl(episodes[episodeIndex], startPosition)
        // A5-P1-5：收尾写状态前再复核一次代际，旧开播不覆盖新开播的集/状态。
        if (generation != startEpisodeGeneration) return
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
                // A6-P1-1：新集开播清播完态，倒计时条件回到“正在播”。
                isEnded = false,
                // P2-3：集次版本号，每次开播（换集/换源/重试/自动换源）都 +1，
                // 播放页据此清掉旧落点预览——只看 durationMs 不够，新集时长与旧集
                // 相同时控制器不重建，待定落点会带到新集、2 秒后对新集 seek 旧落点。
                episodeGeneration = it.episodeGeneration + 1,
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

    /** 播放错误 → 标记当前源失败 → 自动换源（最多 2 次）→ 再报错。
     * resumeFromStart=true 时换源从 0 播（A7-P2-2 起点溢出：原进度已超新源
     * 时长，再传原进度只会二次溢出）。 */
    private fun handlePlaybackError(error: PlaybackException?, resumeFromStart: Boolean = false) {
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
                // A5-P1-5：自动换源也走串行开播入口，不与手动选集并发。
                // A7-P2-2：起点溢出时从 0 播，不再传已超长的原进度。
                launchStartEpisode(
                    next,
                    state.episodeIndex,
                    if (resumeFromStart) 0L else playerManager.currentPositionMs,
                )
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
        // A7-P2-2：起点溢出不是“看完”——新源时长短于开播起点时 ExoPlayer 把
        // seek 钳到片尾、立刻 STATE_ENDED；按播错走一次自动换源（次数上限
        // 内），不断点、不弹下一集预告。
        if (isStartPositionOverflow(episodeStartPositionMs, playerManager.durationMs)) {
            startPositionOverflowHandled = true
            handlePlaybackError(null, resumeFromStart = true)
            return
        }
        val state = _uiState.value
        val current = state.current ?: return
        saveRecordNow()
        // A6-P1-2：看完一集删旧断点——saveRecordNow 只做到“临近片尾不新存”，
        // 之前轮询存下的旧断点（片尾前 10 秒外）还在，重开会从片尾前续播；
        // 播完即删，重开从 0 播（resolveRecordStartMs 的看完钳制是第二道防线）。
        // A6-P1-1：记 isEnded，下一集倒计时按“正在播或已播完”走（播完态
        // isPlaying=false，旧条件会让倒计时永久冻结）。
        _uiState.update { it.copy(isEnded = true) }
        viewModelScope.launch {
            runCatching { playRecordsRepository.delete(currentKey) }
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
                // A5-P2-2：轮询开头判归属——播放器被直播页抢走后，本页的
                // 轮询不再读位置/写状态（之前无归属校验，跨页互相覆盖进度）。
                if (!playerManager.isCurrentOwner(playbackToken)) continue
                // P2-9 前台门控：切后台且后台播放关着时，本轮只空转，不读播放器、
                // 不刷 UI 状态、不存断点；后台播放开着时仍要全量推进——自动跳片头、
                // 下一集预告在后台也要走，否则后台播完不会自动切下一集。
                // 注意：门控只看 Activity 前台（PlayScreen 的 ON_PAUSE/ON_RESUME），
                // 应用内被其他页盖住时 Activity 仍是 resumed，轮询照常跑。
                if (!shouldPoll(isForeground, playerManager.backgroundPlaybackEnabled)) continue
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
                // A7-P2-2（兜底）：播完回调没拦住的起点溢出（例如 STATE_ENDED
                // 没触发），首个轮询发现位置已到片尾且从未就绪时按播错处理一次。
                if (wasLoading && !startPositionOverflowHandled && duration > 0 &&
                    position >= duration &&
                    isStartPositionOverflow(episodeStartPositionMs, duration)
                ) {
                    startPositionOverflowHandled = true
                    handlePlaybackError(null, resumeFromStart = true)
                }
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
                    it.copy(introEndMs = fallback, markedIntroEndMs = null, toast = nextToastEvent("已清除片头标记"))
                }
            } else {
                markedIntroEndMs = position
                playerSettingsRepository.update(currentKey) { it.copy(introEndTime = position) }
                _uiState.update {
                    it.copy(introEndMs = position, markedIntroEndMs = position, toast = nextToastEvent("片头已标记为 ${formatTime(position)}"))
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
                _uiState.update { it.copy(outroStartMs = null, toast = nextToastEvent("已清除片尾标记")) }
            } else {
                playerSettingsRepository.update(currentKey) { it.copy(outroStartTime = position) }
                _uiState.update {
                    it.copy(outroStartMs = position, toast = nextToastEvent("片尾已标记为 ${formatTime(position)}"))
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
        launchStartEpisode(current, next)
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
        launchStartEpisode(current, index)
    }

    /** 换源：不丢集数（集号不变），保进度续播（从当前进度开始，不从 0 播）。 */
    fun selectSource(source: String) {
        val state = _uiState.value
        val next = state.results.firstOrNull { it.source == source } ?: return
        // A6-P1-3：新源集数不足时不静默换集（旧实现 coerceAtMost 到新源末集，
        // 还传原进度，用户无感知就换了集）——Toast 提示并拒绝换源，抽屉保持打开。
        if (state.episodeIndex >= next.episodes.size) {
            _uiState.update {
                it.copy(toast = nextToastEvent("该源只有 ${next.episodes.size} 集，无法切换"))
            }
            return
        }
        _uiState.update { it.copy(drawer = null) }
        launchStartEpisode(next, state.episodeIndex, playerManager.currentPositionMs)
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
        // A5-P2-6：setDataSource 是阻塞调用，取消中断不了——外包 1500ms 超时，
        // 超时直接回 null（调用方用海报兜底），不再让一次抓帧拖住落点预览；
        // 超时后底层阻塞调用仍在 IO 线程跑完才释放 Mutex，属已知代价。
        withTimeoutOrNull(PREVIEW_FRAME_TIMEOUT_MS) {
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
        }

    fun retry() {
        val state = _uiState.value
        val current = state.current
        // A6-P2-2：手动重试保进度（旧实现不传起点，10 秒内断流时断点还没存过，
        // 从 0 重播）并重置 failedSources（只清 autoSwitchAttempts 不清
        // failedSources，重试后自动换源会一直跳过之前失败过的源）。
        // A7-P2-1：同集重试也要重置 nextEpisodeShownForIndex——否则重试后
        // 播到片尾附近，预告卡因“本集已出过”不再出现。
        nextEpisodeShownForIndex = -1
        _uiState.update {
            it.copy(error = null, autoSwitchAttempts = 0, failedSources = emptySet(), isLoading = true)
        }
        viewModelScope.launch {
            if (current != null) {
                launchStartEpisode(current, state.episodeIndex, state.positionMs.takeIf { it > 0 })
            } else {
                initialised = false
                // B5-P2-1：拆键统一走 MoonTvApi.splitKey（最后一个 + 切分 + 反转义），
                // 与 FavoritesScreen/HomeModels 一致；之前手动拆不反转义，
                // 段内含 %2B/%25 的键再进 recordKey 会二次转义、播错集。
                val (source, id) = MoonTvApi.splitKey(currentKey)
                init(
                    source,
                    id,
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
            // A7-P2-4：清理协程读 DataStore 也要包 runCatching——偏好损坏时
            // currentBackgroundPlayback 抛错，之前会让清理协程崩溃（与
            // LiveViewModel.onCleared 同款）。
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
        // cleanupScope 不在这里 cancel：断点保存协程要在 onCleared 之后跑完。
        super.onCleared()
    }

    companion object {
        /** 播错自动换源最多 2 次（ui-design.md §5.3）。 */
        const val MAX_AUTO_SWITCH = 2

        /** A5-P2-6：抓帧超时 1500ms——setDataSource 阻塞不可取消，超时即放弃、用海报兜底。 */
        const val PREVIEW_FRAME_TIMEOUT_MS = 1500L

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
         * 轮询是否应推进（可单测，P2-9）：在前台、或后台播放开着时都推进；
         * 切后台且后台播放关着时只空转——UI 刷新与断点保存都停，
         * 播放器本身由 MainActivity 在 onUserLeaveHint 里暂停，不靠轮询停。
         */
        internal fun shouldPoll(isForeground: Boolean, backgroundPlaybackEnabled: Boolean): Boolean =
            isForeground || backgroundPlaybackEnabled

        /**
         * 起点是否已超出本源时长（可单测，A7-P2-2）：ExoPlayer 的 seekTo 会钳制到
         * 有效范围，起点 >= 时长时直接落到片尾、立刻 STATE_ENDED；此时不是
         * “看完一集”，应按播错走自动换源。起点为 0 或时长未知时不算溢出。
         */
        internal fun isStartPositionOverflow(startPositionMs: Long, durationMs: Long): Boolean =
            startPositionMs > 0 && durationMs > 0 && startPositionMs >= durationMs

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
    /** A6-P1-1：是否已播完（STATE_ENDED）——下一集倒计时在播完态也要走，
     * 不能只看 isPlaying（播完时 isPlaying=false，倒计时永久冻结）。 */
    val isEnded: Boolean = false,
    val playbackRate: Float = 1f,
    val showNextEpisode: Boolean = false,
    /** 手动标记的片头结束时间（毫秒）；null 表示没标过（显示“标记片头”）。 */
    val markedIntroEndMs: Long? = null,
    /** 手动标记的片尾开始时间（毫秒，绝对位置）；null 表示没标过。 */
    val outroStartMs: Long? = null,
    /** 一次性提示（标记成功/清除，Toast，2 秒消失）。 */
    val toast: ToastEvent? = null,
    val autoSwitchAttempts: Int = 0,
    val failedSources: Set<String> = emptySet(),
    val skipIntroVisible: Boolean = false,
    val introEndMs: Long = 90_000L,
    /** 断点标记（毫秒）：进度条上的断点位置。 */
    val breakpointMs: Long = 0L,
    val drawer: PlayDrawer? = null,
    /**
     * 集次版本号（P2-3）：每次开播（换集/换源/重试/自动换源）都 +1，
     * 播放页据此清掉旧落点预览；只看 durationMs 不够（新集时长可能与旧集相同）。
     */
    val episodeGeneration: Int = 0,
)
