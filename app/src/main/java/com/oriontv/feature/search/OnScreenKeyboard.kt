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
import androidx.compose.foundation.layout.fillMaxHeight
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
 * 屏上键盘纵向排布度量（第 76 轮 v2.0.16）：
 * v2.0.15 的键盘是固定行高——顶行 + 候选行 + 6 行字母 + 底行 + 切换行 +
 * 搜索行 + 提示文案合计 545~632dp；但华为 MediaPad M3 横屏只有 533dp 高
 * （1600px @480dpi），扣掉状态栏、页边距、搜索框后键盘只剩约 387dp，
 * 底部的切换行、“搜索/收起键盘”行和提示文案全被切掉（用户照片：最后
 * 可见行是 9 键/空格行）。
 * 修法：字母/数字/符号行改 weight 均分剩余高度（再矮的屏也完整显示，
 * 键只变矮不会消失），固定行收紧、提示文案删掉（候选行空态已有用法
 * 提示）、“搜索”并进各档底行、切换行与收起键盘并成一行。
 * 下面这组常量是布局与单测的唯一事实源，改布局先改这里；
 * 单测按 387dp 预算验算每种模式的键行高，再加行、加高固定行会先失败。
 */
internal const val KEYBOARD_COLUMN_PADDING_DP = 12f
internal const val KEYBOARD_ROW_SPACING_DP = 6f
internal const val KEYBOARD_KEY_VERTICAL_PADDING_DP = 6f
internal const val KEYBOARD_CANDIDATE_ROW_HEIGHT_DP = 48f
/** 华为 MediaPad M3 横屏下键盘分到的高度（实测预算，见运行记录）。 */
internal const val KEYBOARD_BUDGET_M3_DP = 387f
/** 单行键行高下限（正文行高 + 最小边距，再矮字会被切）。 */
internal const val KEYBOARD_MIN_KEY_ROW_HEIGHT_DP = 26f
/** 9 键数字键是两行标签（数字 + 字母），行高下限 = 两行正文 + 上下边距。 */
internal const val KEYBOARD_MIN_TWO_LINE_ROW_HEIGHT_DP = 48f

/** 单键高度 = 上下边距 + 一行正文（与 KeyboardKey 的 padding + Body 一致）。 */
internal fun keyboardKeyHeightDp(): Float =
    KEYBOARD_KEY_VERTICAL_PADDING_DP * 2 + OrionTextStyles.Body.lineHeight.value

/**
 * 键盘固定部分高度（不含可压缩的键行），单位 dp：
 * 顶行 + 底行 + 切换行（3 个单键行）+ 候选行（中文才有）+ 纵向 padding + 行间距。
 */
internal fun keyboardFixedHeightDp(keyRowCount: Int, zhMode: Boolean): Float {
    val candidate = if (zhMode) KEYBOARD_CANDIDATE_ROW_HEIGHT_DP else 0f
    val childCount = 3 + keyRowCount + if (zhMode) 1 else 0
    return 3 * keyboardKeyHeightDp() + candidate +
        2 * KEYBOARD_COLUMN_PADDING_DP +
        (childCount - 1) * KEYBOARD_ROW_SPACING_DP
}

/** 可压缩键行（字母/数字/符号行）在给定可用高度下每行分到的高度，单位 dp。 */
internal fun keyboardKeyRowHeightDp(
    keyRowCount: Int,
    zhMode: Boolean,
    availableHeightDp: Float,
): Float =
    (availableHeightDp - keyboardFixedHeightDp(keyRowCount, zhMode)) / keyRowCount

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
    // 第 76 轮 v2.0.14：拼写器是 remember 里的普通对象（非 State）——中文 9 键
    // 按数字只改拼写器内部状态，大多不触发 emit（只有旧音节落定上屏时才 emit），
    // Compose 不知道要重组，候选行/拼写文本不更新，看着就是“点了没反应”
    // （英文 9 键走 emit、全键中文走 pinyinBuffer State，所以都正常）。
    // 每次拼写器变化后 bumpComposingTick() +1；下行读它即订阅，强制重组重读
    // composingText。值本身不用，只起“重组信号”作用。
    var composingTick by remember { mutableStateOf(0) }
    /** 拼写器变了就 +1，强制重组重读 composingText（见 composingTick 注释）。 */
    fun bumpComposingTick() { composingTick++ }
    // 本键盘自己发出的 query（经 emit），外部改 query（历史填回、清空、屏上
    // 键盘外的输入）时清掉未落定的拼音缓冲与多击状态，不留脏缓冲。
    var lastEmittedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != lastEmittedQuery) {
            pinyinBuffer = ""
            nineKeyComposer.reset()
            bumpComposingTick()
            nineKey.reset()
            lastEmittedQuery = query
        }
    }
    fun emit(newQuery: String) {
        lastEmittedQuery = newQuery
        onQueryChange(newQuery)
    }
    // 当前拼写中的拼音文本（全键档用 pinyinBuffer，9 键档用拼写器）。
    // 读 composingTick 即订阅（见上）：拼写器变化后重组，这里重读 composingText。
    @Suppress("UNUSED_EXPRESSION")
    composingTick
    val composingText = if (nineKeyMode) nineKeyComposer.composingText else pinyinBuffer
    val candidates = if (zhMode) getCandidates(composingText) else emptyList()

    /** 候选行点选：第 index 个字上屏，清缓冲。 */
    fun commitCandidate(index: Int) {
        val ch = selectCandidate(composingText, index) ?: return
        if (nineKeyMode) {
            nineKeyComposer.reset()
            bumpComposingTick()
        } else {
            pinyinBuffer = ""
        }
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
        bumpComposingTick()
    }

    /** 退格：中文模式先删拼音缓冲/待定字母，再删搜索框字符。 */
    fun onBackspace() {
        if (zhMode) {
            if (nineKeyMode) {
                if (nineKeyComposer.backspace()) {
                    bumpComposingTick()
                    return
                }
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
                bumpComposingTick()
                val first = commitFirstCandidate(nineKeyComposer.buffer)
                if (first != null) {
                    nineKeyComposer.reset()
                    bumpComposingTick()
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
                bumpComposingTick()
                commitFirstCandidate(nineKeyComposer.buffer)?.let {
                    finalQuery += it
                    nineKeyComposer.reset()
                    bumpComposingTick()
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
        bumpComposingTick()
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
            .padding(KEYBOARD_COLUMN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(KEYBOARD_ROW_SPACING_DP.dp),
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
            // 第 76 轮 v2.0.14：顶行照影视 app（FongMi）搜索页——切换（中/英）、
            // 123（数字/符号板）、删除（清空搜索框）、退格（删一字）。
            // 9 键切换改到底部动作行（[9 键]/[全键] 按钮），顶行不再放。
            KeyboardKey(
                label = if (zhMode) "中文" else "英文",
                onClick = {
                    zhMode = !zhMode
                    clearPinyinState()
                },
            )
            KeyboardKey(
                label = "123",
                onClick = {
                    symbolMode = true
                    nineKeyMode = false
                    clearPinyinState()
                },
            )
            KeyboardKey(
                label = "删除",
                onClick = {
                    clearPinyinState()
                    emit("")
                },
            )
            KeyboardKey(
                label = "退格",
                onClick = { onBackspace() },
            )
        }
        // 第 74 轮：中文拼音候选行（固定 48dp 高，没候选时留空行/提示，键盘不
        // 上下跳；RN PinyinKeyboard 同款）。只在中文模式显示；英文不拼拼音。
        // v2.0.16：56dp → 48dp（候选 chip 本身 34dp 高，留够边距即可），
        // 给字母行多腾 8dp（见文件头纵向排布度量）。
        if (zhMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KEYBOARD_CANDIDATE_ROW_HEIGHT_DP.dp),
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
            // v2.0.16：数字行改 weight 均分剩余高度（见文件头纵向排布度量），
            // 再矮的屏也完整显示，键只变矮不会消失；键拉满行高。
            val rows = listOf(
                listOf("1" to ".,?!", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            )
            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    row.forEachIndexed { index, (digit, letters) ->
                        KeyboardKey(
                            label = "$digit\n$letters",
                            onClick = { onNineKeyDigit(digit) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
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
                KeyboardKey(
                    label = "搜索",
                    onClick = { handleSearch() },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (symbolMode) {
            // Jewel 第 9 轮 P2-6：[123] 符号板——搜含 `_`、`-`、`@` 等符号的词
            // 不用再绕手机扫码；行宽与 QWERTY 同款 10 键折算对齐。点符号直接追加
            // 进搜索框（不做大小写变换），退格清多击状态与字母档共用。
            // 第 76 轮 v2.0.14：顶行 123 键进本板，首行补数字行（全键档的数字行
            // 随 QWERTY 一起撤了，数字放这里）。
            val rows = listOf("0123456789", "_-@#&*%+", ".,!?;:'\"", "()[]{}<>=")
            rows.forEachIndexed { rowIndex, row ->
                // v2.0.16：符号行改 weight 均分剩余高度（见文件头纵向排布度量）。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
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
                                .fillMaxHeight()
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
                KeyboardKey(
                    label = "搜索",
                    onClick = { handleSearch() },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // 第 76 轮 v2.0.14：全键档改成影视 app（FongMi）搜索页的 A–Z 字母
            // 键盘（5 列：A–E、F–J、K–O、P–T、U–Y、Z）——QWERTY 在电视上遥控
            // 逐格找字母太慢，字母顺序排更顺手；数字行移到 123 板。
            // 末行只有 Z，其余 4 格补空白占位（键宽对齐，跨行空间导航不跳格）。
            // v2.0.16：字母行改 weight 均分剩余高度（见文件头纵向排布度量），
            // 再矮的屏也完整显示，键只变矮不会消失；键拉满行高。
            val rows = listOf("ABCDE", "FGHIJ", "KLMNO", "PQRST", "UVWXY", "Z")
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    row.forEachIndexed { index, ch ->
                        KeyboardKey(
                            label = ch.toString(),
                            onClick = { onLetterKey(ch) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (rowIndex == 0 && index == 0) {
                                        Modifier.preferredFocus(firstKeyRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    repeat(5 - row.length) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
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
                // 123、退格已在顶行（第 76 轮），本行只留 9 键 + 宽空格 + 搜索
                // （v2.0.16：搜索从底下的独立搜索行并到这里，省出一行高度）。
                KeyboardKey(
                    label = "空格",
                    onClick = { onSpace() },
                    modifier = Modifier.weight(2f),
                )
                KeyboardKey(
                    label = "搜索",
                    onClick = { handleSearch() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 第 76 轮 v2.0.16：底行三合一（输入法切换 + 收起键盘）——v2.0.15
        // 这里是两行（切换行 + 搜索行），再加底下提示文案，键盘总高
        // 545~632dp，超出平板横屏 387dp 预算，底部全被切掉；现把“搜索”
        // 并进各档底行（上），本行只留切换 + 收起键盘，提示文案删掉
        // （候选行空态已有用法提示：“中文模式：输入拼音，选字上屏…”）。
        // “全键盘”（绿点，当前：内置屏上键盘）/“系统输入法”（蓝点：
        // 收起屏上键盘，回搜索框走系统输入法；触屏平板上会弹出系统键盘）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardKey(
                label = "● 全键盘",
                onClick = { /* 已是全键盘，当前档，不用切 */ },
                modifier = Modifier.weight(1f),
            )
            KeyboardKey(
                label = "● 系统输入法",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            KeyboardKey(
                label = "收起键盘",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
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
            // v2.0.16：上下边距 10dp → 6dp（见文件头纵向排布度量），单键
            // 38.2dp → 30.2dp；字母行是 weight 行，键拉满行高，边距只影响
            // 顶行/底行等固定行。
            .padding(vertical = KEYBOARD_KEY_VERTICAL_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OrionTextStyles.Body,
            color = if (focused) OrionColors.Text else OrionColors.TextSecondary,
        )
    }
}
