package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏上键盘纵向排布回归测试（第 80 轮 v2.0.21 百度输入法电视版样式）：
 * 旧版（v2.0.16）是顶行 + 候选行 + 6 行 A–Z 字母 + 底行 + 切换行；新版是
 * 页签行 + 候选栏 + 3 行 QWERTY（或 4 行 9 键，末行 0 空格），左右列
 * （分词/?12/中·英、删除/完成）与键行同高、不再单占行高；符号板 4 行 +
 * 1 行底行（ABC/空格/退格/搜索）。
 * 华为 MediaPad M3 横屏只有 533dp 高，扣掉状态栏、页边距、搜索框后键盘只剩
 * 约 387dp 预算；下面按该预算验算：每种模式的键行高都不能小于下限，
 * 否则字会被切——以后再加行、加高固定行，测试会先失败。
 */
class KeyboardLayoutTest {

    @Test
    fun `fullKey zh QWERTY rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, showCandidateRow = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "全键中文 QWERTY 行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `fullKey en QWERTY rows fit M3 budget`() {
        // 英文模式没有候选栏。
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, showCandidateRow = false, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "全键英文 QWERTY 行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `nineKey zh digit rows fit M3 budget`() {
        // 9 键中文：3 行数字 + 1 行 0 空格，候选栏有（T9 切音节出词）。
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, showCandidateRow = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "9 键中文数字行高 $rowHeight dp 小于两行下限 $KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `nineKey en digit rows fit M3 budget`() {
        // 9 键英文：3 行数字 + 1 行 0 空格，候选栏有（T9 词表）。
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, showCandidateRow = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "9 键英文数字行高 $rowHeight dp 小于两行下限 $KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `symbol rows fit M3 budget`() {
        // 符号板：4 行符号 + 1 行底行（ABC/空格/退格/搜索），无候选栏。
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, showCandidateRow = false,
            availableHeightDp = KEYBOARD_BUDGET_M3_DP, extraFixedRows = 1,
        )
        assertTrue(
            "符号行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `fixed part leaves room for key rows`() {
        // 固定部分（不含可压缩的键行）在最 tall 的 9 键中文模式下也不应
        // 超过预算的六成，否则键行会被压到下限以下。
        val fixed = keyboardFixedHeightDp(keyRowCount = 4, showCandidateRow = true)
        assertTrue(
            "固定部分 $fixed dp 超过预算六成 ${KEYBOARD_BUDGET_M3_DP * 0.6f}",
            fixed <= KEYBOARD_BUDGET_M3_DP * 0.6f,
        )
    }

    // v2.0.23 贴底通栏：总高由内容定（固定部分 + 键行 × 64dp），不再由父级
    // fillMaxHeight 撑满；键行 weight 均分后正好是 64dp，且九键总高不超过
    // M3 平板 387dp 预算（贴底后上半区还剩空间放结果）。

    @Test
    fun `docked fullKey zh rows are exactly docked row height`() {
        val total = keyboardDockedHeightDp(keyRowCount = 3, showCandidateRow = true)
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, showCandidateRow = true, availableHeightDp = total,
        )
        assertEquals(KEYBOARD_DOCKED_KEY_ROW_HEIGHT_DP, rowHeight, 0.001f)
    }

    @Test
    fun `docked nineKey zh rows meet two-line minimum`() {
        // 9 键数字键是两行标签（数字 + 字母），行高下限 48dp。
        val total = keyboardDockedHeightDp(keyRowCount = 4, showCandidateRow = true)
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, showCandidateRow = true, availableHeightDp = total,
        )
        assertTrue(
            "贴底 9 键行高 $rowHeight dp 小于两行下限 $KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `docked tallest nineKey height fits M3 budget`() {
        // 九键 4 行是最高的贴底模式，总高不应超过 M3 平板 387dp 预算。
        val total = keyboardDockedHeightDp(keyRowCount = 4, showCandidateRow = true)
        assertTrue(
            "贴底 9 键总高 $total dp 超过 M3 预算 $KEYBOARD_BUDGET_M3_DP",
            total <= KEYBOARD_BUDGET_M3_DP,
        )
    }

    @Test
    fun `docked symbol rows meet minimum`() {
        // 符号板：3 行权重行 + 1 行固定底行（走 extraFixedRows）。
        val total = keyboardDockedHeightDp(
            keyRowCount = 3, showCandidateRow = false, extraFixedRows = 1,
        )
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, showCandidateRow = false,
            availableHeightDp = total, extraFixedRows = 1,
        )
        assertTrue(
            "贴底符号行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }
}
