package com.oriontv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 通用按钮 / 媒体按钮。
 * 焦点高亮：通用按钮聚焦 scale 1.1（plan §3.3 第 6 条，三套样式不要统一成一种）。
 */
@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = if (focused) 1.1f else 1f
                scaleY = if (focused) 1.1f else 1f
            },
    ) {
        Text(text = text, style = OrionTextStyles.Body)
    }
}

/** 播放控制条里的圆形媒体按钮（播放/暂停、下一集等），M1 补图标与行为。 */
@Composable
fun MediaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StyledButton(text = label, onClick = onClick, modifier = modifier)
}
