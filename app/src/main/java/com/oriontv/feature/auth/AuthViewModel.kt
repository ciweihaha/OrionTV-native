package com.oriontv.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.repo.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
        viewModelScope.launch { authRepository.logout() }
    }

    fun showLogin() = authRepository.showLogin()
    fun hideLogin() = authRepository.hideLogin()
}
