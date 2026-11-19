package com.oriontv.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.repo.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 登录状态 ViewModel（v2.0.17）：登录完全内置、永不弹登录框。
 * 这里只剩冷启动检查的入口与登录态的只读暴露——登录弹窗（LoginModal）
 * 与手动登录/退出登录入口已随 v2.0.17 删除；登录失败时 AuthRepository
 * 在后台悄悄重试，UI 侧不处理登录。
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn

    /** 冷启动检查：无地址 → 未登录；有标记 → 轻量校验；无标记 → 静默重登。 */
    fun checkLoginStatus() {
        viewModelScope.launch { authRepository.checkLoginStatus() }
    }
}
