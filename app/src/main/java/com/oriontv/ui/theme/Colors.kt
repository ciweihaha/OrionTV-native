package com.oriontv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OrionTV 设计系统颜色。
 *
 * 唯一基准：ui-design.md §2（深色影院）。
 * 取代：① OrionTV-android 的深空底/星尘金；② CONTRACT.md §3 引用的 plan §3.1 RN 配色。
 */
object OrionColors {
    /** 背景：墨黑微暖，全屏底。 */
    val Background = Color(0xFF0C0E12)

    /** 表面：卡片、面板底。 */
    val Surface = Color(0xFF171A20)

    /** 浮层：弹窗、抽屉、控制条。 */
    val Overlay = Color(0xFF1F232C)

    /** 主文字：标题、重要信息。 */
    val Text = Color(0xFFF2F3F5)

    /** 次文字：简介、元数据。 */
    val TextSecondary = Color(0xFF9AA1AD)

    /** 弱文字：辅助、时间戳。 */
    val TextWeak = Color(0xFF6B7280)

    /** 品牌强调 / 焦点：熔橙。焦点描边、主按钮、选中态。 */
    val Brand = Color(0xFFFF6A3D)

    /** 直播角标：LIVE 红点。 */
    val LiveRed = Color(0xFFFF3B30)

    /** 正在播指示：绿点（EPG 正在播）。 */
    val PlayingGreen = Color(0xFF34D399)

    /** 分隔线：8% 白。 */
    val Divider = Color(0x14FFFFFF)

    /** 旧 SurfaceVariant → 浮层。 */
    val SurfaceVariant = Overlay

    /** 旧 Error。 */
    val Error = Color(0xFFE57373)

    /** 百度输入法电视版首选候选蓝底（v2.0.21 第 80 轮百度样式键盘）。 */
    val BaiduBlue = Color(0xFF306EFF)

    /** 百度样式键盘面板灰（深色主题下的灰色面板，比 Surface 略亮一档）。 */
    val KeyboardPanel = Color(0xFF262B34)
}
