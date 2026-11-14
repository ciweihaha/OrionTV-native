package com.oriontv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号层级（ui-design.md §3）：
 * 页面标题 22sp 加粗 / 区块标题 18sp 加粗 / 卡片标题 15sp / 正文 14sp / 小字 12sp；
 * 行高 1.3–1.4。系统默认无衬线，不引入自定义字体。
 */
object OrionTextStyles {
    /** 页面标题 22sp 加粗。 */
    val PageTitle = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.6.sp,
        fontWeight = FontWeight.Bold,
        color = OrionColors.Text,
    )

    /** 区块标题 18sp 加粗。 */
    val SectionTitle = TextStyle(
        fontSize = 18.sp,
        lineHeight = 23.4.sp,
        fontWeight = FontWeight.Bold,
        color = OrionColors.Text,
    )

    /** 卡片标题 15sp。 */
    val CardTitle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 19.5.sp,
        color = OrionColors.Text,
    )

    /** 正文 14sp。 */
    val Body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.2.sp,
        color = OrionColors.Text,
    )

    /** 小字 12sp。 */
    val Small = TextStyle(
        fontSize = 12.sp,
        lineHeight = 15.6.sp,
        color = OrionColors.TextSecondary,
    )

    /** 导航轨小字 8sp（图标下方竖排标签）。 */
    val NavLabel = TextStyle(
        fontSize = 8.sp,
        lineHeight = 10.4.sp,
        color = OrionColors.TextSecondary,
    )

    // ---- 兼容旧名 ----
    /** 旧 Title 20sp → PageTitle 22sp。 */
    val Title = PageTitle

    /** 旧 LargeTitle 24sp → PageTitle 22sp。 */
    val LargeTitle = PageTitle

    /** 旧 Section 15sp 金色 → SectionTitle 18sp。 */
    val Section = SectionTitle

    /** 旧 Channel 16sp → Body 14sp。 */
    val Channel = Body

    /** 旧 Subtitle → SectionTitle。 */
    val Subtitle = SectionTitle

    /** 旧 Caption / Meta 12sp → Small。 */
    val Caption = Small
    val Meta = Small
}
