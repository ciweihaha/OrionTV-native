package com.oriontv.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 登录弹窗（ui-design.md §5.8）：
 * 居中卡片 640dp 宽、16dp 圆角、60% 黑遮罩；
 * 账号 + 密码 + 登录按钮，失败红字提示；焦点默认落在主按钮上
 * （M1 接 FocusRequester 锚点）；会话自动续期，仅过期时重新弹。
 */
@Composable
fun LoginModal(
    onLogin: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(OrionDimens.DialogWidth)
                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                .background(OrionColors.Overlay)
                .padding(24.dp),
        ) {
            Text(text = "登录", style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(16.dp))
            LoginField(
                label = "账号",
                value = username,
                onValueChange = { username = it },
                keyboardType = KeyboardType.Text,
                masked = false,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoginField(
                label = "密码",
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                masked = true,
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = error!!, style = OrionTextStyles.Body, color = OrionColors.Error)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                PrimaryButton(
                    text = "登录",
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            error = "账号或密码不能为空"
                        } else {
                            error = null
                            onLogin(username, password)
                        }
                    },
                )
                Spacer(modifier = Modifier.width(16.dp))
                SecondaryButton(text = "取消", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    masked: Boolean,
) {
    Column {
        Text(text = label, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrionDimens.CardCornerRadius))
                .background(OrionColors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(text = "请输入$label", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                    }
                    innerTextField()
                },
            )
        }
    }
}
