package com.oriontv.feature.search

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏上键盘纵向排布回归测试（第 76 轮 v2.0.16）：
 * v2.0.15 的键盘是固定行高——顶行 + 候选行 + 6 行字母 + 底行 + 切换行 +
 * 搜索行 + 提示文案合计 545~632dp；但华为 MediaPad M3 横屏只有 533dp 高
 * （1600px @480dpi），扣掉状态栏、页边距、搜索框后键盘只剩约 387dp，
 * 底部的切换行、“搜索/收起键盘”行和提示文案全被切掉（用户照片：最后
 * 可见行是 9 键/空格行）。
 * v2.0.16 起字母/数字/符号行改 weight 均分剩余高度，固定行收紧；
 * 下面按 M3 的 387dp 预算验算：每种模式的键行高都不能小于下限，
 * 否则字会被切——以后再加行、加高固定行，测试会先失败。
 */
class KeyboardLayoutTest {

    @Test
    fun `fullKey zh letter rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 6, zhMode = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "全键中文键行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `fullKey en letter rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 6, zhMode = false, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "全键英文键行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `nineKey zh digit rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, zhMode = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "9 键中文数字行高 $rowHeight dp 小于两行下限 $KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `nineKey en digit rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 3, zhMode = false, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "9 键英文数字行高 $rowHeight dp 小于两行下限 $KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `symbol zh rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, zhMode = true, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "符号中文行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `symbol en rows fit M3 budget`() {
        val rowHeight = keyboardKeyRowHeightDp(
            keyRowCount = 4, zhMode = false, availableHeightDp = KEYBOARD_BUDGET_M3_DP,
        )
        assertTrue(
            "符号英文行高 $rowHeight dp 小于下限 $KEYBOARD_MIN_KEY_ROW_HEIGHT_DP",
            rowHeight >= KEYBOARD_MIN_KEY_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `fixed part leaves room for key rows`() {
        // 固定部分（不含可压缩的键行）在最 tall 的全键中文模式下也不应
        // 超过预算的六成，否则键行会被压到下限以下。
        val fixed = keyboardFixedHeightDp(keyRowCount = 6, zhMode = true)
        assertTrue(
            "固定部分 $fixed dp 超过预算六成 ${KEYBOARD_BUDGET_M3_DP * 0.6f}",
            fixed <= KEYBOARD_BUDGET_M3_DP * 0.6f,
        )
    }
}
