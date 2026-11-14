package com.oriontv.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 确认弹窗（骨架：半透明遮罩 + 居中卡片 + 确认/取消）。
 * 焦点锚点：打开时焦点落在确认按钮上（M1 接 FocusRequester）。
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(500.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OrionColors.Surface)
                .padding(24.dp),
        ) {
            Text(text = title, style = OrionTextStyles.Subtitle)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, style = OrionTextStyles.Body)
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                StyledButton(text = confirmText, onClick = onConfirm)
                Spacer(modifier = Modifier.width(16.dp))
                StyledButton(text = cancelText, onClick = onCancel)
            }
        }
    }
}

/** Toast（3 秒消息条，plan §3.3 第 9 条定时器数字之一）。 */
@Composable
fun OrionToast(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OrionColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = OrionTextStyles.Body)
    }
}
