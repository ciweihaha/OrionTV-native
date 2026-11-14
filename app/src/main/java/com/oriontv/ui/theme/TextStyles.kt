package com.oriontv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号层级，照抄 plan §3.1：默认 16 / 行高 24、标题 32 bold、副标题 20 bold。
 * TV 下 scale = 宽/1000（1920 宽 ≈ 1.92），由调用方按屏幕宽度换算。
 */
object OrionTextStyles {
    val Body = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = OrionColors.Text,
    )
    val Title = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = OrionColors.Text,
    )
    val Subtitle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = OrionColors.Text,
    )
    val Caption = TextStyle(
        fontSize = 14.sp,
        color = OrionColors.TextSecondary,
    )
    val CardTitle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = OrionColors.Text,
    )
}
