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
 * 片头时长下一档（B19-P2-2：纯函数，可单测）——档位表内循环；未知值
 * （indexOf -1）时回到首档 0。
 */
internal fun nextIntroSkipSeconds(current: Int): Int {
    val idx = INTRO_SKIP_OPTIONS.indexOf(current)
    return INTRO_SKIP_OPTIONS[(idx + 1) % INTRO_SKIP_OPTIONS.size]
}

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

    /**
     * B19-P2-2：片头时长待定档位——cycleIntroSkipSeconds 点按时同步推进，
     * 下一档以它为准，不读 DataStore 流驱动的 _uiState（写盘落盘 + 流重发
     * 前还是旧值，快速连按两次会算出同一档、写两次同一值）；写盘落盘、流值
     * 追上后由 collect 清掉。写盘失败时残留待定值，下次点按仍以它推进、
     * 写成功即自愈（失败本身另有 Toast 提示）。
     */
    private var pendingIntroSkipSeconds: Int? = null

    /** 远程输入服务状态（设置页远程输入 section 直接读）。 */
    val remoteInputState: StateFlow<RemoteInputState> = remoteInputManager.state

    /** 配对成功事件（设置页二维码弹窗订阅：配对成功后自动关闭，搜索页同款）。 */
    val remoteInputHandshakes: kotlinx.coroutines.flow.SharedFlow<Unit> =
        remoteInputManager.handshakes

    /** 登录态（设置页“登录/退出登录”入口用，B11-P1-1）。 */
    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn

    /** 手动登录：弹全局登录弹窗（B11-P1-1）。 */
    fun showLogin() = authRepository.showLogin()

    /**
     * 手动退出登录（B11-P1-1）：远程登出失败只记日志，本地会话在
     * AuthRepository.logout() 的 finally 里已清干净（与
     * AuthViewModel.logout() 同款接法）；无论远程成败，本地都已退出。
     */
    fun logout() {
        viewModelScope.launch {
            try {
                authRepository.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("SettingsViewModel", "退出登录远程登出失败（本地会话已清理）：${e.message}")
            }
            _uiState.update { it.copy(toast = nextToastEvent("已退出登录")) }
        }
    }

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
                // B19-P2-2：待定档位优先于流值——点按后写盘落盘前的窗口内，
                // 其他流变化触发的重建也不能把乐观值打回旧档；流值追上待定值
                // 时清掉待定（写盘已落盘），之后以流值为准。
                val pending = pendingIntroSkipSeconds
                val effective = if (pending != null) {
                    if (loaded.introSkipSeconds == pending) pendingIntroSkipSeconds = null
                    loaded.copy(introSkipSeconds = pending)
                } else {
                    loaded
                }
                // 保留用户正在编辑的草稿：只在首次加载或已保存值变化时同步输入框。
                _uiState.update { current ->
                    val draftApi = if (current.isLoading || current.apiUrl == current.savedApiUrl) {
                        effective.apiUrl
                    } else {
                        current.apiUrl
                    }
                    val draftM3u = if (current.isLoading || current.m3uUrl == current.savedM3uUrl) {
                        effective.m3uUrl
                    } else {
                        current.m3uUrl
                    }
                    // B43-P2-1：合并式保留——流值只更新 DataStore 拥有的字段，
                    // 瞬时态（isSaving/error/toast）归 _uiState 所有，不被每次
                    // DataStore 发射整包清掉；否则保存中途一次写盘发射就把
                    // isSaving 清回 false、“保存中…”提前变回“保存”，窗口内可
                    // 并发跑第二路保存（绕过 B7-P2-3 守卫、登出打到新服务器，
                    // 违 B7-P2-9 注释禁令），保存失败红字/Toast 也会被任意一次
                    // 同文件写入无故清掉。
                    // 旧态不会赖住：error 在下次保存开始时清（saveSourceAddresses
                    // 同步置位处 error=null）、toast 由 UI 消费清掉（consumeToast，
                    // SettingsScreen 调用）；加载失败红字不适用——流抛错后
                    // collect 协程已结束，不会再有发射。
                    effective.copy(
                        apiUrl = draftApi,
                        m3uUrl = draftM3u,
                        error = current.error,
                        toast = current.toast,
                        isSaving = current.isSaving,
                    )
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
                    // Jewel 第 9 轮 P2-3：取消原样重抛——旧写法 catch (_: Exception)
                    // 把 CE 一并吞掉，后续 setSourceAddresses 照常写；与 B15-P2-2
                    // 同款口径（附属清凭据失败只吞非取消异常）。
                    try {
                        credentialsStore.clear()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                // B12-P2-3：新地址与 StorageType 清空一次原子写——旧实现先写
                // 两个地址、后清 StorageType，三次独立写，中间窗口里新地址配
                // 旧 StorageType；clearStorageType 自身又是两次独立写。现一次
                // edit {} 落四键（见 SettingsRepository.setSourceAddresses），
                // 写失败时四键全不落盘、回滚旧地址再报“保存失败”。
                // B4-P1-3：换源地址把 storageType 清为空（未知态），旧服务
                // 的 StorageType 不残留到新服务上；拉取失败即保持未知，下次
                // 数据访问自动重拉，成功前按本地模式兜底并提示用户。
                try {
                    settingsRepository.setSourceAddresses(
                        state.apiUrl.trim(),
                        state.m3uUrl.trim(),
                        apiChanged,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // B7-P2-9：写新地址失败时回滚旧地址（登出已无法回滚，
                    // 至少地址不丢），再按原路报“保存失败”。
                    runCatching {
                        settingsRepository.setSourceAddresses(
                            state.savedApiUrl,
                            state.savedM3uUrl,
                            false,
                        )
                    }
                        // B17-P2-1：回滚写失败仍不推翻“保存失败”的上报，但取消
                        // 异常原样重抛——catch 块内抛出的 CE 不会被同 try 的
                        // catch 再捕获，直接交由 launch 协程取消处理。
                        .onFailure { if (it is CancellationException) throw it }
                    throw e
                }
                var configFailed = false
                if (state.apiUrl.trim().isNotBlank()) {
                    // B6-P2-10：30 秒失败缓存内的 null 不是“本次拉取失败”——
                    // 缓存期内不发请求、不报配置失败，下次数据访问越过缓存再试。
                    val failureCached =
                        settingsRepository.isConfigFailureCachedFor(state.apiUrl.trim())
                    // B9-P2-2：同款——refreshServerConfig 入口的 DataStore 读
                    // 失败会抛错（D7-P2-9 只加固了它内部后半段），抛错按拉取失败
                    // 计（configFailed=true，走“已保存、按本地模式运行”），
                    // 不进外层 catch 的“保存失败”。
                    val serverConfig = try {
                        settingsRepository.refreshServerConfig()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("SettingsViewModel", "保存源地址：拉取 server-config 失败，按本地模式：${e.message}")
                        null
                    }
                    // B27-P2-1：refreshServerConfig() 返回 null 有两种含义——
                    // “已拉取过”（SettingsRepository.kt:125 的 !isServerConfigUnknown()
                    // 即 return null）与“拉取失败”无法区分；并发的另一路数据访问
                    // 可能已先抢到锁成功拉取，此时再判失败会误报“服务器配置暂未拉取
                    // 成功”。照 AuthRepository.checkLoginStatus() 同款范式复核一次
                    // isServerConfigUnknown()：仍未知才是真失败。
                    if (!failureCached && serverConfig == null && apiChanged &&
                        settingsRepository.isServerConfigUnknown()
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
                // B10-P1-1：换了 API 地址即已登出旧服务器，新地址上没有登录态——
                // 旧实现只 Toast“登录状态已清除”，进程内再无登录入口；保存成功
                // 且新地址非空时直接弹登录框（空地址 = 本地模式，不弹）。
                if (apiChanged && state.apiUrl.trim().isNotBlank()) {
                    authRepository.showLogin()
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
     * Jewel 第 9 轮 P2-7：写失败除记日志外给一次 Toast——开关的 checked 态由
     * DataStore 流驱动，写盘失败时开关视觉上弹回原位，用户不知道是“没点上”
     * 还是“保存失败”；与搜索历史写失败的 Toast 口径一致。开机恢复
     * （restoreRemoteInputIfEnabled）不是用户保存动作，失败不弹 Toast。
     */
    private fun saveSetting(tag: String, toastOnFailure: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("SettingsViewModel", "保存设置失败：$tag：${e.message}")
                if (toastOnFailure) {
                    _uiState.update { it.copy(toast = nextToastEvent("保存失败，请重试")) }
                }
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
        // B19-P2-2：以待定值（有）或当前态算下一档，同步记下待定并乐观更新
        // _uiState——两次 OK 在写盘时延内也各前进一档；流随后发出的值与乐观
        // 值一致，不会回跳。
        val current = pendingIntroSkipSeconds ?: _uiState.value.introSkipSeconds
        val next = nextIntroSkipSeconds(current)
        pendingIntroSkipSeconds = next
        _uiState.update { it.copy(introSkipSeconds = next) }
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
        saveSetting("restoreRemoteInput", toastOnFailure = false) {
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
