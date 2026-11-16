package com.oriontv.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.ModalFadeIn
import com.oriontv.ui.components.consumeTaps
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 登录弹窗（ui-design.md §5.8）：
 * 居中卡片 640dp 宽、16dp 圆角、60% 黑遮罩；弹窗淡入 150ms（§4，C11-P2-3）；
 * 账号 + 密码 + 登录按钮，失败红字提示；焦点默认落在主按钮上
 * （M1 接 FocusRequester 锚点）；会话自动续期，仅过期时重新弹。
 */
@Composable
fun LoginModal(
    onLogin: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    // Jewel 第 9 轮 P2-9：账号/密码改 rememberSaveable——普通 remember 随组合
    // 处置而丢失，配置变更（旋转/语言切换）或进程重建时已输一半的内容全丢，
    // 用户得重敲；存档后重建即恢复。密码进存档的权衡：SavedState 与应用已
    // 有的 CredentialsStore 同属应用私有存储，且只是未提交的瞬态输入态、
    // 登录成功/弹窗关闭即随组合处置而丢，不作长期保存。
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val usernameRequester = remember { FocusRequester() }
    val passwordRequester = remember { FocusRequester() }
    val loginRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    // 焦点默认落在主按钮上（ui-design.md §5.8）。
    LaunchedEffect(Unit) { loginRequester.requestFocus() }

    val shownError = error ?: localError

    // C11-P2-3：入场淡入 150ms（设计稿 §4）——与其他弹窗同款 ModalFadeIn，
    // 只做入场、不改布局与焦点行为。
    ModalFadeIn {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f))
            // B29-P2-1：登录弹窗空白区点按在此消费掉，不漏到视频点按层。
            .consumeTaps(),
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
            // C5-P1-3：焦点陷阱（照 UpdateCard 的做法）——账号 → 密码 →
            // 登录 → 取消 → 账号循环，不出弹窗。输入框只接 up/down：
            // 左右键留给光标移动，不抢。
            LoginField(
                label = "账号",
                value = username,
                onValueChange = { username = it },
                keyboardType = KeyboardType.Text,
                masked = false,
                modifier = Modifier
                    .focusRequester(usernameRequester)
                    .focusProperties {
                        up = cancelRequester
                        down = passwordRequester
                    },
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoginField(
                label = "密码",
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                masked = true,
                modifier = Modifier
                    .focusRequester(passwordRequester)
                    .focusProperties {
                        up = usernameRequester
                        down = loginRequester
                    },
            )
            if (shownError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = shownError, style = OrionTextStyles.Body, color = OrionColors.Error)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                PrimaryButton(
                    text = "登录",
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            localError = "账号或密码不能为空"
                        } else {
                            localError = null
                            onLogin(username, password)
                        }
                    },
                    modifier = Modifier
                        .preferredFocus(loginRequester)
                        .focusProperties {
                            left = cancelRequester
                            right = cancelRequester
                            up = passwordRequester
                            down = cancelRequester
                        },
                )
                Spacer(modifier = Modifier.width(16.dp))
                SecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(cancelRequester)
                        .focusProperties {
                            left = loginRequester
                            right = loginRequester
                            up = passwordRequester
                            down = usernameRequester
                        },
                )
            }
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
    modifier: Modifier = Modifier,
) {
    // C4-P1-7：输入框也要有焦点态（品牌色 2dp 描边 + 背景提亮，与 orionFocus 同款），
    // 否则遥控器移上去看不出落在哪个框。
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(text = label, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrionDimens.CardCornerRadius))
                .background(if (focused) OrionColors.SurfaceVariant else OrionColors.Surface)
                .border(
                    width = if (focused) OrionDimens.FocusBorderWidth else 1.dp,
                    color = if (focused) OrionColors.Brand else OrionColors.Divider,
                    shape = RoundedCornerShape(OrionDimens.CardCornerRadius),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    // C10-P2-1：左右键只移光标，焦点不出弹窗——光标已在边界
                    // 时也消费松键（官方文档：光标到文本边界再按方向键会把
                    // 焦点交给相邻目标；弹窗的焦点陷阱是上/下循环，左右键逃逸
                    // 会落到弹窗底下的页面上）。只拦松键：光标移动发生在
                    // KeyDown，拦 KeyDown 会拦住光标移动（C7-P2-3 同款）。
                    .onKeyEvent { event ->
                        event.type == KeyEventType.KeyUp &&
                            shouldConsumeLoginFieldArrowKeyUp(event.key)
                    },
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

/** 登录弹窗输入框左右键松键是否消费（可单测，C10-P2-1）：左右键只移光标，
 * 焦点不出弹窗——光标已在边界时也消费，不像设置页那样放行回菜单
 *（弹窗的焦点陷阱是账号 → 密码 → 登录 → 取消的上/下循环，左右键逃逸
 * 会落到弹窗底下的页面上）。上下键不消费（走 focusProperties 的循环）。 */
internal fun shouldConsumeLoginFieldArrowKeyUp(key: Key): Boolean =
    key == Key.DirectionLeft || key == Key.DirectionRight
