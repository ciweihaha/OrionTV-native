package com.oriontv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OrionTV 设计系统颜色，照抄 plan §3.1（以 v1.7.2 为准，只深色）。
 */
object OrionColors {
    val Background = Color(0xFF151718)
    val Text = Color(0xFFECEDEE)
    val Primary = Color(0xFF00BB5E)
    val Link = Color(0xFF0A7EA4)
    val Border = Color(0xFF333333)
    val Icon = Color(0xFF9BA1A6)

    /** 卡片/弹窗底色（RN 侧加载态 #222 底色）。 */
    val Surface = Color(0xFF222222)
    val SurfaceVariant = Color(0xFF1E1F20)
    val TextSecondary = Color(0xFF9BA1A6)
    val OnPrimary = Color(0xFFFFFFFF)
    val Error = Color(0xFFE5484D)
}
