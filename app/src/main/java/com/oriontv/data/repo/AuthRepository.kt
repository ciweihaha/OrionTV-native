package com.oriontv.data.repo

import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.local.CredentialsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录会话仓库（ui-design.md §5.8，照搬 RN authStore）：
 * 登录弹窗 + 自动续期 + 退出登录；凭据放 EncryptedSharedPreferences，
 * 登录标记位 authCookies 只存 "1"/""；真 cookie 在 OkHttp CookieJar 里。
 * 401 续期：用已保存凭据重登（并发去重，只跑一次）；失败才弹登录框。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
    private val credentialsStore: CredentialsStore,
) {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val restoreMutex = Mutex()
    private var restoreInFlight = false

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
            val ok = moonTvApi.login(baseUrl, username, password)
            if (!ok) {
                _loginError.value = "账号或密码错误"
                return Result.failure(IllegalStateException("账号或密码错误"))
            }
            credentialsStore.save(username, password)
            settingsRepository.setAuthCookies("1")
            _isLoggedIn.value = true
            _showLoginModal.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            val message = "登录失败：${e.message ?: "网络错误"}"
            _loginError.value = message
            Result.failure(IllegalStateException(message, e))
        }
    }

    /** 退出登录：远程登出失败也不留标记（finally 无条件清）。 */
    suspend fun logout() {
        val baseUrl = settingsRepository.currentApiBaseUrl()
        try {
            if (baseUrl.isNotBlank()) moonTvApi.logout(baseUrl)
        } finally {
            settingsRepository.setAuthCookies("")
            _isLoggedIn.value = false
        }
    }

    /**
     * 冷启动检查：无地址 → 未登录；有登录标记 → 已登录；
     * 无标记 → 有保存凭据就静默重登（localstorage 无密码服务传空密码也算一次尝试），
     * 重登失败才弹登录框。
     */
    suspend fun checkLoginStatus() {
        val baseUrl = settingsRepository.currentApiBaseUrl()
        if (baseUrl.isBlank()) {
            _isLoggedIn.value = false
            _showLoginModal.value = false
            return
        }
        settingsRepository.refreshServerConfig()
        val marker = settingsRepository.currentAuthCookies()
        if (marker.isNotBlank()) {
            _isLoggedIn.value = true
            _showLoginModal.value = false
            return
        }
        val restored = restoreSession()
        if (!restored) {
            _isLoggedIn.value = false
            _showLoginModal.value = true
        }
    }

    /** 用已保存凭据静默重登；无凭据时对无密码服务试一次空登录。 */
    suspend fun restoreSession(): Boolean {
        if (restoreInFlight) return _isLoggedIn.value
        return restoreMutex.withLock {
            if (restoreInFlight) return@withLock _isLoggedIn.value
            restoreInFlight = true
            try {
                val baseUrl = settingsRepository.currentApiBaseUrl()
                if (baseUrl.isBlank()) return@withLock false
                val saved = runCatching { credentialsStore.load() }.getOrNull()
                val ok = try {
                    moonTvApi.login(baseUrl, saved?.first, saved?.second)
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    settingsRepository.setAuthCookies("1")
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    true
                } else {
                    _isLoggedIn.value = false
                    false
                }
            } finally {
                restoreInFlight = false
            }
        }
    }

    fun showLogin() {
        _loginError.value = null
        _showLoginModal.value = true
    }

    fun hideLogin() {
        _showLoginModal.value = false
    }
}
