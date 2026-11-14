package com.oriontv.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.core.util.AppLog
import com.oriontv.data.repo.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** 登录 ViewModel：登录弹窗 + 自动续期 + 退出登录（逻辑在 AuthRepository）。 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
    val showLoginModal: StateFlow<Boolean> = authRepository.showLoginModal
    val loginError: StateFlow<String?> = authRepository.loginError

    fun checkLoginStatus() {
        viewModelScope.launch { authRepository.checkLoginStatus() }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch { authRepository.login(username, password) }
    }

    fun logout() {
        // 第 6 轮 D6-P2-14：AuthRepository.logout() 的契约是“远程登出抛错时
        // 照常抛（只保证清理执行）”，裸 launch 接不住会让 viewModelScope
        // 未捕获异常崩应用；这里接住记日志——本地会话在 finally 里已清干净。
        viewModelScope.launch {
            try {
                authRepository.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("AuthViewModel", "退出登录远程登出失败（本地会话已清理）：${e.message}")
            }
        }
    }

    fun showLogin() = authRepository.showLogin()
    fun hideLogin() = authRepository.hideLogin()
}
