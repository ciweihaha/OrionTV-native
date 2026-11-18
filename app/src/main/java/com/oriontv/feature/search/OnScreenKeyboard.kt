package com.oriontv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/** 拼音候选行一次最多展示的字数（第 74 轮，RN PinyinKeyboard 同款 12 个，电视横屏取 10）。 */
private const val MAX_CANDIDATES_SHOWN = 10

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

    /** 这次按键是否算同一键的多击延续（只读判定，不推进状态；第 74 轮 9 键拼音用）。 */
    fun isContinuing(group: String, currentText: String, nowMs: Long): Boolean {
        if (!KEY_GROUPS.containsKey(group)) return false
        return group == lastGroup &&
            lastTapAtMs >= 0 && nowMs - lastTapAtMs < multiTapWindowMs &&
            currentText.isNotEmpty() && currentText.last() == expectedLastChar
    }

    fun press(group: String, currentText: String, nowMs: Long): String {
        val letters = KEY_GROUPS[group] ?: return currentText
        val continuing = isContinuing(group, currentText, nowMs)
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
 * 填入搜索框；拼音切分走 PinyinTable（423 条音节表，含少量非常见/方言音节）。
 * 第 74 轮：内置中文拼音输入（移植 RN v1.7.3–v1.7.4）——中/英切换（默认中文，
 * 全键/9 键两档顶栏都有），本地词典出候选（PinyinCandidates + PinyinInput，
 * 不用联网），9 键中文用 PinyinNineKeyComposer；iQIYI 联想仍在联想行做补充。
 */
@Composable
fun OnScreenKeyboard(
    query: String,
    onQueryChange: (String) -> Unit,
    /**
     * 第 74 轮：搜索键带完整关键词回调——中文缓冲有合法音节时先把首选字上屏，
     * 再用含该字的完整关键词搜（只调 onQueryChange 再读 uiState.query 会丢字，
     * 状态更新还没重组回来）。
     */
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nineKeyMode by remember { mutableStateOf(false) }
    // Jewel 第 9 轮 P2-6：符号板档（三档互斥：全键 / 9 键 / 符号，切档即清另一档）。
    var symbolMode by remember { mutableStateOf(false) }
    val nineKey = remember { NineKeyInput() }
    val firstKeyRequester = remember { FocusRequester() }
    // 第 74 轮：内置中文拼音输入（移植 RN v1.7.3–v1.7.4 的拼音键盘）——
    // 中/英切换（默认中文，与 RN 同款）；中文模式字母进拼音缓冲、不直接上屏，
    // 候选行点选上屏，空格选首选字；9 键中文用 PinyinNineKeyComposer
    //（多击待定字母落定后才进缓冲，见 PinyinInput.kt）。
    var zhMode by remember { mutableStateOf(true) }
    var pinyinBuffer by remember { mutableStateOf("") }
    val nineKeyComposer = remember { PinyinNineKeyComposer() }
    // 本键盘自己发出的 query（经 emit），外部改 query（历史填回、清空、屏上
    // 键盘外的输入）时清掉未落定的拼音缓冲与多击状态，不留脏缓冲。
    var lastEmittedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != lastEmittedQuery) {
            pinyinBuffer = ""
            nineKeyComposer.reset()
            nineKey.reset()
            lastEmittedQuery = query
        }
    }
    fun emit(newQuery: String) {
        lastEmittedQuery = newQuery
        onQueryChange(newQuery)
    }
    // 当前拼写中的拼音文本（全键档用 pinyinBuffer，9 键档用拼写器）。
    val composingText = if (nineKeyMode) nineKeyComposer.composingText else pinyinBuffer
    val candidates = if (zhMode) getCandidates(composingText) else emptyList()

    /** 候选行点选：第 index 个字上屏，清缓冲。 */
    fun commitCandidate(index: Int) {
        val ch = selectCandidate(composingText, index) ?: return
        if (nineKeyMode) nineKeyComposer.reset() else pinyinBuffer = ""
        nineKey.reset()
        emit(query + ch)
    }

    /** 全键档字母键：中文走拼音缓冲，英文直接上屏（小写，与旧行为一致）。 */
    fun onLetterKey(ch: Char) {
        val letter = ch.lowercaseChar()
        if (!zhMode) {
            emit(query + letter)
            return
        }
        val r = appendLetter(pinyinBuffer, letter)
        r.commit?.let { emit(query + it) }
        pinyinBuffer = r.buffer
    }

    /** 9 键档数字键：中文走拼音拼写器，英文走多击直出（旧行为）。 */
    fun onNineKeyDigit(digit: String) {
        if (!zhMode) {
            emit(nineKey.press(digit, query, System.currentTimeMillis()))
            return
        }
        nineKeyComposer.press(digit, System.currentTimeMillis())?.let { emit(query + it) }
    }

    /** 退格：中文模式先删拼音缓冲/待定字母，再删搜索框字符。 */
    fun onBackspace() {
        if (zhMode) {
            if (nineKeyMode) {
                if (nineKeyComposer.backspace()) return
            } else if (pinyinBuffer.isNotEmpty()) {
                pinyinBuffer = pinyinBuffer.dropLast(1)
                return
            }
        }
        nineKey.reset()
        if (query.isNotEmpty()) emit(query.dropLast(1))
    }

    /** 空格：中文模式有合法音节时选首选字上屏，否则加空格。 */
    fun onSpace() {
        if (zhMode) {
            if (nineKeyMode) {
                nineKeyComposer.settle()?.let { emit(query + it) }
                val first = commitFirstCandidate(nineKeyComposer.buffer)
                if (first != null) {
                    nineKeyComposer.reset()
                    emit(query + first)
                    return
                }
            } else {
                val first = commitFirstCandidate(pinyinBuffer)
                if (first != null) {
                    pinyinBuffer = ""
                    emit(query + first)
                    return
                }
            }
        }
        emit(query + " ")
    }

    /** 搜索键：中文缓冲有合法音节时先把首选字上屏，再用完整关键词搜。 */
    fun handleSearch() {
        var finalQuery = query
        if (zhMode) {
            if (nineKeyMode) {
                nineKeyComposer.settle()?.let { finalQuery += it }
                commitFirstCandidate(nineKeyComposer.buffer)?.let {
                    finalQuery += it
                    nineKeyComposer.reset()
                }
            } else {
                commitFirstCandidate(pinyinBuffer)?.let {
                    finalQuery += it
                    pinyinBuffer = ""
                }
            }
        }
        if (finalQuery != query) emit(finalQuery)
        onSearch(finalQuery)
    }

    /** 切拼音档/切中英文时清掉未落定的拼音缓冲与多击状态。 */
    fun clearPinyinState() {
        pinyinBuffer = ""
        nineKeyComposer.reset()
        nineKey.reset()
    }

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
            // 第 74 轮：中/英切换（全键、9 键、符号三档顶栏共用，两布局都要有）。
            KeyboardKey(
                label = if (zhMode) "中文" else "英文",
                onClick = {
                    zhMode = !zhMode
                    clearPinyinState()
                },
            )
            KeyboardKey(
                label = if (nineKeyMode) "切全键" else "切 9 键",
                onClick = {
                    nineKeyMode = !nineKeyMode
                    if (nineKeyMode) symbolMode = false
                    clearPinyinState()
                },
            )
        }
        // 第 74 轮：中文拼音候选行（固定 56dp 高，没候选时留空行/提示，键盘不
        // 上下跳；RN PinyinKeyboard 同款）。只在中文模式显示；英文不拼拼音。
        if (zhMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (composingText.isNotEmpty()) {
                        Text(
                            text = composingText,
                            style = OrionTextStyles.Body,
                            color = OrionColors.Brand,
                        )
                    }
                    if (candidates.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(candidates.take(MAX_CANDIDATES_SHOWN)) { index, ch ->
                                CategoryChip(
                                    text = ch,
                                    selected = false,
                                    onClick = { commitCandidate(index) },
                                )
                            }
                        }
                    } else if (composingText.isNotEmpty()) {
                        Text(
                            text = if (isSyllablePrefix(composingText)) {
                                "继续拼…"
                            } else {
                                "无此拼音候选，按删除修改（ü 用 v 代替，如 nv=女）"
                            },
                            style = OrionTextStyles.Small,
                            color = OrionColors.TextSecondary,
                        )
                    } else {
                        Text(
                            text = "中文模式：输入拼音，选字上屏；空格选首选字",
                            style = OrionTextStyles.Small,
                            color = OrionColors.TextSecondary,
                        )
                    }
                }
            }
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
                            onClick = { onNineKeyDigit(digit) },
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
                    onClick = {
                        nineKeyMode = false
                        clearPinyinState()
                    },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "0 空格",
                    onClick = { onSpace() },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = { onBackspace() },
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
                            onClick = { emit(query + ch) },
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
                    onClick = {
                        symbolMode = false
                        clearPinyinState()
                    },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "空格",
                    onClick = { onSpace() },
                    modifier = Modifier.weight(2f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = { onBackspace() },
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
                            onClick = { onLetterKey(ch) },
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
                        clearPinyinState()
                    },
                    modifier = Modifier.weight(1f),
                )
                // Jewel 第 9 轮 P2-6：符号板入口——切符号档（同时清 9 键档）。
                KeyboardKey(
                    label = "[123]",
                    onClick = {
                        symbolMode = true
                        nineKeyMode = false
                        clearPinyinState()
                    },
                    modifier = Modifier.weight(1f),
                )
                KeyboardKey(
                    label = "空格",
                    onClick = { onSpace() },
                    modifier = Modifier.weight(2f),
                )
                KeyboardKey(
                    label = "退格",
                    onClick = { onBackspace() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardKey(
                label = "搜索",
                onClick = { handleSearch() },
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
            text = "提示：中文模式敲拼音出候选（本地词典，不用联网），点选上屏，空格选首选字；9 键同键连续字母须停顿 1 秒再按；点“英文”直接输字母；搜含符号的词可切 [123] 符号板；搜中文词也可用“手机扫码输入”。",
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
    // v2.0.6 焦点修复：同 CategoryChip，聚焦态改由 InteractionSource 驱动。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // 触摸修复：clickable 本身可聚焦，并同时处理触摸点按与遥控器
            // 中心/回车键（松键触发，与旧 onKeyUp 同款）；旧的 onKeyUp 分支已删，
            // 不留两处按键处理（否则遥控器按一次会触发两次 onClick）。
            .clickable(
                interactionSource = interactionSource,
                indication = null, // v2.0.6：电视上焦点高亮即提示，不用水波纹
            ) { onClick() }
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
