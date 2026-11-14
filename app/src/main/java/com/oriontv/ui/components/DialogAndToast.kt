package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 确认弹窗（ui-design.md §5.8）：
 * 居中卡片 640dp 宽、16dp 圆角、60% 黑遮罩；焦点默认落在主按钮上
 * （M1 接 FocusRequester 锚点）；登录失败红字提示；弹窗淡入 150ms（§4）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    cancelText: String = "取消",
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val confirmRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { confirmRequester.requestFocus() }
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(150),
        ),
        exit = androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(150),
        ),
    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(OrionDimens.DialogWidth)
                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                .background(OrionColors.Overlay)
                .border(
                    width = 1.dp,
                    color = OrionColors.Divider,
                    shape = RoundedCornerShape(OrionDimens.PanelCornerRadius),
                )
                .padding(24.dp),
        ) {
            Text(text = title, style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                PrimaryButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.focusRequester(confirmRequester),
                )
                Spacer(modifier = Modifier.width(16.dp))
                SecondaryButton(text = cancelText, onClick = onCancel)
            }
        }
    }
    }
}

/** Toast（ui-design.md §5.8）：底部居中黑色胶囊，2 秒后消失，不遮挡焦点元素。 */
@Composable
fun OrionToast(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(OrionDimens.CapsuleRadius))
            .background(OrionColors.Background.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = OrionTextStyles.Body)
    }
}
