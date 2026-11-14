package com.oriontv.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * OrionTV 主题：只深色，无切换。颜色照抄 plan §3.1。
 *
 * 圆角：卡片海报 8dp；布局留白：顶栏左右防 overscan（32/48px）。
 */
private val OrionColorScheme: ColorScheme
    @Composable
    get() = darkColorScheme(
        primary = OrionColors.Primary,
        onPrimary = OrionColors.OnPrimary,
        background = OrionColors.Background,
        onBackground = OrionColors.Text,
        surface = OrionColors.Surface,
        onSurface = OrionColors.Text,
        surfaceVariant = OrionColors.SurfaceVariant,
        onSurfaceVariant = OrionColors.TextSecondary,
        border = OrionColors.Border,
        error = OrionColors.Error,
    )

object OrionDimens {
    /** 卡片海报圆角 8dp。 */
    val CardCornerRadius = 8.dp

    /** 网格间距 16dp。 */
    val GridSpacing = 16.dp

    /** 顶栏左右留白，防 overscan。 */
    val OverscanPadding = PaddingValues(horizontal = 48.dp)
}

@Composable
fun OrionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrionColorScheme,
        content = content,
    )
}
