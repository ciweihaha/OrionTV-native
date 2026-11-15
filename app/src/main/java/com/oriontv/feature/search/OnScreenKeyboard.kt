package com.oriontv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 9 键多击输入纯逻辑（TV 应用同款思路，自写实现）：
 * 同一键 1 秒内连按循环切字母（2:abc、3:def、4:ghi、5:jkl、6:mno、7:pqrs、8:tuv、9:wxyz，
 * 1:标点）；超时、换键、或当前文本末尾不是上次敲的字母时，追加首字母。
 * 不依赖 Android，可单测；时间由调用方注入。
 */
class NineKeyInput(
    private val multiTapWindowMs: Long = MULTI_TAP_WINDOW_MS,
) {
    private var lastGroup: String? = null
    private var lastTapAtMs: Long = -1L
    private var lastIndex: Int = 0
    private var expectedLastChar: Char? = null

    fun press(group: String, currentText: String, nowMs: Long): String {
        val letters = KEY_GROUPS[group] ?: return currentText
        val continuing = group == lastGroup &&
            lastTapAtMs >= 0 && nowMs - lastTapAtMs < multiTapWindowMs &&
            currentText.isNotEmpty() && currentText.last() == expectedLastChar
        return if (continuing) {
            lastIndex = (lastIndex + 1) % letters.length
            val next = letters[lastIndex]
            expectedLastChar = next
            lastTapAtMs = nowMs
            currentText.dropLast(1) + next
        } else {
            lastGroup = group
            lastIndex = 0
            val first = letters[0]
            expectedLastChar = first
            lastTapAtMs = nowMs
            currentText + first
        }
    }

    /** 退格/外部改写后丢弃多击状态，下次按键从首字母开始。 */
    fun reset() {
        lastGroup = null
        lastTapAtMs = -1L
        lastIndex = 0
        expectedLastChar = null
    }

    companion object {
        /** 同一键 1 秒内连按算多击（与 TV 应用一致）。 */
        const val MULTI_TAP_WINDOW_MS = 1000L

        /** 9 键字母分组（1 为标点组）。 */
        val KEY_GROUPS: Map<String, String> = mapOf(
            "1" to ".,?!",
            "2" to "abc",
            "3" to "def",
            "4" to "ghi",
            "5" to "jkl",
            "6" to "mno",
            "7" to "pqrs",
            "8" to "tuv",
            "9" to "wxyz",
        )
    }
}

/**
 * 电视端屏上键盘（P1-8，最小可用实现）：
 * 全键 QWERTY + 9 键多击 + 符号板三档切换；能逐字输入、退格、确认搜索、收起。
 * 输入直接进搜索框（走 onQueryChange，300ms 防抖后自动搜）。
 *
 * Jewel 第 9 轮 P2-6：补 `[123]` 符号板（搜含 `_`、`-`、`@` 等符号的词不用再
 * 绕手机扫码）与拼音候选——敲出的拼音字母由 SearchViewModel 发给 iQIYI 联想
 * 接口出中文候选（见 IqiyiSuggest），候选 chips 在键盘下方联想行展示、点选
 * 填入搜索框；拼音切分走 PinyinTable（TV v5.9.6 前例的 423 标准音节表）。
 * 仍缺：没有本地中文输入法——中文候选只来自历史联想 + iQIYI 联想，无网时
 * 只剩历史联想。
 */
@Composable
fun OnScreenKeyboard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nineKeyMode by remember { mutableStateOf(false) }
    // Jewel 第 9 轮 P2-6：符号板档（三档互斥：全键 / 9 键 / 符号，切档即清另一档）。
    var symbolMode by remember { mutableStateOf(false) }
    val nineKey = remember { NineKeyInput() }
    val firstKeyRequester = remember { FocusRequester() }

    // 打开即把焦点落到首键，遥控可直接按方向键选键。
    // 第 6 轮 P2-8：按 nineKeyMode 键——QWERTY↔9 键切换后当前聚焦的切换
    // 按钮随模式重组离开组合，之前没有任何重新请求焦点的调用，焦点丢失；
    // 切换后把焦点还给新模式的首键（首次打开也走同一条路）。
    // Jewel 第 9 轮 P2-6：符号板切档同款——三档任一切换都回新模式首键。
    LaunchedEffect(nineKeyMode, symbolMode) { firstKeyRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrionColors.Surface, RoundedCornerShape(OrionDimens.PanelCornerRadius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    nineKeyMode -> "9 键（连按同键切换字母）"
                    symbolMode -> "符号"
                    else -> "全键"
                },
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            KeyboardKey(
                label = if (nineKeyMode) "切全键" else "切 9 键",
                onClick = {
                    nineKeyMode = !nineKeyMode
                    if (nineKeyMode) symbolMode = false
                },
            )
        }
        if (nineKeyMode) {
            val rows = listOf(
                listOf("1" to ".,?!", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            )
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { index, (digit, letters) ->
                        KeyboardKey(
                            label = "$digit\n$letters",
                            onClick = {
                                onQueryChange(
                                    nineKey.press(digit, query, System.currentTimeMillis()),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (rows.indexOf(row) == 0 && index == 0) {
                                        Modifier.preferredFocus(firstKeyRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardKey(
                    label = "全键",
                    onClick = { nineKeyMode = false },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "0 空格",
                    onClick = { onQueryChange(query + " ") },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = {
                        nineKey.reset()
                        onQueryChange(query.dropLast(1))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (symbolMode) {
            // Jewel 第 9 轮 P2-6：[123] 符号板——搜含 `_`、`-`、`@` 等符号的词
            // 不用再绕手机扫码；行宽与 QWERTY 同款 10 键折算对齐。点符号直接追加
            // 进搜索框（不做大小写变换），退格清多击状态与字母档共用。
            val rows = listOf("_-@#&*%+", ".,!?;:'\"", "()[]{}<>=")
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // P2-10（第 5 轮）：各行键宽对齐——短行两侧补空白占位（权重
                    // 按 10 键行折算），不再让短行按键更宽、跨行空间导航跳格。
                    val sideWeight = (10 - row.length) / 2f
                    if (sideWeight > 0f) Spacer(modifier = Modifier.weight(sideWeight))
                    row.forEachIndexed { index, ch ->
                        KeyboardKey(
                            label = ch.toString(),
                            onClick = { onQueryChange(query + ch) },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (rowIndex == 0 && index == 0) {
                                        Modifier.preferredFocus(firstKeyRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (sideWeight > 0f) Spacer(modifier = Modifier.weight(sideWeight))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardKey(
                    label = "ABC",
                    onClick = { symbolMode = false },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "空格",
                    onClick = { onQueryChange(query + " ") },
                    modifier = Modifier.weight(2f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = {
                        nineKey.reset()
                        onQueryChange(query.dropLast(1))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // P2-10（第 5 轮）：各行键宽对齐——短行两侧补空白占位（权重
                    // 按 10 键行折算），不再让短行按键更宽、跨行空间导航跳格。
                    val sideWeight = (10 - row.length) / 2f
                    if (sideWeight > 0f) Spacer(modifier = Modifier.weight(sideWeight))
                    row.forEachIndexed { index, ch ->
                        KeyboardKey(
                            label = ch.toString(),
                            onClick = { onQueryChange(query + ch.lowercaseChar()) },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (rowIndex == 0 && index == 0) {
                                        Modifier.preferredFocus(firstKeyRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (sideWeight > 0f) Spacer(modifier = Modifier.weight(sideWeight))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardKey(
                    label = "9 键",
                    onClick = {
                        nineKeyMode = true
                        symbolMode = false
                    },
                    modifier = Modifier.weight(1f),
                )
                // Jewel 第 9 轮 P2-6：符号板入口——切符号档（同时清 9 键档）。
                KeyboardKey(
                    label = "[123]",
                    onClick = {
                        symbolMode = true
                        nineKeyMode = false
                    },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "空格",
                    onClick = { onQueryChange(query + " ") },
                    modifier = Modifier.weight(2f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = {
                        nineKey.reset()
                        onQueryChange(query.dropLast(1))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardKey(
                label = "搜索",
                onClick = onSearch,
                modifier = Modifier.weight(1f),
            )
            KeyboardKey(
                label = "收起键盘",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "提示：敲拼音字母后，下方出现中文候选（iQIYI 联想），点选填入搜索框；搜含符号的词可切 [123] 符号板；搜中文词也可用“手机扫码输入”。",
            style = OrionTextStyles.Small,
            color = OrionColors.TextSecondary,
        )
    }
}

/** 键盘单键：可聚焦 + 焦点三件套，OK 键触发。 */
@Composable
private fun KeyboardKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused, RoundedCornerShape(OrionDimens.CardCornerRadius))
            .background(OrionColors.Background, RoundedCornerShape(OrionDimens.CardCornerRadius))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OrionTextStyles.Body,
            color = if (focused) OrionColors.Text else OrionColors.TextSecondary,
        )
    }
}
