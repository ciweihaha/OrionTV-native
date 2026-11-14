package com.oriontv.data.repo

import com.oriontv.core.util.AppLog
import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.UnauthorizedException
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 登录会话仓库（ui-design.md §5.8，照搬 RN authStore）：
 * 登录弹窗 + 自动续期 + 退出登录；凭据放 EncryptedSharedPreferences，
 * 登录标记位 authCookies 只存 "1"/""；真 cookie 在 OkHttp CookieJar 里。
 * 401 续期：用已保存凭据重登（并发去重，只跑一次）；失败才弹登录框。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val moonTvApi: AuthApi,
    private val settingsRepository: AuthSettings,
    private val credentialsStore: CredentialStore,
    private val cookieJar: InMemoryCookieJar,
) {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val restoreMutex = Mutex()
    /** 第 3 轮收尾 B-P2-17：在途恢复由各方共享（见 restoreSession）。 */
    private var restoreDeferred: Deferred<Boolean>? = null
    /** 恢复/配置重试用的后台作用域（与调用方生命周期解耦，共享恢复不随等待方取消）。 */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 第 3 轮收尾 B-P1-8：冷启动配置拉取失败后的一次延迟重试任务（只排一个）。 */
    private var configRetryJob: Job? = null

    init {
        // MoonTvApi 的 401 续期回调：静默重登。
        moonTvApi.onUnauthorized = { restoreSession() }
    }

    /** 登录：成功写标记位 + 存凭据（供下次自动续期），失败返回原因。 */
    suspend fun login(username: String, password: String): Result<Unit> {
        val baseUrl = settingsRepository.currentApiBaseUrl()
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("还没有配置服务器地址"))
        _loginError.value = null
        return try {
            when (val result = moonTvApi.login(baseUrl, username, password)) {
                MoonTvApi.LoginResult.Success -> {
                    // D-P2-17：凭据保存失败（Keystore 异常）不再误报“登录失败”——
                    // 服务端会话已建、Cookie 已在 Jar 里，本次登录成功；下次冷启动
                    // 静默重登会失败、再提示登录，日志里留一条可见记录。
                    runCatching { credentialsStore.save(username, password) }.onFailure { e ->
                        AppLog.w("AuthRepository", "登录成功但凭据保存失败，下次冷启动需重新登录：${e.message}")
                    }
                    // B6-P2-5：setAuthCookies 抛错不再报成“登录失败”——服务端会话
                    // 已建、Cookie 已在 Jar 里，本次登录成功；标记丢了只影响下次
                    // 冷启动静默恢复（会再提示登录），与凭据保存失败同款处理。
                    runCatching { settingsRepository.setAuthCookies("1") }.onFailure { e ->
                        AppLog.w("AuthRepository", "登录成功但登录标记保存失败，下次冷启动需重新登录：${e.message}")
                    }
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    Result.success(Unit)
                }
                // B4-P1-2：只有 401（或 2xx 但 ok=false）才报“账号或密码错误”。
                MoonTvApi.LoginResult.WrongCredentials -> {
                    _loginError.value = "账号或密码错误"
                    Result.failure(IllegalStateException("账号或密码错误"))
                }
                // 5xx/超时/网络错：MoonTvApi 内已重试一次，仍失败则报网络类错误，不误导改密码。
                is MoonTvApi.LoginResult.Transient -> {
                    val message = "登录失败：${result.cause.message ?: "网络错误"}，请检查网络后重试"
                    _loginError.value = message
                    Result.failure(IllegalStateException(message, result.cause))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "登录失败：${e.message ?: "网络错误"}"
            _loginError.value = message
            Result.failure(IllegalStateException(message, e))
        }
    }

    /**
     * 退出登录：远程登出失败也不留会话残留（finally 无条件清）。
     * 只清标记位等于没退：已保存凭据还在，重启后静默重登会直接登回去；
     * CookieJar 里的会话 cookie 也还在（P0-4）。三处都要清。
     */
    suspend fun logout() {
        // B5-P2-3：DataStore 读失败按无地址处理，不让冷启动/登出崩溃。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        try {
            if (baseUrl.isNotBlank()) moonTvApi.logout(baseUrl)
        } finally {
            // B-P2-18：clear() 已是 suspend，runCatching 的非 suspend 块里调不了，改 try/catch。
            try { credentialsStore.clear() } catch (_: Exception) { }
            runCatching { cookieJar.clear() }
            // B5-P2-3：标记清除失败也不拦登出收尾（_isLoggedIn 必须落 false）。
            try { settingsRepository.setAuthCookies("") } catch (_: Exception) { }
            _isLoggedIn.value = false
        }
    }

    /**
     * 冷启动检查：无地址 → 未登录；有登录标记 → 先轻量校验会话（B-P2-16），
     * 有效才算已登录，过期走静默重登；
     * 无标记 → 有保存凭据就静默重登（localstorage 无密码服务传空密码也算一次尝试），
     * 重登失败才弹登录框。
     *
     * 注：第 5 轮 D-P2-7（先读标记、server-config 放后台刷新）已被 B-P1-8 的设计取代：
     * 同步拉一次配置（30 秒失败缓存）+ 失败时 31 秒后延迟重试，不再双通道刷新。
     */
    suspend fun checkLoginStatus() {
        // B5-P2-3：DataStore 读失败按未配置/未拉取处理，冷启动不崩溃；
        // 取消原样抛出（runOrDefault 内处理）。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        if (baseUrl.isBlank()) {
            _isLoggedIn.value = false
            _showLoginModal.value = false
            return
        }
        val config = runOrDefault(null) { settingsRepository.refreshServerConfig() }
        // B-P1-8：冷启动拉取失败且 StorageType 仍未知时，31 秒后（越过 30 秒
        // 失败缓存）再试一次，不把进程钉死在本地模式。
        if (config == null && runOrDefault(false) { settingsRepository.isServerConfigUnknown() }) {
            scheduleConfigRetry()
        }
        val marker = runOrDefault("") { settingsRepository.currentAuthCookies() }
        if (marker.isNotBlank()) {
            when (verifySession(baseUrl)) {
                SessionCheck.VALID -> {
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    return
                }
                SessionCheck.UNREACHABLE -> {
                    // 服务端暂时不可达：无法判定会话，沿用标记位已登录，
                    // 不因一次网络抖动弹登录框。
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    return
                }
                SessionCheck.EXPIRED -> {
                    // 掉到下面的静默重登。
                }
            }
        }
        val restored = restoreSession()
        if (!restored) {
            _isLoggedIn.value = false
            _showLoginModal.value = true
        }
    }

    /** B-P1-8：冷启动配置失败后的一次延迟重试（31 秒，越过 30 秒失败缓存）。 */
    private fun scheduleConfigRetry() {
        if (configRetryJob?.isActive == true) return
        configRetryJob = restoreScope.launch {
            delay(CONFIG_RETRY_DELAY_MS)
            // B4-P2-17：31 秒后配置重拉成功时重跑登录恢复——否则冷启动时
            // 配置未到、restoreSession 已失败，登录弹窗一直滞留；
            // 重跑成功时 performRestore 会把弹窗关掉，失败则弹窗继续留着。
            val refreshed = runCatching { settingsRepository.refreshServerConfig() }.getOrNull()
            if (refreshed != null) {
                restoreSession()
            }
        }
    }

    /**
     * B-P2-16：冷启动轻量会话校验（复用已有的 getFavorites，不新增服务端接口）。
     * 401 续期回调里已试过一次静默重登，仍 401 即会话过期；网络错则算不可达。
     */
    private suspend fun verifySession(baseUrl: String): SessionCheck {
        return try {
            moonTvApi.getFavorites(baseUrl)
            SessionCheck.VALID
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnauthorizedException) {
            SessionCheck.EXPIRED
        } catch (_: Exception) {
            SessionCheck.UNREACHABLE
        }
    }

    /** 会话校验结果：有效 / 已过期 / 服务端不可达（无法判定）。 */
    private enum class SessionCheck { VALID, EXPIRED, UNREACHABLE }

    /**
     * 用已保存凭据静默重登；无凭据时对无密码服务试一次空登录。
     * 第 3 轮收尾 B-P2-17：并发 401 不再各回各的旧值——在途恢复由 Deferred 共享，
     * 等待方挂起等恢复完成后再取结果；重入（401 回调发生在恢复自身的 login 里）
     * 直接回当前值，不自等自己死锁。
     */
    suspend fun restoreSession(): Boolean {
        val deferred = restoreMutex.withLock {
            val active = restoreDeferred?.takeIf { it.isActive }
            if (active != null) {
                if (active === coroutineContext[Job]) return _isLoggedIn.value
                return@withLock active
            }
            restoreScope.async { performRestore() }.also { restoreDeferred = it }
        }
        return deferred.await()
    }

    private suspend fun performRestore(): Boolean {
        // B5-P2-3：同 checkLoginStatus，DataStore 读失败按未恢复处理。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        if (baseUrl.isBlank()) return false
        val saved = try { credentialsStore.load() } catch (_: Exception) { null }
        val ok = try {
            // B4-P1-2：静默恢复只认 Success；Transient/WrongCredentials 都算未恢复。
            moonTvApi.login(baseUrl, saved?.first, saved?.second) is MoonTvApi.LoginResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            // B5-P2-3：标记写失败不推翻已建好的会话（本次登录成功），只记日志；
            // 下次冷启动静默重登会失败、再提示登录。
            try {
                settingsRepository.setAuthCookies("1")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("AuthRepository", "静默恢复成功但登录标记保存失败，下次冷启动需重新登录：${e.message}")
            }
            _isLoggedIn.value = true
            _showLoginModal.value = false
            true
        } else {
            _isLoggedIn.value = false
            false
        }
    }

    /**
     * B5-P2-3：DataStore 读写失败回落默认值，协程取消原样抛出
     *（同 Repositories.runOrDefault，那个是 private、这里各写一份）。
     */
    private suspend fun <T> runOrDefault(default: T, block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            default
        }
    }

    companion object {
        /** B-P1-8：冷启动配置延迟重试间隔（越过 SettingsRepository 的 30 秒失败缓存）。 */
        const val CONFIG_RETRY_DELAY_MS = 31_000L
    }

    fun showLogin() {
        _loginError.value = null
        _showLoginModal.value = true
    }

    fun hideLogin() {
        _showLoginModal.value = false
    }
}
