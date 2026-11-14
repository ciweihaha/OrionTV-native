package com.oriontv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.core.util.ApiUrlPolicy
import com.oriontv.core.util.AppLog
import com.oriontv.data.local.CredentialsStore
import com.oriontv.data.repo.AuthRepository
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.remoteinput.RemoteInputManager
import com.oriontv.feature.remoteinput.RemoteInputState
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** 片头时长档位（RN INTRO_SKIP_OPTIONS）：0=不跳，只用手动标记。 */
val INTRO_SKIP_OPTIONS = listOf(0, 30, 60, 90, 120)

/**
 * 设置 ViewModel（ui-design.md §5.7，plan §6 M3）：
 * 5 个 section 的真实读写；源地址保存后立即生效并清除登录态
 *（先对旧地址登出，再写新地址、清凭据、清登录标记、刷新 server-config）；
 * 播放开关即改即存；远程输入开关随启随停服务。
 * 手工焦点机在 SettingsScreen（currentFocusIndex 状态机，只响应松键）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val credentialsStore: CredentialsStore,
    private val remoteInputManager: RemoteInputManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(isLoading = true))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 远程输入服务状态（设置页远程输入 section 直接读）。 */
    val remoteInputState: StateFlow<RemoteInputState> = remoteInputManager.state

    init {
        viewModelScope.launch {
            // B6-P2-9：combine/collect 加 try/catch——DataStore 流抛错时旧实现
            // 让协程直接死掉，isLoading 恒 true、设置页永久空白；现在落错误态。
            try {
                // 显式类型参数：9 条流元素类型不一（String/Boolean/Int），Kotlin 2.4 起
                // 不再允许把 reified 类型参数推断为交集类型。
                combine<Any?, SettingsUiState>(
                settingsRepository.apiBaseUrl,
                settingsRepository.m3uUrl,
                settingsRepository.nextEpisodeOverlayEnabled,
                settingsRepository.autoSkipIntro,
                settingsRepository.introSkipSeconds,
                settingsRepository.incognitoMode,
                settingsRepository.backgroundPlayback,
                settingsRepository.speedMemory,
                settingsRepository.remoteInputEnabled,
            ) { values ->
                val api = values[0] as String
                val m3u = values[1] as String
                SettingsUiState(
                    isLoading = false,
                    apiUrl = api,
                    m3uUrl = m3u,
                    savedApiUrl = api,
                    savedM3uUrl = m3u,
                    nextEpisodeOverlay = values[2] as Boolean,
                    autoSkipIntro = values[3] as Boolean,
                    introSkipSeconds = values[4] as Int,
                    incognitoMode = values[5] as Boolean,
                    backgroundPlayback = values[6] as Boolean,
                    speedMemory = values[7] as Boolean,
                    remoteInputEnabled = values[8] as Boolean,
                )
            }.collect { loaded ->
                // 保留用户正在编辑的草稿：只在首次加载或已保存值变化时同步输入框。
                _uiState.update { current ->
                    val draftApi = if (current.isLoading || current.apiUrl == current.savedApiUrl) {
                        loaded.apiUrl
                    } else {
                        current.apiUrl
                    }
                    val draftM3u = if (current.isLoading || current.m3uUrl == current.savedM3uUrl) {
                        loaded.m3uUrl
                    } else {
                        current.m3uUrl
                    }
                    loaded.copy(apiUrl = draftApi, m3uUrl = draftM3u)
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "设置加载失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun onApiUrlChange(value: String) {
        _uiState.update { it.copy(apiUrl = value) }
    }

    fun onM3uUrlChange(value: String) {
        _uiState.update { it.copy(m3uUrl = value) }
    }

    /** 源地址是否被改过（保存按钮门控：没改不可点）。
     * B6-P2-10：按 trim 后比较——只改空格不算改过，不会亮起保存按钮、更不会误登出。 */
    val isDirty: Boolean
        get() = _uiState.value.let {
            it.apiUrl.trim() != it.savedApiUrl.trim() || it.m3uUrl.trim() != it.savedM3uUrl.trim()
        }

    /**
     * 保存源地址：立即生效并清除登录态。
     * 顺序：先对旧地址登出（finally 里清标记）→ 写新地址 → 清保存的凭据 →
     * 刷新 server-config（新地址的 StorageType 分支随即生效）。
     */
    fun saveSourceAddresses() {
        val state = _uiState.value
        // C8-P2-5：加载完成前直接返回——UI 侧按钮已禁用，这里是防御
        // （加载中的 draft 还是空串/旧值，存了会把已保存地址清掉）。
        if (state.isLoading) return
        if (state.isSaving || !isDirty) return
        // B6-P1-1：非法地址直接拒绝保存——旧实现照单全收还先登出清掉登录态，
        // 用户填错一个字符就丢登录。空地址允许（= 不配服务器，本地模式）。
        if (state.apiUrl.trim().isNotBlank() &&
            ApiUrlPolicy.check(state.apiUrl.trim()) is ApiUrlPolicy.Check.Invalid
        ) {
            _uiState.update {
                it.copy(toast = nextToastEvent("服务器地址无效，请输入 http(s):// 开头的地址"))
            }
            return
        }
        // B7-P2-3：isSaving 守卫必须在 launch 前同步置位——旧实现在 launch
        // 体内才置位，快速双击两次调用都通过守卫、并发跑两路保存。
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                // B6-P2-10：按 trim 后比较——只改空格不算换地址，不误登出。
                val apiChanged = state.apiUrl.trim() != state.savedApiUrl.trim()
                if (apiChanged) {
                    // B7-P2-9：登出失败（旧服务器不可达）不再中止保存——logout
                    // 的 finally 里本地凭据已清，继续写新地址；否则一次网络
                    // 抖动就丢登录态、新地址也没存上。
                    // 注：不能改成“先写新地址再登出”——logout() 读
                    // currentApiBaseUrl()，先写会让登出打到新服务器上。
                    try {
                        authRepository.logout()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("SettingsViewModel", "保存源地址：登出旧服务器失败，继续保存：${e.message}")
                    }
                    // B-P2-18：clear() 已是 suspend，runCatching 的非 suspend 块里调不了。
                    try { credentialsStore.clear() } catch (_: Exception) { }
                }
                try {
                    settingsRepository.setApiBaseUrl(state.apiUrl.trim())
                    settingsRepository.setM3uUrl(state.m3uUrl.trim())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // B7-P2-9：写新地址失败时回滚旧地址（登出已无法回滚，
                    // 至少地址不丢），再按原路报“保存失败”。
                    runCatching {
                        settingsRepository.setApiBaseUrl(state.savedApiUrl)
                        settingsRepository.setM3uUrl(state.savedM3uUrl)
                    }
                    throw e
                }
                // B4-P1-3：换源地址先把 storageType 清为空（未知态），旧服务
                // 的 StorageType 不残留到新服务上；拉取失败即保持未知，下次
                // 数据访问自动重拉，成功前按本地模式兜底并提示用户。
                var configFailed = false
                if (apiChanged) {
                    settingsRepository.clearStorageType()
                }
                if (state.apiUrl.trim().isNotBlank()) {
                    // B6-P2-10：30 秒失败缓存内的 null 不是“本次拉取失败”——
                    // 缓存期内不发请求、不报配置失败，下次数据访问越过缓存再试。
                    val failureCached =
                        settingsRepository.isConfigFailureCachedFor(state.apiUrl.trim())
                    if (!failureCached &&
                        settingsRepository.refreshServerConfig() == null && apiChanged
                    ) {
                        configFailed = true
                    }
                }
                // D-P2-8：公网 http 地址保存时提示明文风险（局域网/回环除外）；
                // 只提示、不拦截保存。
                val httpWarning =
                    if (ApiUrlPolicy.check(state.apiUrl.trim()) is ApiUrlPolicy.Check.PlainHttpPublic) {
                        "，注意：http 明文地址，登录密码将明文传输"
                    } else {
                        ""
                    }
                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        savedApiUrl = state.apiUrl.trim(),
                        savedM3uUrl = state.m3uUrl.trim(),
                        // B5-P2-9：只在用户保存期间没改过输入框时才回写；否则保留
                        // 用户刚敲的字，不拿保存开始时的旧快照覆盖。
                        apiUrl = if (current.apiUrl == state.apiUrl) state.apiUrl.trim() else current.apiUrl,
                        m3uUrl = if (current.m3uUrl == state.m3uUrl) state.m3uUrl.trim() else current.m3uUrl,
                        toast = nextToastEvent(
                            when {
                                configFailed -> "已保存，登录状态已清除；服务器配置暂未拉取成功，已按本地模式运行$httpWarning"
                                apiChanged -> "已保存，登录状态已清除$httpWarning"
                                else -> "已保存$httpWarning"
                            },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                // B-P1-9 / D-P2-2：取消原样抛出，不把取消写成“保存失败”。
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, error = "保存失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    // ---- 播放设置：即改即存 ----

    /**
     * B7-P2-8：即改即存的写操作统一走这里——DataStore 写失败时记日志、不崩溃；
     * 旧实现每个 setter 都是裸 launch，写失败即未捕获异常、整个协程崩掉。
     */
    private fun saveSetting(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("SettingsViewModel", "保存设置失败：$tag：${e.message}")
            }
        }
    }

    fun setNextEpisodeOverlay(value: Boolean) =
        saveSetting("nextEpisodeOverlay") { settingsRepository.setNextEpisodeOverlayEnabled(value) }

    fun setAutoSkipIntro(value: Boolean) =
        saveSetting("autoSkipIntro") { settingsRepository.setAutoSkipIntro(value) }

    fun setSpeedMemory(value: Boolean) =
        saveSetting("speedMemory") { settingsRepository.setSpeedMemory(value) }

    fun setIncognitoMode(value: Boolean) =
        saveSetting("incognitoMode") { settingsRepository.setIncognitoMode(value) }

    fun setBackgroundPlayback(value: Boolean) =
        saveSetting("backgroundPlayback") { settingsRepository.setBackgroundPlayback(value) }

    /** 片头时长：点按循环切换档位（0/30/60/90/120 秒，0=不跳）。 */
    fun cycleIntroSkipSeconds() {
        val current = _uiState.value.introSkipSeconds
        val idx = INTRO_SKIP_OPTIONS.indexOf(current)
        val next = INTRO_SKIP_OPTIONS[(idx + 1) % INTRO_SKIP_OPTIONS.size]
        saveSetting("introSkipSeconds") { settingsRepository.setIntroSkipSeconds(next) }
    }

    /** 远程输入开关：存设置，随启随停 12346 服务。 */
    fun setRemoteInputEnabled(value: Boolean) {
        saveSetting("remoteInputEnabled") {
            settingsRepository.setRemoteInputEnabled(value)
            if (value) {
                remoteInputManager.start()
            } else {
                remoteInputManager.stop()
            }
        }
    }

    /** 开机恢复：如果设置里开着远程输入，把服务拉起来（失败时设置页红字提示）。 */
    fun restoreRemoteInputIfEnabled() {
        saveSetting("restoreRemoteInput") {
            if (settingsRepository.remoteInputEnabled.first()) {
                remoteInputManager.start()
            }
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }
}

data class SettingsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** 一次性提示（Toast，2 秒消失，UI 消费后清掉）。 */
    val toast: ToastEvent? = null,
    val apiUrl: String = "",
    val m3uUrl: String = "",
    val savedApiUrl: String = "",
    val savedM3uUrl: String = "",
    val nextEpisodeOverlay: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val introSkipSeconds: Int = 90,
    val speedMemory: Boolean = true,
    val incognitoMode: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val remoteInputEnabled: Boolean = false,
)
