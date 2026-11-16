package com.oriontv.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.core.util.InFlightGuard
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailRepository
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.data.repo.FavoriteState
import com.oriontv.data.repo.FavoritesRepository
import com.oriontv.data.repo.PlayRecordsRepository
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/**
 * 详情 ViewModel（ui-design.md §5.2）：
 * 多源聚合（首选源先行、全量后台合并）、源切换 chip、选集、收藏切换、
 * 断点续播入口（播放记录的集号/进度）、失败源标灰。
 * 换源不丢集数：集号存在 state 里，与当前源无关。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playRecordsRepository: PlayRecordsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState(isLoading = true))
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    /** B12-P2-4：在途的收藏切换任务——init() 切新片时取消，不让旧片的切换
     * 结果写到新片态上（与 searchJob 同款取消）。 */
    private var toggleJob: Job? = null
    private var currentKey: String = ""
    private var currentTitle: String = ""
    private var currentId: String = ""

    /** 第 6 轮 P1-2：收藏切换防连按守卫（toggle 是翻转操作，连按两次翻回去）。 */
    private val favoriteToggleGuard = InFlightGuard()

    /** B4-P2-2：轻量代次——init 时 +1，迟到的搜索回调/收藏读取复核后才写回。
     * B7-P2-7：searchDetail 的回调跑在 IO 线程（withContext(ioDispatcher)），
     * 代次在主线程写、IO 线程读，plain var 无可见性保证，改 @Volatile。 */
    @Volatile
    private var generation = 0

    /**
     * C5-P2-4：P1-4/P2-2 的焦点锚点原来是普通变量。详情页被 popUpTo 弹掉
     * 后 ViewModel 被清，返回重建时 focusAnchored 回 false，会抢焦点到
     * “播放”按钮（首页/收藏页同款问题，见 HomeViewModel）。改成
     * SavedStateHandle 承载，照 FavoritesViewModel 的做法。
     */
    var focusAnchored: Boolean
        get() = savedStateHandle[SAVED_FOCUS_ANCHORED] ?: false
        set(value) {
            savedStateHandle[SAVED_FOCUS_ANCHORED] = value
        }

    init {
        // B31-P2-1：播放页返回时把失败源写回详情页标灰（写方见 OrionNavHost
        // Play 侧 onBack：退出前把 PlayViewModel 的 failedSources 写进上一级
        // 回栈项的 SavedStateHandle）。之前 markSourceAsFailed 全仓零调用方，
        // DetailScreen 的标灰渲染永不生效；接线后播放失败的源回详情页即标灰。
        // consume-once：取走后立即清掉 handle，下一次同名源失败再写相同值
        // 也能重新触发（StateFlow 相同值不重复发射）。只并入本次详情页已知
        // 源（见 mergePlaybackFailedSources），跨片旧值不串台。
        // 注意：返回详情页时本 ViewModel 并非全新实例（回栈存活，init 的
        // “返回后重组不丢状态”早退分支即是明证），缺的一直只是回传接线，
        // 不是实例存活问题。
        viewModelScope.launch {
            savedStateHandle.getStateFlow(SAVED_FAILED_SOURCES, emptyList<String>())
                .collect { incoming ->
                    if (incoming.isEmpty()) return@collect
                    savedStateHandle[SAVED_FAILED_SOURCES] = emptyList<String>()
                    val merged = mergePlaybackFailedSources(
                        _uiState.value.failedSources,
                        incoming,
                        _uiState.value.sources.map { it.first },
                    )
                    _uiState.update { it.copy(failedSources = merged) }
                }
        }
    }

    companion object {
        /** SavedStateHandle 键：是否已落过“播放”按钮焦点锚点。 */
        const val SAVED_FOCUS_ANCHORED = "detail_focus_anchored"

        /**
         * SavedStateHandle 键：播放页退出时回传的失败源（source 列表），
         * 见 OrionNavHost Play 侧 onBack；详情页 init 块收集后消费掉。
         */
        const val SAVED_FAILED_SOURCES = "detail_failed_sources"

        /**
         * 详情搜索错误文案映射（可单测，B10-P2-1）：各源都返回、但无精确标题
         * 匹配时仓库抛“没有找到相关内容”，那是“搜不到”，不是网络失败——
         * 旧实现落到通用分支显示“加载失败，请检查网络后重试”，误导用户查网络。
         */
        internal fun mapDetailError(message: String?): String = when {
            message?.contains("未配置服务器地址") == true -> "还没有配置源地址，先去设置里添加吧"
            // B36-P2-3：零视频源不是“搜不到”，给去设置的提示（与上一分支同款）。
            message?.contains("服务器未配置视频源") == true -> "还没有配置视频源，先去设置里添加吧"
            message?.contains("登录已过期") == true -> "登录已过期，请重新登录"
            // B5-P1-3：超时单独提示，用户知道是等太久而不是网络断了，可直接重试。
            message?.contains("超时") == true -> "搜索超时，请重试"
            message?.contains("没有找到相关内容") == true -> "没有找到相关内容，换个关键词试试"
            // P2-17：不把原始异常拼给用户，固定文案。
            else -> "加载失败，请检查网络后重试"
        }

        /**
         * 断点集号按当前源集数钳制（可单测，B7-P2-6）：记录集号超出当前源时落
         * 到末集；源还没回来（集数为 0）时只保底 >= 0，不动集号。
         */
        internal fun clampSelectedEpisode(selectedEpisode: Int, episodeCount: Int): Int =
            if (episodeCount <= 0) selectedEpisode.coerceAtLeast(0)
            else selectedEpisode.coerceIn(0, episodeCount - 1)

        /**
         * 第 7 轮跟进 D7-P2-10：三态收藏映射（可单测）——UNKNOWN 不得画成
         * “未收藏”：isFavorite 仍按 false 计（与 isFavorite() 的旧 Boolean
         * 契约一致），另置 unknown 由 UI 把按钮置灰禁用。
         */
        internal fun mapFavoriteState(state: FavoriteState): FavoriteButtonState =
            when (state) {
                FavoriteState.FAVORITE -> FavoriteButtonState(isFavorite = true, unknown = false)
                FavoriteState.NOT_FAVORITE -> FavoriteButtonState(isFavorite = false, unknown = false)
                FavoriteState.UNKNOWN -> FavoriteButtonState(isFavorite = false, unknown = true)
            }

        /**
         * 第 7 轮跟进 D-P2-13：toggle 失败后重读的收藏态落点（可单测）——重读
         * 成功以服务端实态为准（数据层写后校验分叉时已先按实态更新 lastKnown
         * 再抛错，重读读到的就是实态）；重读本身失败时保留切换前旧态，不二次崩溃。
         */
        internal fun resolveFavoriteAfterToggleFailure(reRead: Boolean?, previous: Boolean): Boolean =
            reRead ?: previous

        /**
         * 收藏切换结果是否应写回当前态（可单测，B12-P2-4）：切换开始时快照的
         * key 与代次仍等于当前值时才写——init() 切新片后旧切换的 toggle() 可能
         * 已越过挂起点、取消拦不住，失败分支的重读更是挂起调用，旧片结果
         * 写到新片态上即串台。只复核，不拦仓库侧的写（服务端切换本身已生效）。
         */
        internal fun shouldApplyToggleResult(
            toggleKey: String,
            currentKey: String,
            toggleGeneration: Int,
            currentGeneration: Int,
        ): Boolean = toggleKey == currentKey && toggleGeneration == currentGeneration

        /**
         * B31-P2-1：播放页回传的失败源并入详情页标灰集（可单测）——只并入
         * 本次详情页已知源（state.sources），handle 里万一残留跨片旧值
         * （Detail→Detail 原位替换时 ViewModel 与 SavedStateHandle 同存活，
         * 见 C13-P2-2）不串台；已标灰的去重（Set 语义）。
         */
        internal fun mergePlaybackFailedSources(
            existing: Set<String>,
            incoming: Collection<String>,
            knownSources: Collection<String>,
        ): Set<String> = existing + incoming.filter { it in knownSources }
    }

    fun init(source: String, id: String, title: String) {
        // 同一视频重复 init 不重新搜（返回后重组不丢状态）。
        // B4-P1-1：统一键——init/toggleFavorite/PlayViewModel 只用 recordKey；
        // id 为空时用标题兜底，不再所有空 id 项共用 `source+` 空键。
        val key = MoonTvApi.recordKey(source, id, title)
        if (currentKey == key && _uiState.value.results.isNotEmpty()) return
        currentKey = key
        currentTitle = title
        currentId = id
        // C13-P2-2：切新片时焦点锚点复位——Detail→Detail 深链（launchSingleTop）
        // 下导航库把栈顶条目原位替换成带新参数的新条目，但新条目复用同一 id 与
        // ViewModelStore（已按 navigation-runtime 2.9.8 的 launchSingleTopInternal
        // 与 NavBackStackEntry 拷贝构造字节码核实，替换路径不调 clear），同一
        // ViewModel 与 SavedStateHandle 存活、focusAnchored 仍是 true；数据到达
        // effect 被锚点挡住、tick 又不变，焦点无处恢复。复位后数据到达即落
        // “播放”按钮；同片重复 init 早退，不动锚点。
        focusAnchored = false
        val gen = ++generation
        searchJob?.cancel()
        // B12-P2-4：切新片时取消在途的收藏切换——toggle() 是翻转操作，旧片
        // 的切换晚到会把结果写到新片态上（init 的搜索回调与收藏读取都有 gen
        // 复核，唯独 toggle 没有）；取消是协作式的，写态前下面还有 key/gen
        // 二次复核，两道防线。
        toggleJob?.cancel()
        _uiState.value = DetailUiState(isLoading = true, selectedEpisode = 0)
        searchJob = viewModelScope.launch {
            // 断点：有播放记录时默认选中记录里的集，并记下断点秒数（P1-2）。
            // B4-P2-5：仓库调用失败不崩溃（读不到断点当无断点，从 0 集播）。
            val record = try {
                playRecordsRepository.getByKey(currentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val startEpisode = record?.index ?: 0
            _uiState.value = _uiState.value.copy(
                selectedEpisode = startEpisode,
                // B-P2-5：断点集所属分组也一并置好，否则分组视图仍停在 1-20 集，
                // 断点集（第 25 集起）不可见、焦点也不在它上面。
                selectedGroup = DetailSearchLogic.groupIndexForEpisode(startEpisode),
                resumeEpisode = record?.index,
                resumePositionSeconds = record?.play_time,
            )
            // douban 项没有真实源，不走单源搜索，直接全量按标题搜。
            val preferredSource = if (source == "douban") null else source.ifBlank { null }
            try {
                detailRepository.searchDetail(title, preferredSource, id.ifBlank { null }) { results, allLoaded ->
                    // B4-P2-2：回调里复核代次——旧搜索迟到返回时不再覆盖新搜索的状态。
                    if (gen != generation) return@searchDetail
                    val state = _uiState.value
                    val current = state.current
                        ?: results.firstOrNull()
                        ?: state.current
                    // B7-P2-6 / 第 6 轮 P2-1：断点集号按当前源集数钳制——记录里的集号可能超出
                    // 当前源（换源/源更新后集数变少），不钳制时点“播放”进错误页。
                    // 只有真正钳动时才重算分组：用户可能正在浏览别的分组视图，别拉回去。
                    val clampedEpisode = clampSelectedEpisode(
                        state.selectedEpisode,
                        current?.episodes?.size ?: 0,
                    )
                    val clampedGroup = if (clampedEpisode != state.selectedEpisode) {
                        DetailSearchLogic.groupIndexForEpisode(clampedEpisode)
                    } else {
                        state.selectedGroup
                    }
                    _uiState.value = state.copy(
                        isLoading = false,
                        results = results,
                        sources = results.map { it.source to it.source_name },
                        current = current,
                        allSourcesLoaded = allLoaded,
                        error = null,
                        selectedEpisode = clampedEpisode,
                        selectedGroup = clampedGroup,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // B5-P1-3 兜底：仓库已把全量搜索超时转成普通失败；这里再拦一次，
                // 防首选源搜索等其它 withTimeout 路径的超时也被当取消重抛
                //（真取消是 JobCancellationException，不走这条分支）。
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(IOException("详情搜索超时，请重试")),
                )
            } catch (e: CancellationException) {
                // B-P1-9：取消必须原样抛出；否则旧搜索任务被取消后仍会把错误态
                // 写回，覆盖新搜索的状态（B-P2-23② 亦由此解决）。
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e),
                )
            }
            // 收藏状态（异步查，不阻塞详情展示）。
            // B4-P2-2：读取前复核代次；B4-P2-5：查不到不崩溃（现按 UNKNOWN 计，见下）。
            // 第 7 轮跟进 D7-P2-10：改用三态 favoriteState()——UNKNOWN（服务端
            // 失败且从未查过）不得画成“未收藏”，按钮置灰禁用（见 DetailScreen）。
            if (gen != generation) return@launch
            val favoriteState = try {
                favoritesRepository.favoriteState(currentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FavoriteState.UNKNOWN
            }
            val buttonState = mapFavoriteState(favoriteState)
            _uiState.value = _uiState.value.copy(
                isFavorite = buttonState.isFavorite,
                isFavoriteUnknown = buttonState.unknown,
            )
        }
    }

    /** 切换源：保留已选集数（换源不丢集数）；新源集数不足时钳到最后一集。 */
    fun selectSource(source: String) {
        val state = _uiState.value
        val next = state.results.firstOrNull { it.source == source } ?: return
        val episode = clampSelectedEpisode(state.selectedEpisode, next.episodes.size)
        _uiState.value = state.copy(current = next, selectedEpisode = episode)
    }

    fun selectEpisode(index: Int) {
        _uiState.value = _uiState.value.copy(selectedEpisode = index)
    }

    fun selectGroup(groupIndex: Int) {
        _uiState.value = _uiState.value.copy(selectedGroup = groupIndex)
    }

    fun toggleFavorite() {
        val state = _uiState.value
        val current = state.current ?: return
        // 第 6 轮 P1-2：收藏切换防连按——toggle() 是“读-改-写翻转”，快速
        // 连按两次 = 翻转两次 = 回到原态（删了又加回来），仓库层的 writeMutex
        // 只串行化调用、拦不住。守卫在发起前同步拿锁（主线程），进行中
        // 第二次点按直接丢弃；按钮同时按 isFavoriteToggling 禁用（见 DetailScreen）。
        // 只改调用方守卫，不碰 Repositories.kt 的 toggle() 本体（数据层 D6-P1-2 修）。
        if (!favoriteToggleGuard.tryAcquire()) return
        _uiState.update { it.copy(isFavoriteToggling = true) }
        // B12-P2-4：切换开始时快照 key 与代次，写态前复核未变才写——init()
        // 切新片后旧切换的 toggle() 可能已越过挂起点、取消拦不住，失败分支
        // 的重读 isFavorite() 更是挂起调用，期间切新片必致旧片结果写到新片态上。
        val toggleKey = currentKey
        val toggleGen = generation
        toggleJob = viewModelScope.launch {
            try {
                val favorite = Favorite(
                    cover = current.poster,
                    title = current.title,
                    source_name = current.source_name,
                    total_episodes = current.episodes.size,
                    search_title = currentTitle,
                    year = current.year,
                )
                // B4-P1-1：收藏键用 init 的 currentKey（进入时的源/标题），不
                // 再按当前结果重算——否则切源后收藏存到另一把键下，init 查
                // isFavorite 时查不到，收藏态永远对不上（旧 P2-16 做法已废弃）。
                // B12-P2-4：用切换开始时快照的 toggleKey，不在 launch 体内重读
                // currentKey——init() 若已切新片，重读会把切换作用到新片上。
                // B-P0-1：服务端写失败时 toggle 抛错（见 FavoritesRepository），
                // 这里接住给失败提示、不崩溃，收藏态保持原值。
                val nowFavorite = favoritesRepository.toggle(toggleKey, favorite)
                if (!shouldApplyToggleResult(toggleKey, currentKey, toggleGen, generation)) return@launch
                _uiState.value = _uiState.value.copy(isFavorite = nowFavorite, favoriteError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 第 7 轮跟进 D-P2-13：失败时重读一次 isFavorite()，让 UI 显示
                // 服务端实态——数据层写后校验分叉时已先按服务端实态更新 lastKnown
                // 再抛错，重读读到的就是实态；重读本身失败时保留切换前旧态，
                // 不二次崩溃（见 resolveFavoriteAfterToggleFailure）。
                val reRead: Boolean? = try {
                    // B12-P2-4：重读按切换开始时快照的 key（被切走的旧片），
                    // 不按可能已变的 currentKey——读新片的态再丢弃是白读，
                    // 读旧片的态才是本次切换的服务端实态（守卫随后才决定写不写）。
                    favoritesRepository.isFavorite(toggleKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // B12-P2-4：重读是挂起调用，期间切新片时旧结果不得落到新片态上。
                if (!shouldApplyToggleResult(toggleKey, currentKey, toggleGen, generation)) return@launch
                _uiState.value = _uiState.value.copy(
                    isFavorite = resolveFavoriteAfterToggleFailure(reRead, state.isFavorite),
                    favoriteError = nextToastEvent("收藏失败，请检查网络后重试"),
                )
            } finally {
                favoriteToggleGuard.release()
                _uiState.update { it.copy(isFavoriteToggling = false) }
            }
        }
    }

    fun consumeFavoriteError() {
        _uiState.update { it.copy(favoriteError = null) }
    }

    // B33-P2-2：markSourceAsFailed 已删——全仓零调用方（B31-P2-1 起失败源
    // 回传走 SavedStateHandle：写方 OrionNavHost，读方本类 init 的
    // SAVED_FAILED_SOURCES collect + mergePlaybackFailedSources）；
    // failedSources 字段与 DetailScreen 的标灰渲染是 B31 接线在用的，保留。
    private fun friendlyError(e: Exception): String = mapDetailError(e.message)
}

/**
 * P1-2：详情页“播放”应带的位置（毫秒）。
 * 只有当前选中集等于断点记录集时才续播；换集后断点失效，从 0 开始。
 * 纯逻辑，可单测。
 */
fun resumePositionMs(
    selectedEpisode: Int,
    resumeEpisode: Int?,
    resumePositionSeconds: Long?,
): Long {
    if (resumeEpisode == null || resumePositionSeconds == null) return 0L
    if (selectedEpisode != resumeEpisode) return 0L
    return (resumePositionSeconds * 1000).coerceAtLeast(0L)
}

/**
 * 第 7 轮跟进 D7-P2-10：详情收藏按钮的三态映射结果（可单测，见
 * [DetailViewModel.mapFavoriteState]）。
 */
data class FavoriteButtonState(
    val isFavorite: Boolean,
    /** 未知态：按钮置灰禁用，不显示“未收藏”。 */
    val unknown: Boolean,
)

data class DetailUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<SearchResult> = emptyList(),
    /** (source, source_name) 列表，与 results 同序。 */
    val sources: List<Pair<String, String>> = emptyList(),
    val current: SearchResult? = null,
    val allSourcesLoaded: Boolean = false,
    val failedSources: Set<String> = emptySet(),
    val selectedEpisode: Int = 0,
    val selectedGroup: Int = 0,
    val isFavorite: Boolean = false,
    /**
     * B25-P2-3：第 7 轮 D7-P2-10 定“UNKNOWN（从未查过）不得画成‘未收藏’”——
     * 之前默认值 false，init 重置与初始态共用它，首选源结果一到按钮即按
     * “未收藏”显示且可点，但真正收藏态要等全量搜索跑完（最长 120 秒）
     * 后才读回；窗口内点按 toggleFavorite() 是读-改-写翻转，会把已收藏
     * 项反向取消。默认值改 true：读回前按钮置灰禁用（见 DetailScreen），
     * 读回完成时 mapFavoriteState 已按三态写回，无需改读路径。
     */
    val isFavoriteUnknown: Boolean = true,
    /** 第 6 轮 P1-2：收藏切换进行中（防连按；进行中收藏按钮禁用）。 */
    val isFavoriteToggling: Boolean = false,
    /** B-P0-1：收藏切换失败时的一次性提示（2 秒 Toast，UI 消费后清掉）。 */
    val favoriteError: ToastEvent? = null,
    /** P1-2：断点记录的集号（null = 无断点）；只有选中它时“播放”才带位置。 */
    val resumeEpisode: Int? = null,
    /** P1-2：断点记录的已播秒数（null = 无断点）。 */
    val resumePositionSeconds: Long? = null,
)
