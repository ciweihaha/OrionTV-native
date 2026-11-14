package com.oriontv.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oriontv.ui.components.ConfirmDialog

/**
 * 登录弹窗占位（宽 80%）。M1 补：用户名/密码输入、登录提交、401 自动弹框、退出登录。
 */
@Composable
fun LoginModal(
    onLogin: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmDialog(
        title = "登录",
        message = "登录弹窗（骨架占位，M1 实现）",
        confirmText = "登录",
        cancelText = "取消",
        onConfirm = { onLogin("", "") },
        onCancel = onDismiss,
        modifier = modifier,
    )
}
