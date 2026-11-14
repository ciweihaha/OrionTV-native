package com.oriontv.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * OrionTV 主题：只深色，无切换。基准：ui-design.md §2/§3。
 *
 * 焦点态（§4）：描边 + 放大 + 阴影三件套——
 * 焦点卡片放大 1.08 + 2dp 品牌色描边 + 柔和阴影（elevation 8dp），120ms 缓动。
 * 焦点永不丢失：页面切换、数据刷新、弹窗关闭后自动回落到最近锚点。
 */
private val OrionColorScheme: ColorScheme
    @Composable
    get() = darkColorScheme(
        primary = OrionColors.Brand,
        onPrimary = OrionColors.Background,
        background = OrionColors.Background,
        onBackground = OrionColors.Text,
        surface = OrionColors.Surface,
        onSurface = OrionColors.Text,
        surfaceVariant = OrionColors.Overlay,
        onSurfaceVariant = OrionColors.TextSecondary,
        border = OrionColors.Divider,
        error = OrionColors.Error,
    )

object OrionDimens {
    /** 导航轨宽 96dp，常驻（ui-design.md §1）。 */
    val NavRailWidth = 96.dp

    /** 卡片圆角 12dp。 */
    val CardCornerRadius = 12.dp

    /** 面板圆角 16dp（弹窗、抽屉、详情海报）。 */
    val PanelCornerRadius = 16.dp

    /** 按钮与 chip 胶囊（999dp）。 */
    val CapsuleRadius = 999.dp

    /** 内容行之间 32dp。 */
    val RowSpacing = 32.dp

    /** 卡片之间 16dp。 */
    val CardSpacing = 16.dp

    /** chip 之间 12dp。 */
    val ChipSpacing = 12.dp

    /** 内容区安全边距：左右 48dp、上下 32dp（华为智慧屏不过切边）。 */
    val ContentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp)

    /** 顶栏/页面左右留白（兼容旧名）。 */
    val OverscanPadding = PaddingValues(horizontal = 48.dp)

    /** 安全边距 48dp（兼容旧名）。 */
    val SafeMargin = 48.dp

    /** 焦点放大 1.08（ui-design.md §4）。 */
    const val FocusScale = 1.08f

    /** 焦点描边 2dp 品牌色。 */
    val FocusBorderWidth = 2.dp

    /** 焦点阴影 8dp。 */
    val FocusElevation = 8.dp

    /** 直播频道面板宽 420dp，从右滑入（§5.4）。 */
    val LivePanelWidth = 420.dp

    /** 弹窗宽 640dp（§5.8）。 */
    val DialogWidth = 640.dp

    /** 选集网格每行 8 个（§5.2）。 */
    const val EpisodeGridColumns = 8

    /** 首页内容行每行 6 张卡（§5.1）。 */
    const val HomeRowCards = 6
}

@Composable
fun OrionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrionColorScheme,
        content = content,
    )
}
