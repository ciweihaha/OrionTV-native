package com.oriontv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.core.util.ApiUrlPolicy
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
        }
    }

    fun onApiUrlChange(value: String) {
        _uiState.update { it.copy(apiUrl = value) }
    }

    fun onM3uUrlChange(value: String) {
        _uiState.update { it.copy(m3uUrl = value) }
    }

    /** 源地址是否被改过（保存按钮门控：没改不可点）。 */
    val isDirty: Boolean
        get() = _uiState.value.let { it.apiUrl != it.savedApiUrl || it.m3uUrl != it.savedM3uUrl }

    /**
     * 保存源地址：立即生效并清除登录态。
     * 顺序：先对旧地址登出（finally 里清标记）→ 写新地址 → 清保存的凭据 →
     * 刷新 server-config（新地址的 StorageType 分支随即生效）。
     */
    fun saveSourceAddresses() {
        val state = _uiState.value
        if (state.isSaving || !isDirty) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val apiChanged = state.apiUrl != state.savedApiUrl
                if (apiChanged) {
                    authRepository.logout()
                    // B-P2-18：clear() 已是 suspend，runCatching 的非 suspend 块里调不了。
                    try { credentialsStore.clear() } catch (_: Exception) { }
                }
                settingsRepository.setApiBaseUrl(state.apiUrl.trim())
                settingsRepository.setM3uUrl(state.m3uUrl.trim())
                // B4-P1-3：换源地址先把 storageType 清为空（未知态），旧服务
                // 的 StorageType 不残留到新服务上；拉取失败即保持未知，下次
                // 数据访问自动重拉，成功前按本地模式兜底并提示用户。
                var configFailed = false
                if (apiChanged) {
                    settingsRepository.clearStorageType()
                }
                if (state.apiUrl.trim().isNotBlank()) {
                    if (settingsRepository.refreshServerConfig() == null && apiChanged) {
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

    fun setNextEpisodeOverlay(value: Boolean) {
        viewModelScope.launch { settingsRepository.setNextEpisodeOverlayEnabled(value) }
    }

    fun setAutoSkipIntro(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoSkipIntro(value) }
    }

    fun setSpeedMemory(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSpeedMemory(value) }
    }

    fun setIncognitoMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setIncognitoMode(value) }
    }

    fun setBackgroundPlayback(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBackgroundPlayback(value) }
    }

    /** 片头时长：点按循环切换档位（0/30/60/90/120 秒，0=不跳）。 */
    fun cycleIntroSkipSeconds() {
        val current = _uiState.value.introSkipSeconds
        val idx = INTRO_SKIP_OPTIONS.indexOf(current)
        val next = INTRO_SKIP_OPTIONS[(idx + 1) % INTRO_SKIP_OPTIONS.size]
        viewModelScope.launch { settingsRepository.setIntroSkipSeconds(next) }
    }

    /** 远程输入开关：存设置，随启随停 12346 服务。 */
    fun setRemoteInputEnabled(value: Boolean) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
