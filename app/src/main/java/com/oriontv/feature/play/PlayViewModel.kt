package com.oriontv.feature.play

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oriontv.core.util.AppLog
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
    /** 当前片的统一键（init 定下；搜源回调在 IO 线程读它核对代际，见 P2-2）。 */
    @Volatile
    private var currentKey: String = ""
    private var routePositionMs: Long = 0L
    private var initialised = false
    /** 本页认领的播放代际（onCleared 只停自己那一代，不停直播页的播放）。 */
    private var playbackToken: Long = 0L

    /** 是否在前台（PlayScreen 的 ON_RESUME/ON_PAUSE 观察者维护；轮询门控见 startPolling，P2-9）。 */
    @Volatile
    var isForeground: Boolean = true
        private set

    /** A9-P1-2：页面是否在前台（onPagePaused/onPageResumed 维护；应用内导航
     * 切走不经过 Activity 生命周期，只看 isForeground 不够）。startEpisode()
     * 开播前复核它，后台的新开播暂缓、回前台时 onPageResumed() 补开播。 */
    @Volatile
    private var pageActive: Boolean = true
    /** A9-P1-2：本页是否已开播过一次——后台继续播只允许已开播过的延续
     *（PiP 内自动换源/下一集、开关开着的继续播）；从未开播过时后台开播即无窗口
     * 后台音频（开关开）或违背开关语义（开关关）。 */
    private var playbackStarted: Boolean = false
    /** A9-P1-2：暂缓的开播（后台搜源完成/在途开播被取消时记下，回前台时补开）。 */
    private var pendingStart: PendingStart? = null
    /** A9-P1-2：搜源是否在离页时被取消——回前台时 current 仍为空即重跑搜源。 */
    private var searchInterrupted: Boolean = false

    /** A9-P1-2：一次待补的开播请求（结果/集号/起点）。 */
    private data class PendingStart(
        val result: SearchResult,
        val episodeIndex: Int,
        val startPositionMs: Long?,
        /** A19-P2-2：reclaim 时快照的用户暂停态——待补开播重播时仍保持暂停。 */
        val resumePaused: Boolean = false,
    )

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
            } else if (positionMs > 0) {
                // A12-P2-2：同 key、同集、只换了新起点时旧实现直接 return，
                // positionMs 全程未读，深链带来的新起点被丢弃；已开播时直接
                // seek 一次（与当前进度相同则不动），仍在搜源（current == null）
                // 时按集号变化分支同款记入 routePositionMs，由搜源回调的
                // launchStartEpisode 消费，不丢起点。
                val current = _uiState.value.current
                if (current != null) {
                    if (shouldSeekToNewPosition(positionMs, playerManager.currentPositionMs)) {
                        seekTo(positionMs, userInitiated = false)
                    }
                } else {
                    routePositionMs = positionMs
                    _uiState.update { it.copy(positionMs = positionMs) }
                }
            }
            return
        }
        // A10-P2-4：新 key 分支——旧开播/旧超时不断、旧播放不停，旧集会在
        // 新搜源期间继续播，退出时 onCleared 的 saveRecordNow() 还会用新 key +
        // 新集号 + 旧集进度写一条错位记录；先断旧开播、停旧播放，再重认领、
        // 起新搜源。另须清掉旧 current/results：搜源回调只在 current == null
        // 时才开播，不清就永远开不了新集（光取消 job、停播放不够）。
        startEpisodeJob?.cancel()
        readyTimeoutJob?.cancel()
        pendingStart = null
        playbackStarted = false
        playerManager.stopIfOwner(playbackToken)
        initialised = true
        playbackToken = playerManager.claimPlayback()
        currentKey = MoonTvApi.recordKey(source, id, title)
        routePositionMs = positionMs
        // A13-P2-1：新 key 分支也要重置 VM 级逐集字段——之前只清了 uiState
        // 里的几项（A11-P2-1 的范围），introSkippedThisEpisode /
        // skipIntroCapsule / nextEpisodeShownForIndex 还留着旧集的值；
        // startEpisode() 的换集分支够不着它们——搜源回调先把 current 置成
        // 新结果才调 launchStartEpisode，到 startEpisode 时 episodeChanged
        // 已是 false（current 非空且集号相同），新片的片头既不自动跳、胶囊
        // 也不再出现。只动新 key 分支，不动同集换源/重试的“每集一次”语义
        //（那两条路仍走 startEpisode 的换集/重 arm 分支）。
        introSkippedThisEpisode = false
        skipIntroCapsule.resetForEpisode()
        nextEpisodeShownForIndex = -1
        // 后台播放开关读入 PlayerManager：MainActivity.onUserLeaveHint 靠它决定是否进画中画。
        viewModelScope.launch {
            // A8-P2-3：DataStore 读失败会让协程未捕获崩溃；失败即保持默认值
            //（MainActivity 另有持续跟新的缓存值兜底，见 A4-P2-5）。
            runCatching { settingsRepository.currentBackgroundPlayback() }
                .onSuccess { playerManager.backgroundPlaybackEnabled = it }
                .onFailure {
                    // B17-P2-1：取消异常原样重抛，不当成普通读失败用默认值。
                    if (it is CancellationException) throw it
                    AppLog.w("PlayViewModel", "后台播放开关读入失败，用默认值：${it.message}")
                }
        }
        _uiState.update { resetUiStateForNewKey(it, episodeIndex, positionMs, title) }
        startSearch()
        startPolling()
    }

    /**
     * 搜源（A9-P1-2：init 与回前台补搜共用；搜源回调里开播，见 launchStartEpisode）。
     * 源/标识/标题取 init 定下的 currentKey 与 state（retry() 同款拆键），
     * 不另存三份字段。
     */
    private fun startSearch() {
        searchInterrupted = false
        // A11-P2-2：起搜时快照 currentKey——旧搜源的 onUpdate 在 IO 线程内
        // 同步回调，Job.cancel() 只在挂起点生效；旧回调可能在新 key 的 init()
        // 已清掉 current 之后才跑到，只凭 state.current == null 开播会让旧片
        // 在新 key 下开播（A10-P2-4 的竞态残留）。回调开播前复核快照仍等于
        // 当前 currentKey，不等只更新列表不开播；旧 key 回调连列表也不更新
        //（旧源表会污染新片的换源列表）；搜完后的“无源”错误与异常错误
        // 同样只在同代时才落态。
        val searchKey = currentKey
        val (source, id) = MoonTvApi.splitKey(searchKey)
        val title = _uiState.value.title
        // 第 8 轮 P2-2：同款代际快照——searchDetail 的 onUpdate 在 IO 线程
        // 同步执行，旧回调已在执行中时，init() 清 current 与 searchJob?.cancel()
        // 都拦不住它；回调凭快照与现 currentKey 是否一致判定归属（与 startEpisode
        // 的 startEpisodeGeneration 代际校验同款）。
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val preferredSource = if (source == "douban") null else source.ifBlank { null }
                detailRepository.searchDetail(title, preferredSource, id.ifBlank { null }) { results, _ ->
                    // 旧搜源回调撞上同回栈项复用换 key 的瞬间：它读到 init() 刚清的
                    // current == null，会把旧标题的结果装成 current 并开播旧集，
                    // 随后新搜源回来只更新列表——播的是旧片、源列表是新片的。
                    // 代际不符直接丢弃，不写任何状态。
                    if (!shouldAcceptSearchResults(searchKey, currentKey)) return@searchDetail
                    val state = _uiState.value
                    if (shouldStartEpisodeFromSearch(
                            searchKey,
                            currentKey,
                            state.current != null,
                            results.isNotEmpty(),
                        )
                    ) {
                        val first = results.firstOrNull { it.source == source } ?: results[0]
                        _uiState.update { it.copy(results = results, current = first) }
                        launchStartEpisode(first, state.episodeIndex)
                    } else if (searchKey == currentKey) {
                        _uiState.update { it.copy(results = results) }
                    }
                }
                if (searchKey == currentKey && _uiState.value.current == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "没有找到可播放的源")
                    }
                }
            } catch (e: CancellationException) {
                // A4-P2-1：取消必须原样抛出（与 DetailViewModel 同款残留）。
                throw e
            } catch (e: Exception) {
                if (searchKey != currentKey) return@launch
                _uiState.update {
                    it.copy(isLoading = false, error = "加载失败：${e.message ?: "网络错误"}")
                }
            }
        }
    }

    /** 设置页返回 / 切回前台时刷新开播设置缓存（供 PlayScreen 的 ON_RESUME 调用）。 */
    fun refreshCachedSettings() {
        viewModelScope.launch { loadCachedSettings() }
    }

    /**
     * 离页（A9-P1-2，供 PlayScreen 的 ON_PAUSE 与 onDispose 调用）：
     * 记下不在前台，并取消在途的搜源/开播——搜源完成、开播读数完成后再开播
     * 会在后台出声；取消后待补的开播由 pendingStart 记着、被取消的搜源由
     * searchInterrupted 记着，回前台时 onPageResumed() 补上。
     * 注意：只取消在途任务，不暂停已在播的播放（后台播放开关语义与
     * MainActivity.onUserLeaveHint 不变；应用内切走时播放本就继续）。
     */
    fun onPagePaused() {
        pageActive = false
        if (searchJob?.isActive == true) {
            searchJob?.cancel()
            searchInterrupted = true
        }
        startEpisodeJob?.cancel()
        // A10-P2-1：就绪超时也要取消——旧实现只取消搜源/开播，离页后 20 秒
        // 超时到点仍会触发 handlePlaybackError，白白消耗一次自动换源
        //（与直播侧 onPagePaused 取消就绪超时对齐）。
        readyTimeoutJob?.cancel()
    }

    /**
     * 回页（A9-P1-2，供 PlayScreen 的 ON_RESUME 与重进组合调用）：
     * 记回前台、刷新开播设置缓存；有暂缓的开播先补开，没有且搜源曾被取消、
     * 当前仍无结果时重跑搜源；其余情况（已在播/已暂停且仍是所有者/已落错误态）不动，
     * 不自动恢复被暂停的播放；暂停态被别页抢走播放器时重挂媒体项、保持暂停（A19-P2-2）。
     */
    fun onPageResumed() {
        pageActive = true
        refreshCachedSettings()
        pendingStart?.let { pending ->
            pendingStart = null
            launchStartEpisode(pending.result, pending.episodeIndex, pending.startPositionMs, pending.resumePaused)
            return
        }
        if (searchInterrupted && _uiState.value.current == null && searchJob?.isActive != true) {
            startSearch()
        }
        // A17-P2-1：回页时复核播放器归属——看片中途深链压入详情页、再从该页
        // 播另一部压入新播放页，新页 onCleared() 停播并清掉播放项后返回本页时，
        // pendingStart 为空、searchInterrupted 为假，旧实现什么都不做，轮询
        // 因 !isCurrentOwner 直接跳过、OK 调 play() 是空操作，只能退出重进。
        // 只补“被抢走”一种情形：有当前集、无错误态、且自己已不是所有者时
        // 重开当前集；不用 !isPlaying 作门控——用户暂停后去详情页再回时自己
        // 仍是所有者，不会误把暂停的片自动续播；错误页仍走“重试”；起点走
        // resolveSwitchStartMs（A15-P2-1/A16-P2-1 同款），播完态从 0 播。
        // A18-P2-1：isEnded 还要并上 state.isEnded——本集播完后 state.isEnded
        // 冻结为真、positionMs 冻结在片长，但播放器可能已被新页停掉/播别片，
        // playerManager.isEnded 恒 false；只读它会以片长为起点重开，ExoPlayer
        // 钳到片尾立即 STATE_ENDED，误判起点溢出换源/落错误页。
        val state = _uiState.value
        val current = state.current
        if (current != null && shouldReclaimPlayback(state.error == null, playerManager.isCurrentOwner(playbackToken))) {
            // A19-P2-2：快照用户暂停态——已暂停的集被别页抢走播放器后回页，
            // 只重挂媒体项、保持暂停（startEpisode 内 autoPlay=false），
            // 不自动续播；跳过 reclaim 会把页面晾在黑屏上（A18-P2-1 同款教训）。
            launchStartEpisode(
                current,
                state.episodeIndex,
                resolveSwitchStartMs(playerManager.isEnded || state.isEnded, state.positionMs.takeIf { it > 0 }),
                resumePaused = userPaused,
            )
        }
    }

    /** 开播时读一次开关（autoSkipIntro / 片头时长 / 下一集预告），轮询只读缓存。 */
    private suspend fun loadCachedSettings() {
        // A8-P2-3：四处 DataStore 读任一失败都会让调用方协程未捕获崩溃
        //（init 的 launch、refreshCachedSettings、startEpisode）；失败即
        // 保留旧缓存值、不崩溃——开关类设置缺省即默认行为。
        try {
            cachedAutoSkipIntro = settingsRepository.autoSkipIntro.first()
            cachedIntroSkipSeconds = settingsRepository.introSkipSeconds.first()
            cachedNextEpisodeOverlayEnabled = settingsRepository.nextEpisodeOverlayEnabled.first()
            // A4-P2-5：后台播放开关也在这里同步重读（设置页返回/切回前台时刷新），
            // 不只靠进页时那一次异步读入。
            playerManager.backgroundPlaybackEnabled = settingsRepository.currentBackgroundPlayback()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("PlayViewModel", "开播设置缓存刷新失败，沿用旧值：${e.message}")
        }
    }

    /** A5-P1-5：开播的唯一入口——取消旧开播再起新的（旧开播在挂起点被取消；
     * 已跑进同步尾巴的旧开播由 startEpisode 内的代际校验拦住）。 */
    private fun launchStartEpisode(
        result: SearchResult,
        episodeIndex: Int,
        startPositionMs: Long? = null,
        resumePaused: Boolean = false,
    ) {
        // A9-P1-2：记下本次开播请求——后台暂缓或在途取消时，回前台凭它补开播。
        pendingStart = PendingStart(result, episodeIndex, startPositionMs, resumePaused)
        startEpisodeJob?.cancel()
        startEpisodeJob = viewModelScope.launch {
            try {
                startEpisode(result, episodeIndex, startPositionMs, resumePaused)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A9-P1-2：开播失败已落错误态，不再留待补开播（回前台不再自动重跑
                // 失败的开播）；只清自己的那一笔，不清已被更新的开播取代的待办。
                if (pendingStart == PendingStart(result, episodeIndex, startPositionMs, resumePaused)) {
                    pendingStart = null
                }
                // A8-P2-3：开播挂起读数（断点/倍速/设置）失败会让协程未捕获崩溃；
                // 落错误态、用户可重试，不崩溃。
                AppLog.w("PlayViewModel", "开播失败：${e.message}")
                _uiState.update { it.copy(isLoading = false, error = "开播失败，请重试") }
            }
        }
    }

    /** 开播一集：恢复倍速与断点，20 秒就绪超时保护。 */
    private suspend fun startEpisode(result: SearchResult, episodeIndex: Int, startPositionMs: Long? = null, resumePaused: Boolean = false) {
        // A9-P1-2：开播前复核页面仍在前台——搜源/读断点期间按 Home 后，搜源完成
        // 仍会在后台调到这里；后台的新开播暂缓（待补开播已由 launchStartEpisode
        // 记下），回前台时 onPageResumed() 补开播；已开播过的延续（PiP 内自动换源/
        // 下一集、开关开着的继续播）不受影响。
        if (!shouldStartPlayback(pageActive, playerManager.backgroundPlaybackEnabled, playbackStarted)) return
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
                // A19-P2-2：换源兜底也带上暂停态，暂停集换源后仍保持暂停。
                launchStartEpisode(next, episodeIndex, resumePaused = resumePaused)
                return
            }
            _uiState.update { it.copy(isLoading = false, error = "该源暂无可播剧集") }
            // A9-P1-2：已落终态错误，不再留待补开播（回前台不自动重跑）；只清自己的那一笔。
            if (pendingStart == PendingStart(result, episodeIndex, startPositionMs, resumePaused)) {
                pendingStart = null
            }
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
        // 也走 startEpisode，之前无条件重置会让“每集一次”的胶囊在换源后再次出现。
        // 开播首集（current == null）按换集处理。
        // A9-P2-1（第 9 轮修正）：下一集预告的“已取消”标记不属于这一组——同集重开
        // 是新的一次播出，上一轮里点过的“取消”不能带到这一轮，否则重开的一集
        // 播到片尾预告永远不再出现；每次开播都清（胶囊的“每集一次”不受影响，
        // 换源后立即重弹另有 nextEpisodeShownForIndex 拦着，见 A7-P2-1）。
        val episodeChanged = _uiState.value.current == null ||
            episodeIndex != _uiState.value.episodeIndex
        nextEpisodeDismissedForIndex = -1
        // 第 7 轮 P2-3：同集换源/自动换源/重试时，新起点仍在片头窗口内也重 arm
        // 自动跳与胶囊——否则开播早期（仍在片头窗口内）换源后，新源上自动跳
        // 不触发、胶囊不重现，用户只能手动快进；起点已过片头时不重 arm，
        // “每集一次”不被换源重置。片长守卫在轮询里按新源片长重算
        //（maybeAutoSkipIntro 的 shouldAutoSkip，见 watch 项 (b)）。
        // 注意：只重 arm 自动跳与胶囊；nextEpisodeDismissedForIndex 由上面按
        // A9-P2-1 每次开播都清（同集重开是新的一次播出，“取消”不带到这一轮），
        // 换源后立即重弹另有 nextEpisodeShownForIndex 拦着。
        val resolvedIntroEndMs = IntroSkip.resolveIntroEndMs(
            settings?.introEndTime,
            cachedIntroSkipSeconds,
        )
        if (episodeChanged) {
            introSkippedThisEpisode = false
            skipIntroCapsule.resetForEpisode()
            nextEpisodeShownForIndex = -1
            // A7-P1-1：换集后自动换源次数与失败源集合也要重置——之前只清预告
            // 状态，上一集换源 2 次后下一集首次播错直接报错。
            _uiState.update { it.copy(autoSwitchAttempts = 0, failedSources = emptySet()) }
        } else if (shouldRearmIntroSkipOnSameEpisode(startPosition, resolvedIntroEndMs)) {
            introSkippedThisEpisode = false
            skipIntroCapsule.resetForEpisode()
        }
        // A19-P2-2：reclaim 恢复暂停态时保持 userPaused=true（就绪超时门控
        // 与轮询 isPlaying 语义随之保持暂停）；其余开播一律从播放态开始。
        userPaused = resumePaused
        lastSavedPositionMs = -1L
        markedIntroEndMs = settings?.introEndTime?.takeIf { it > 0 }
        playerManager.setPlaybackSpeed(rate)
        // A7-P2-2：记下本集开播起点，供播完回调/轮询识别“起点溢出”。
        episodeStartPositionMs = startPosition
        startPositionOverflowHandled = false
        // A5-P1-5：播流前复核代际——挂起读数期间已有新开播时，旧开播不再碰播放器。
        if (generation != startEpisodeGeneration) return
        playerManager.playUrl(episodes[episodeIndex], startPosition, autoPlay = !resumePaused)
        playbackStarted = true
        // A5-P1-5：收尾写状态前再复核一次代际，旧开播不覆盖新开播的集/状态。
        if (generation != startEpisodeGeneration) return
        // A9-P1-2：本次开播已真正播出，清除待补开播；只清自己的那一笔。
        if (pendingStart == PendingStart(result, episodeIndex, startPositionMs, resumePaused)) {
            pendingStart = null
        }
        _uiState.update {
            it.copy(
                // A20-P2-2：暂停态重挂不置加载态——旧实现无条件 isLoading=true，
                // 但暂停态下就绪超时复核（userPaused 门控）与轮询（只有真正开播
                // 才清 isLoading）两条清除路径都被堵死，加载动画一直盖在已暂停
                // 的画面上，只能按 OK 恢复播放才消失；playUrl(autoPlay=false)
                // 也已渲染首帧，加载态本身无事实依据。播错自动换源路径不受影响
                //（handlePlaybackError 在页分支自己会再置 isLoading=true）。
                isLoading = !resumePaused,
                error = null,
                current = result,
                episodeIndex = episodeIndex,
                positionMs = startPosition,
                breakpointMs = startPosition,
                playbackRate = rate,
                // 手动标记优先，没标过用设置里的默认片头时长兜底（0=不跳）。
                // 第 7 轮 P2-3：复用上面算好的 resolvedIntroEndMs，不重复算。
                introEndMs = resolvedIntroEndMs,
                // 片尾标记：绝对位置，播到该处提前弹下一集预告。
                markedIntroEndMs = settings?.introEndTime?.takeIf { it > 0 },
                outroStartMs = settings?.outroStartTime?.takeIf { it > 0 },
                showNextEpisode = false,
                drawer = null,
                // A8-P1-1：新集开播清播完态，倒计时冻结，直到本集播完才走。
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
            // A10-P2-1：到点复核前台——取消是协作式的，已越过 delay 的任务
            // 仍会恢复执行，离页后到点不再触发自动换源（与直播侧
            // shouldFirePendingPlaybackTask 同款二次核对）。
            if (shouldFireReadyTimeout(pageActive, state.isLoading, state.current != null, userPaused)) {
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
        // 第 8 轮 P2-3：离页后播放器错误回调不累加失败计数、不弹 Toast、不落
        // 错误态——startEpisode 的 shouldStartPlayback 门控已拦住重播（无音频
        // 泄漏），但门控之前的账已算了（failedSources/autoSwitchAttempts 累加、
        // 自动换源记 pendingStart、最终落错误态）；后台播放开着时按“已开播过的
        // 延续”放行（与 startEpisode 同谓词）。只记待补开播：回页时
        // onPageResumed 凭 pendingStart 重播当前源（不记失败、不换源——回页后
        // 若再失败，在页内走正常自动换源）；不记待补时回页无任何恢复，播放器会
        // 停在错误态、OK 也无法自愈（play() 不重挂 media item）。
        if (!shouldHandlePlaybackError(pageActive, playerManager.backgroundPlaybackEnabled, playbackStarted)) {
            // A16-P2-1：播完态下当前进度即片尾位置，直接记会让回页补开播触发
            // 起点溢出守卫误判、自动换源并记当前源失效；播完态记 0L 从头播。
            // A20-P2-1：待补开播也带上暂停态——暂停后离页期间播错、回页补开播
            // 时 resumePaused 默认为 false 会把暂停的片自动续播（与 A19-P2-2
            // 修掉的 reclaim 缺口同一失效模式，只是走了 deferred 路径）。
            pendingStart = PendingStart(
                current,
                state.episodeIndex,
                resolveSwitchStartMs(playerManager.isEnded, playerManager.currentPositionMs.takeIf { it > 0 }),
                resumePaused = userPaused,
            )
            return
        }
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
                // A16-P2-1：播完态下当前进度即片尾位置，同款误判（见上）；播完态从 0 播。
                // A20-P2-1：在页自动换源也带上暂停态——暂停时源报错换源后
                // resumePaused 默认为 false 会把暂停的片在新源上自动播出。
                launchStartEpisode(
                    next,
                    state.episodeIndex,
                    if (resumeFromStart) 0L else resolveSwitchStartMs(playerManager.isEnded, playerManager.currentPositionMs),
                    resumePaused = userPaused,
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
        // A8-P1-1：记 isEnded，下一集倒计时只在播完态走（A6-P1-1 的
        // “正在播或已播完”会让预告卡边播边倒计时、吞掉最后约 7 秒）。
        _uiState.update { it.copy(isEnded = true) }
        viewModelScope.launch {
            runCatching { playRecordsRepository.delete(currentKey) }
                // B17-P2-1：附属写失败不推翻主流程，但取消异常原样重抛。
                .onFailure { if (it is CancellationException) throw it }
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
                val ended = playerManager.isEnded
                val state = _uiState.value
                val wasLoading = state.isLoading
                _uiState.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        bufferedMs = buffered,
                        isPlaying = playing,
                        // 第 6 轮 P1-1：每轮询用播放器实态刷新播完标志——
                        // onPlaybackEnded 事件置真、这里在重播/快进离尾后清掉，
                        // 不靠状态机猜（暂停后 500ms 内重播再暂停的竞态会留 stale 真值，
                        // togglePlayPause 的重播判断直接读 playerManager.isEnded，不读它）。
                        isEnded = ended,
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
                // 第 9 轮 P2-1（纵深）：搜源/加载期 current 还没回来时，轮询不跑
                // 自动跳与胶囊——哪怕 introEndMs 仍是旧值/默认值，位置 0 的
                // inWindow 也恒真；只在已开播（current 非空）后才跑这两路。
                // startEpisode 会在开播时按新片重算 introEndMs 并重 arm，
                // 门控不丢任何一集的自动跳/胶囊。
                if (state.current != null) {
                    // 自动跳过片头（每集一次，先于胶囊判断：自动跳开了就不出胶囊）。
                    maybeAutoSkipIntro(position, duration)
                    // 跳过片头胶囊：进入片头窗口才出现，出现 10 秒后消失，每集只出现一次。
                    updateSkipIntroVisibility(position)
                }
                // 临近播完（剩 10 秒）且有下一集时也出预告（播完兜底在 onPlaybackEnded）；
                // 标过片尾的，播到片尾标记处就提前出预告。
                // 用户点过“取消”的本集不再重弹（P1-1）。
                if (shouldShowNextEpisodeEarly(
                        nextEpisodeShownForIndex,
                        state.episodeIndex,
                        duration,
                        position,
                        state.outroStartMs,
                        cachedNextEpisodeOverlayEnabled,
                        state.current?.episodes?.size ?: 0,
                        nextEpisodeDismissedForIndex,
                    )
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
     * 第 6 轮 P1-2：把片长传进去做守卫——短片遇默认 90 秒兜底不再开播即被送走。
     * 第 9 轮 P2-2：片长未知（durationMs <= 0）时暂缓自动跳、等片长出了再判——
     * 轮询每 500ms 会再来，不会丢跳；慢源上片长晚于首个 tick 到达时，旧行为
     * 会在位置 0 直接 seekTo(90_000)，短片（<100 秒）还没出第一帧就被钳到
     * 片尾、判成“看完一集”。只动自动跳一路，手动胶囊不受影响。
     */
    private fun maybeAutoSkipIntro(positionMs: Long, durationMs: Long) {
        val state = _uiState.value
        if (!shouldAutoSkipNow(
                positionMs,
                state.introEndMs,
                cachedAutoSkipIntro,
                introSkippedThisEpisode,
                durationMs,
            )
        ) {
            return
        }
        introSkippedThisEpisode = true
        _uiState.update { it.copy(skipIntroVisible = false) }
        seekTo(state.introEndMs, userInitiated = false)
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
        } else if (shouldReplayFromStart(playerManager.isPlaying, playerManager.isEnded)) {
            // 第 6 轮 P1-1：播完态下 OK 是“重播本集”——STATE_ENDED 下 play()
            // 不重启播放，须先 seekTo(0)；同时只关预告卡、不记“取消”，
            // 本集再次播完时预告卡还能重弹、自动切下一集。
            // 注意：重播判断直接读 playerManager.isEnded（实态），不读
            // uiState.isEnded——后者是轮询刷新的，暂停竞态下可能 stale。
            // 第 7 轮 P2-2：重播也要重置 nextEpisodeShownForIndex（与 retry()
            // 一致）——否则轮询门控以为“本集已出过”，重播后片尾前 10 秒预告
            // 不再提前出现，只有播完时 onPlaybackEnded 才会再弹卡。
            // A13-P2-3：重播也要重置片头状态（与 retry() 经 startEpisode 的
            // 重 arm 语义对齐）——重播不走 startEpisode，introSkippedThisEpisode
            // 仍是真、胶囊已记“出现过”时，重播的片头既不自动跳、胶囊也不出现。
            nextEpisodeShownForIndex = -1
            introSkippedThisEpisode = false
            skipIntroCapsule.resetForEpisode()
            _uiState.update { it.copy(showNextEpisode = false, isEnded = false) }
            seekTo(0L, userInitiated = false)
            playerManager.play()
            userPaused = false
            // 暂停期间就绪超时不触发（P2-6）；恢复播放后若仍在加载，重新起 20 秒超时。
            if (_uiState.value.isLoading) armReadyTimeout()
        } else {
            playerManager.play()
            userPaused = false
            // 暂停期间就绪超时不触发（P2-6）；恢复播放后若仍在加载，重新起 20 秒超时。
            if (_uiState.value.isLoading) armReadyTimeout()
        }
        _uiState.update { it.copy(isPlaying = playerManager.isPlaying) }
        saveRecordNow()
    }

    /**
     * 统一 seek 入口。用户主动 seek（进度条拖动、±10 秒、落点预览提交）走默认
     * userInitiated=true，片头学习只看这类 seek；程序自己发的 seek（自动跳片头、
     * 胶囊跳过、重播回 0、深链断点）必须传 false，否则断点续播会被误学成片头。
     */
    fun seekTo(positionMs: Long, userInitiated: Boolean = true) {
        val fromMs = playerManager.currentPositionMs
        val duration = playerManager.durationMs
        val clamped = clampSeekTarget(positionMs, duration)
        playerManager.seekTo(clamped)
        if (userInitiated) maybeLearnIntroEnd(fromMs, clamped, duration)
        // 第 7 轮 P2-1：seek 后按新位置重算预告卡——落点离开片尾窗口（剩 10 秒内
        // 或已标记片尾处）时清掉，否则预告卡钉屏、“3 秒后自动播放”被冻结误导；
        // 落点仍在片尾窗口内时保留（轮询门控的 nextEpisodeShownForIndex 已置位，
        // 清掉也不会重弹）。片长未知时无法重算，不动。
        val clearNextEpisode = shouldClearNextEpisodeOnSeek(
            clamped,
            duration,
            _uiState.value.outroStartMs,
        )
        _uiState.update {
            it.copy(
                positionMs = playerManager.currentPositionMs,
                showNextEpisode = if (clearNextEpisode) false else it.showNextEpisode,
            )
        }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(playerManager.currentPositionMs + deltaMs)
    }

    /** 跳过片头：跳到片头结束点（手动标记优先，否则默认时长）。 */
    fun skipIntro() {
        val target = _uiState.value.introEndMs
        if (target > 0) seekTo(target, userInitiated = false)
        introSkippedThisEpisode = true
        _uiState.update { it.copy(skipIntroVisible = false) }
    }

    /**
     * 学习式片头：用户在片头窗口内手动往前跳、落点越过当前认定的片头结束点时，
     * 把落点记为本片的片头结束点（与手动标记同一把键、同一个 introEndTime
     * 字段），下集起自动按学到的点跳。只学一次——已有手动标记的不覆盖；写库
     * 失败不崩溃、内存不谎报（与 markIntroEnd 同款）；学完用现有 toast 提示
     * 一次，不加新界面元素。
     */
    private fun maybeLearnIntroEnd(fromMs: Long, toMs: Long, durationMs: Long) {
        if (currentKey.isBlank()) return
        if (!IntroSkip.shouldLearnIntroEnd(
                fromMs = fromMs,
                toMs = toMs,
                introEndMs = _uiState.value.introEndMs,
                hasMark = markedIntroEndMs != null,
                durationMs = durationMs,
            )
        ) return
        viewModelScope.launch {
            try {
                playerSettingsRepository.update(currentKey) { it.copy(introEndTime = toMs) }
                markedIntroEndMs = toMs
                _uiState.update {
                    it.copy(
                        introEndMs = toMs,
                        markedIntroEndMs = toMs,
                        toast = nextToastEvent("已记住片头，下集自动跳过"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "片头学习保存失败：${e.message}")
            }
        }
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
            // A8-P2-3：DataStore 写失败会让协程未捕获崩溃；失败即提示、不崩溃，
            // 内存里的 markedIntroEndMs 保持旧值（不谎报已标记）。
            try {
                if (markedIntroEndMs != null) {
                    playerSettingsRepository.update(currentKey) { it.copy(introEndTime = null) }
                    markedIntroEndMs = null
                    val fallback = cachedIntroSkipSeconds * 1000L
                    _uiState.update {
                        it.copy(introEndMs = fallback, markedIntroEndMs = null, toast = nextToastEvent("已清除片头标记"))
                    }
                } else {
                    playerSettingsRepository.update(currentKey) { it.copy(introEndTime = position) }
                    markedIntroEndMs = position
                    _uiState.update {
                        it.copy(introEndMs = position, markedIntroEndMs = position, toast = nextToastEvent("片头已标记为 ${formatTime(position)}"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "片头标记保存失败：${e.message}")
                _uiState.update { it.copy(toast = nextToastEvent("片头标记保存失败，请重试")) }
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
            // A8-P2-3：同 markIntroEnd，DataStore 写失败提示、不崩溃。
            try {
                if (_uiState.value.outroStartMs != null) {
                    playerSettingsRepository.update(currentKey) { it.copy(outroStartTime = null) }
                    _uiState.update { it.copy(outroStartMs = null, toast = nextToastEvent("已清除片尾标记")) }
                } else {
                    playerSettingsRepository.update(currentKey) { it.copy(outroStartTime = position) }
                    _uiState.update {
                        it.copy(outroStartMs = position, toast = nextToastEvent("片尾已标记为 ${formatTime(position)}"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "片尾标记保存失败：${e.message}")
                _uiState.update { it.copy(toast = nextToastEvent("片尾标记保存失败，请重试")) }
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
        // A15-P2-1：播完态（STATE_ENDED）下换源，当前进度即片尾位置；
        // 直接传给新源会被起点溢出守卫（isStartPositionOverflow）误判为播错、
        // 自动换到别的源。播完态从 0 播新源。
        launchStartEpisode(
            next,
            state.episodeIndex,
            resolveSwitchStartMs(playerManager.isEnded, playerManager.currentPositionMs),
        )
    }

    fun setPlaybackSpeed(rate: Float) {
        playerManager.setPlaybackSpeed(rate)
        _uiState.update { it.copy(playbackRate = rate, drawer = null) }
        // 倍速记忆关掉时只改当次播放，不持久化（阶段 4）。
        viewModelScope.launch {
            // A8-P2-3：DataStore 读写失败会让协程未捕获崩溃；倍速本身已生效，
            // 记忆失败只记日志、不打扰用户。
            try {
                if (settingsRepository.currentSpeedMemory()) {
                    playerSettingsRepository.update(currentKey) { it.copy(playbackRate = rate) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "倍速记忆保存失败：${e.message}")
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
            // A8-P2-3：init() 的挂起读数失败会让协程未捕获崩溃；落错误态、
            // 用户可再重试，不崩溃。launchStartEpisode 自身已包 try/catch，
            // 这里只守 init() 那条分支。
            try {
                if (current != null) {
                    // A15-P2-1 顺带：错误页不隐含非播完态——起点溢出走自动换源、
                    // 次数耗尽后同样落错误页，此时播放器仍在 STATE_ENDED，
                    // state.positionMs 即片尾位置；播完态重试从 0 播。
                    val startMs = resolveSwitchStartMs(
                        playerManager.isEnded,
                        state.positionMs.takeIf { it > 0 },
                    )
                    launchStartEpisode(current, state.episodeIndex, startMs)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "重试开播失败：${e.message}")
                _uiState.update { it.copy(isLoading = false, error = "重试失败，请检查网络后重试") }
            }
        }
    }

    /** 保存播放记录（无痕模式不写；播完/播错/退出时立即存）。 */
    fun saveRecordNow(scope: CoroutineScope = viewModelScope) {
        // A18-P2-2：存盘前复核播放器归属——多弹回栈时 onCleared() 可能读到
        // 别页正在播的位置/片长，却以本集 key + 集号存盘；轮询、播完回调、
        // 播错处理三处存/读位置前都有同款 isCurrentOwner 门控，这里补上。
        // 正常返回清掉的页面必是所有者，断点照常存。
        if (!playerManager.isCurrentOwner(playbackToken)) return
        val state = _uiState.value
        val current = state.current ?: return
        val position = playerManager.currentPositionMs
        val duration = playerManager.durationMs
        if (duration <= 0 || position <= 0) return
        // 看完一集不存断点：否则下次打开 position==duration，立即“播完”。
        if (isNearEnd(position, duration)) return
        lastSavedPositionMs = position
        scope.launch {
            // A8-P2-3：无痕判断与断点保存（服务端/本地写）失败会让协程未捕获
            // 崩溃；断点保存是尽力而为，失败只记日志、下次保存再试，不崩溃。
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("PlayViewModel", "断点保存失败：${e.message}")
            }
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
        // cleanupScope 不在这里 cancel：断点保存协程要在 onCleared 之后跑完。
        super.onCleared()
    }

    companion object {
        /**
         * 同 key、同集、只换了新起点时是否应直接 seek（可单测，A12-P2-2）：
         * 新起点有效（>0）且与当前进度不同时 seek 一次；等于当前进度时不动，
         * 免得一次重复深链把正在播的位置抖回几百毫秒前。起点为 0 不算“新起点”
         * （调用方按“未指定”处理）。
         */
        internal fun shouldSeekToNewPosition(positionMs: Long, currentPositionMs: Long): Boolean =
            positionMs > 0 && positionMs != currentPositionMs

        /**
         * 搜源回调是否应开播（可单测，A11-P2-2）：回调起搜时快照的 key 仍等于
         * 当前 currentKey、且当前无结果、有结果时才开播；旧 key 的回调一律
         * 不开播——onUpdate 在 IO 线程内同步回调，Job.cancel() 只在挂起点
         * 生效，只凭 current == null 判断会让旧片在新 key 下开播。
         */
        internal fun shouldStartEpisodeFromSearch(
            searchKey: String,
            currentKey: String,
            hasCurrent: Boolean,
            hasResults: Boolean,
        ): Boolean = searchKey == currentKey && !hasCurrent && hasResults

        /**
         * 新 key 换片时 UI 态复位（可单测，第 8 轮 P2-1）：A10-P2-4 只清了
         * current/results/error/autoSwitchAttempts/failedSources，同回栈项复用换
         * key 时旧集的下一集预告卡（showNextEpisode/isEnded）、跳过片头胶囊
         * （skipIntroVisible）、抽屉（drawer）与旧片长（durationMs）会在新搜源
         * 加载期间继续显示在加载动画之上；startEpisode 的状态更新要等搜源回来
         * 才清掉它们。A11-P2-1 补：缓冲/断点位置（bufferedMs/breakpointMs）
         * 同样清零，旧值残留在新片搜源期。第 9 轮 P2-1 补：片头/标记/播放态
         * （introEndMs/markedIntroEndMs/outroStartMs/isPlaying/isBuffering/
         * playbackRate/toast）同样不能带到新搜源加载期——否则轮询用默认值
         * 90 秒（首启）或旧片值（换 key）在位置 0 跑自动跳与胶囊：自动跳开着
         * 时对空闲播放器发一次 seekTo(90_000)，关着时胶囊浮在加载动画之上；
         * 控制条按钮还会短暂显示旧片的片头/片尾标记文案与旧倍速/播放态。
         * introEndMs 清 0（不是默认值 90 秒）：0 即“没片头可跳”，轮询的
         * inWindow 与 shouldAutoSkip 都不成立；startEpisode 会按新片重算它。
         */
        internal fun resetUiStateForNewKey(
            state: PlayUiState,
            episodeIndex: Int,
            positionMs: Long,
            title: String,
        ): PlayUiState = state.copy(
            isLoading = true,
            episodeIndex = episodeIndex,
            positionMs = positionMs,
            title = title,
            // A10-P2-4：新 key 不再沿用旧结果——搜源回调只在 current == null
            // 时开播，旧 current 不清会让新搜源回来后只更新列表、不开播；
            // 旧 failedSources/autoSwitchAttempts 也不带到新片。
            current = null,
            results = emptyList(),
            error = null,
            autoSwitchAttempts = 0,
            failedSources = emptySet(),
            showNextEpisode = false,
            isEnded = false,
            skipIntroVisible = false,
            drawer = null,
            durationMs = 0L,
            bufferedMs = 0L,
            breakpointMs = 0L,
            // 第 9 轮 P2-1：片头/标记/播放态复位（见上面 KDoc）。
            introEndMs = 0L,
            markedIntroEndMs = null,
            outroStartMs = null,
            isPlaying = false,
            isBuffering = false,
            playbackRate = 1f,
            toast = null,
        )

        /**
         * 搜源回调的结果是否属于当前搜源（可单测，第 8 轮 P2-2）：回调快照的键
         * 与现 currentKey 一致才接受——旧搜源回调已在 IO 线程执行中时，取消与
         * 清 current 都拦不住它，代际不符的结果必须丢弃，不开播、不写状态。
         */
        internal fun shouldAcceptSearchResults(callbackKey: String, currentKey: String): Boolean =
            callbackKey == currentKey

        /**
         * VOD 就绪超时到点是否应触发（可单测，A10-P2-1）：页面在前台、仍在
         * 加载、有当前结果、用户没手动暂停时才触发；离页后到点不触发
         *（取消是协作式的，已越过 delay 的任务仍会恢复执行，须凭 pageActive
         * 二次核对；与直播侧 shouldFirePendingPlaybackTask 同款）。
         */
        internal fun shouldFireReadyTimeout(
            pageActive: Boolean,
            isLoading: Boolean,
            hasCurrent: Boolean,
            userPaused: Boolean,
        ): Boolean = pageActive && isLoading && hasCurrent && !userPaused

        /**
         * 播完态按 OK 是否应重播本集（可单测，P1-1）：未在播且已到片尾
         * （STATE_ENDED）时 play() 须先 seekTo 才能重播，OK 在此态是重播；
         * 单纯暂停（未播完）时 OK 只是恢复播放，不重播、不回 0。
         */
        internal fun shouldReplayFromStart(isPlaying: Boolean, isEnded: Boolean): Boolean =
            !isPlaying && isEnded

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
         * 后台是否允许开播（可单测，A9-P1-2）：页面在前台时一律允许；
         * 后台时只允许已开播过的延续（PiP 内自动换源/下一集、开关开着的继续播），
         * 从未开播过的新开播一律暂缓（开关开时是无 PiP 窗口的后台音频，
         * 开关关时违背开关语义）；暂缓的开播由 onPageResumed() 回前台时补上。
         * 与 LiveViewModel.shouldStartPlayback 同款谓词（两页各一份，见该处注释）。
         */
        internal fun shouldStartPlayback(
            pageActive: Boolean,
            backgroundPlaybackEnabled: Boolean,
            playbackStarted: Boolean,
        ): Boolean = pageActive || (backgroundPlaybackEnabled && playbackStarted)

        /**
         * 离页后播放器错误是否应处理（可单测，第 8 轮 P2-3）：与开播门控同谓词——
         * 前台一律处理；后台只处理已开播过的延续（PiP 内断流恢复/自动换源）；
         * 离页且后台播放关着时不处理（不累加失败计数、不弹 Toast、不落错误态），
         * 只记 pendingStart 待补开播，回页时 onPageResumed 重播当前源。
         */
        internal fun shouldHandlePlaybackError(
            pageActive: Boolean,
            backgroundPlaybackEnabled: Boolean,
            playbackStarted: Boolean,
        ): Boolean = shouldStartPlayback(pageActive, backgroundPlaybackEnabled, playbackStarted)
        /**
         * 轮询里片尾前 10 秒预告是否应提前出现（可单测，第 7 轮 P2-2）：
         * 本集还没出过（nextEpisodeShownForIndex != episodeIndex）、开关开、
         * 有下一集、没被取消过，且当前位置在片尾窗口内（剩 10 秒或已标记片尾处）。
         * OK 重播把标记重置为 -1（与 retry() 一致），重播后片尾前 10 秒才能
         * 再提前出卡；旧重播分支漏了这一重置，重播后只有播完时 onPlaybackEnded
         * 才会再弹卡。谓词本身与轮询原条件逐项等价，只是抽出来可单测。
         */
        internal fun shouldShowNextEpisodeEarly(
            nextEpisodeShownForIndex: Int,
            episodeIndex: Int,
            durationMs: Long,
            positionMs: Long,
            outroStartMs: Long?,
            overlayEnabled: Boolean,
            episodeCount: Int,
            dismissedForIndex: Int,
        ): Boolean =
            durationMs > 0 && nextEpisodeShownForIndex != episodeIndex &&
                shouldShowNextEpisode(overlayEnabled, episodeIndex, episodeCount, dismissedForIndex) &&
                (durationMs - positionMs in 1..END_THRESHOLD_MS ||
                    IntroSkip.isAtOutro(positionMs, durationMs, outroStartMs))

        /**
         * seek 后下一集预告卡是否应清掉（可单测，第 7 轮 P2-1）：按新位置重算——
         * 落点仍在片尾窗口（剩 10 秒内或已标记片尾处）时保留，否则清掉；
         * 片长未知时无法重算，不动（轮询要求 duration > 0 才出卡，卡出现时片长已知）。
         */
        internal fun shouldClearNextEpisodeOnSeek(
            positionMs: Long,
            durationMs: Long,
            outroStartMs: Long?,
        ): Boolean {
            if (durationMs <= 0) return false
            return !(durationMs - positionMs in 1..END_THRESHOLD_MS ||
                IntroSkip.isAtOutro(positionMs, durationMs, outroStartMs))
        }

        /**
         * 同集换源/自动换源/重试时是否应重 arm 自动跳片头与胶囊（可单测，
         * 第 7 轮 P2-3）：新起点仍在片头窗口内时重 arm——开播早期换源后新源上
         * 自动跳与胶囊才重现；起点已过片头（或没片头可跳）时不重 arm，
         * “每集一次”不被换源重置。换集时走另一分支（无条件重置），不在此判定。
         */
        internal fun shouldRearmIntroSkipOnSameEpisode(
            startPositionMs: Long,
            introEndMs: Long,
        ): Boolean = introEndMs > 0 && startPositionMs < introEndMs

        /**
         * 本轮询 tick 是否应执行自动跳片头（可单测，第 9 轮 P2-2）：片长未知
         * （durationMs <= 0）时暂缓——IntroSkip.shouldAutoSkip 的片长守卫
         * 只在片长已知时生效，未知时放行；慢源上片长晚于首个 tick 到达时，
         * 位置 0 + 默认 90 秒片头会直接 seek 到片尾、短片被判“看完一集”。
         * 暂缓不丢跳：轮询每 500ms 再来，片长一出、仍在片头窗口内就跳；
         * 手动胶囊不走这条谓词，不受影响。
         */
        internal fun shouldAutoSkipNow(
            positionMs: Long,
            introEndMs: Long,
            autoSkipEnabled: Boolean,
            alreadySkipped: Boolean,
            durationMs: Long,
        ): Boolean = durationMs > 0 &&
            IntroSkip.shouldAutoSkip(positionMs, introEndMs, autoSkipEnabled, alreadySkipped, durationMs)

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
         * A15-P2-1：换源/重试的起点（可单测）：播完态下当前进度即片尾位置，
         * 传给新源/重试会触发起点溢出守卫、被误判为播错；播完态一律从 0 播。
         * 未播完时原样传回（null 表示“未指定”，由调用方按断点/路由位置处理）。
         */
        internal fun resolveSwitchStartMs(isEnded: Boolean, positionMs: Long?): Long? =
            if (isEnded) 0L else positionMs

        /**
         * A17-P2-1：回页时是否应重开当前集（可单测）——只补“被抢走”一种情形：
         * 无错误态、且自己已不是播放器所有者。有无当前集由调用方凭 current
         * != null 另行判断。不用 !isPlaying 作门控：用户暂停后去详情页再回时
         * 自己仍是所有者，不会误把暂停的片自动续播；错误页仍走“重试”。
         */
        internal fun shouldReclaimPlayback(noError: Boolean, isCurrentOwner: Boolean): Boolean =
            noError && !isCurrentOwner

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
    /** A8-P1-1：是否已播完（STATE_ENDED）——下一集倒计时只在播完态走，
     * 正在播时预告卡虽已出现（剩 10 秒），倒计时冻结在 3 秒。
     * 第 6 轮 P1-1（本分支）：轮询按播放器实态刷新它、OK 重播时清掉。 */
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
