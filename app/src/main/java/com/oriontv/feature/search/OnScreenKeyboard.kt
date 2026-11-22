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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/** 拼音候选栏一次最多展示的词数（第 80 轮 10 -> 12：候选栏不再与拼写文本同行抢宽度，见下）。 */
private const val MAX_CANDIDATES_SHOWN = 12

/** 候选 chip 胶囊（Buttons.kt 的 Capsule 是私有的，这里同款）。 */
private val Capsule = RoundedCornerShape(OrionDimens.CapsuleRadius)

/**
 * 屏上键盘纵向排布度量（第 80 轮 v2.0.21 百度输入法电视版样式）：
 * 旧版（v2.0.16）是顶行 + 候选行 + 6 行 A–Z 字母 + 底行 + 切换行，固定部分
 * 占了大半高度；新版是灰色面板 + 页签行 + 候选栏 + 3 行 QWERTY（或 4 行 9 键），
 * 左右列（分词/?12/中·英、删除/完成）与键行同高、不再单占行高。
 * 华为 MediaPad M3 横屏键盘预算仍是 387dp（见 KEYBOARD_BUDGET_M3_DP）；
 * 键行继续 weight 均分剩余高度，再矮的屏也完整显示。
 * 固定部分 = 页签行（1 个单键行）+ 候选栏（有才算）+ 符号板底行（符号档才有）
 * + 纵向 padding + 行间距；下面这组常量是布局与单测的唯一事实源，改布局先改这里。
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
 * 页签行 + 候选栏（[showCandidateRow] 时）+ 符号板底行（[extraFixedRows]，符号档为 1）
 * + 纵向 padding + 行间距（外层子项：页签行、候选栏、键区；符号底行在键区内再加一隙）。
 */
internal fun keyboardFixedHeightDp(
    keyRowCount: Int,
    showCandidateRow: Boolean,
    extraFixedRows: Int = 0,
): Float {
    val candidate = if (showCandidateRow) KEYBOARD_CANDIDATE_ROW_HEIGHT_DP else 0f
    val outerChildren = 2 + if (showCandidateRow) 1 else 0 // 页签行 + 键区（+ 候选栏）
    val gaps = (outerChildren - 1) + extraFixedRows
    return (1 + extraFixedRows) * keyboardKeyHeightDp() + candidate +
        2 * KEYBOARD_COLUMN_PADDING_DP +
        gaps * KEYBOARD_ROW_SPACING_DP
}

/** 可压缩键行（QWERTY / 数字 / 符号行）在给定可用高度下每行分到的高度，单位 dp。 */
internal fun keyboardKeyRowHeightDp(
    keyRowCount: Int,
    showCandidateRow: Boolean,
    availableHeightDp: Float,
    extraFixedRows: Int = 0,
): Float =
    (availableHeightDp - keyboardFixedHeightDp(keyRowCount, showCandidateRow, extraFixedRows)) / keyRowCount

/** 贴底通栏时单键行高（电视遥控操作，64dp 够大、又不至于把结果区挤没）。 */
internal const val KEYBOARD_DOCKED_KEY_ROW_HEIGHT_DP = 64f

/**
 * 贴底通栏键盘总高（v2.0.23）：固定部分 + 键行 × 64dp；九键 4 行最高
 * （约 376dp），与 M3 平板 387dp 预算同量级；键行 weight 均分，模式切换
 * 时总高跟着变（全键中文 312dp / 九键中文 376dp / 全键英文无候选 258dp），
 * 上半区 weight(1f) 自动让出空间。符号档 keyRowCount 只计 3 行权重行，
 * 固定底行走 extraFixedRows。
 */
internal fun keyboardDockedHeightDp(
    keyRowCount: Int,
    showCandidateRow: Boolean,
    extraFixedRows: Int = 0,
): Float =
    keyboardFixedHeightDp(keyRowCount, showCandidateRow, extraFixedRows) +
        keyRowCount * KEYBOARD_DOCKED_KEY_ROW_HEIGHT_DP

/**
 * 9 键多击输入纯逻辑（TV 应用同款思路，自写实现）：
 * 同一键 1 秒内连按循环切字母（2:abc、3:def、4:ghi、5:jkl、6:mno、7:pqrs、8:tuv、9:wxyz，
 * 1:标点）；超时、换键、或当前文本末尾不是上次敲的字母时，追加首字母。
 * 不依赖 Android，可单测；时间由调用方注入。
 *
 * 第 80 轮：屏上键盘的 9 键改走 T9 数字码（[segmentDigitCodeAlternatives] /
 * [t9EnglishCandidates]，见 PinyinInput.kt），本类不再走 UI，只留作旧单测
 * （NineKeyInputTest）与数字映射的事实源——[KEY_GROUPS] 就是标准 T9 映射，
 * 核对过，无需改。
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
 * 电视端屏上键盘——百度输入法电视版样式（v2.0.21 第 80 轮，用户发电视实拍视频确认过）：
 * 底部灰色面板；顶部页签只留“拼音全键 / 拼音九键”（不要笔画键盘）；
 * 候选栏在键盘上方、首选蓝底，遥控选词、选了才上屏去搜；
 * 全键 QWERTY 三行，左列 分词 / ?12 / 中·英，右列 删除 + 完成（完成占两行高），
 * 空格在第三行中间；九键是标准 3×4 数字键（2=ABC … 9=WXYZ，0=空格），
 * 按数字出候选词（中文 887484826 -> 图书馆，英文 2288 -> CCTV）；
 * 英文模式（全键）输什么字母直接显示什么，没有候选栏。
 * 拼音引擎不动：仍是 PinyinInput.kt（appendLetter 永不自动提交、逐音节选字、
 * displayCandidates）+ 12747 条词典；只换 UI 和交互。
 *
 * 输入直接进搜索框（走 onQueryChange，300ms 防抖后自动搜）。
 * 第 80 轮改动点：全键 A–Z 字母顺序排 -> QWERTY 三行；九键多击 -> T9 数字码；
 * 顶行（切换/123/删除/退格）与底行（全键盘/系统输入法/收起键盘）撤掉，改由
 * 左右列承担：删除 = 退格语义（先删拼写字母，再删搜索框字符；整串清空键取消，
 * 退格多按几次即可），完成 = 提交拼写 + 搜索 + 收起键盘。
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
    // 第 80 轮：符号板档（?12 键进出；全键 / 9 键是页签，符号是叠加档）。
    var symbolMode by remember { mutableStateOf(false) }
    val firstKeyRequester = remember { FocusRequester() }
    // 第 74 轮：内置中文拼音输入——中/英切换（默认中文）；中文模式字母进拼音缓冲、
    // 不直接上屏，候选栏点选上屏，空格选首选词；第 80 轮九键中文改 T9 数字码。
    var zhMode by remember { mutableStateOf(true) }
    var pinyinBuffer by remember { mutableStateOf("") }
    // 第 79 轮：多音节一起选——pickedChars 是已为前面音节选好的字（按音节顺序），
    // 候选栏先给当前待选音节出字，选完最后一个音节整词上屏；改拼音字母时按
    // retainPicked 保留前面没变的音节的已选字。
    var pickedChars by remember { mutableStateOf<List<String>>(emptyList()) }
    // 第 80 轮：九键 T9 数字码（中文切音节 / 英文查词共用）；多击拼写器不再走 UI。
    var nineKeyDigits by remember { mutableStateOf("") }
    // 第 80 轮：分词轮换下标（0 = 默认切分）；输入/退格/切档时回 0。
    var segIndex by remember { mutableStateOf(0) }
    // 本键盘自己发出的 query（经 emit），外部改 query（历史填回、清空、屏上
    // 键盘外的输入）时清掉未落定的拼音缓冲与数字码，不留脏缓冲。
    var lastEmittedQuery by remember { mutableStateOf(query) }

    // 全键中文的字母切分（分词可轮换）与九键中文的数字码切分（分词可轮换）。
    val letterAlternatives =
        if (zhMode && !nineKeyMode && !symbolMode) segmentPinyinAlternatives(pinyinBuffer) else emptyList()
    val digitAlternatives =
        if (zhMode && nineKeyMode && !symbolMode) segmentDigitCodeAlternatives(nineKeyDigits) else emptyList()
    // 当前切分拼成的拼音串（分词轮换即换这里）；切不出时回落到原始缓冲/空串。
    val effectivePinyin = when {
        zhMode && nineKeyMode -> digitAlternatives.getOrNull(segIndex)?.joinToString("") ?: ""
        zhMode -> letterAlternatives.getOrNull(segIndex)?.joinToString("") ?: pinyinBuffer
        else -> ""
    }
    // 候选栏内容：中文走 displayCandidates（词典 + 整词预览），九键英文走 T9 词表；
    // 全键英文与符号档没有候选栏。
    val candidates = when {
        symbolMode -> emptyList()
        zhMode -> displayCandidates(effectivePinyin, pickedChars)
        nineKeyMode -> t9EnglishCandidates(nineKeyDigits)
        else -> emptyList()
    }
    // 页签行右侧的拼写中文本：中文是拼音字母串，九键英文是数字码（切不出音节时也显示数字码）。
    val composingDisplayText = when {
        symbolMode -> ""
        zhMode && nineKeyMode -> effectivePinyin.ifEmpty { nineKeyDigits }
        zhMode -> pinyinBuffer
        nineKeyMode -> nineKeyDigits
        else -> ""
    }
    // 候选栏只在中文模式、或九键英文（T9 词）时出现；全键英文没有候选栏。
    val showCandidateBar = !symbolMode && (zhMode || nineKeyMode)

    fun emit(newQuery: String) {
        lastEmittedQuery = newQuery
        onQueryChange(newQuery)
    }

    /** 切拼音档/切中英文/切符号档时清掉未落定的拼音缓冲、数字码与已选字。 */
    fun clearPinyinState() {
        pinyinBuffer = ""
        pickedChars = emptyList()
        nineKeyDigits = ""
        segIndex = 0
    }

    /** 分词：有多种切分时轮换到下一种；切分变了，已选字按旧切分选的、直接丢掉重选。 */
    fun onSegmentKey() {
        val alts = if (nineKeyMode) digitAlternatives else letterAlternatives
        if (alts.size <= 1) return
        segIndex = (segIndex + 1) % alts.size
        pickedChars = emptyList()
    }

    /** 候选栏点选：中文走 selectCandidateStep（整词位直接上屏，字位逐音节记下）；九键英文点词上屏。 */
    fun commitCandidate(index: Int) {
        if (!zhMode && nineKeyMode) {
            val word = t9EnglishCandidates(nineKeyDigits).getOrNull(index) ?: return
            nineKeyDigits = ""
            segIndex = 0
            emit(query + word)
            return
        }
        val (text, nextPicked) = selectCandidateStep(effectivePinyin, pickedChars, index)
        if (text != null) {
            clearPinyinState()
            emit(query + text)
        } else {
            pickedChars = nextPicked
        }
    }

    /** 全键档字母键：中文走拼音缓冲（只攒字母、不自动上屏），英文直接上屏（小写，与旧行为一致）。 */
    fun onLetterKey(ch: Char) {
        val letter = ch.lowercaseChar()
        if (!zhMode) {
            emit(query + letter)
            return
        }
        val old = effectivePinyin
        val wasSegmented = segIndex != 0
        val next = appendLetter(pinyinBuffer, letter).buffer
        segIndex = 0
        val newEffective = segmentPinyinAlternatives(next).firstOrNull()?.joinToString("") ?: next
        pickedChars = if (wasSegmented) emptyList() else retainPicked(old, newEffective, pickedChars)
        pinyinBuffer = next
    }

    /** 数字 1（标点）：先把拼写中的词提交（同空格），再追加句号；无拼写时直接加句号（中文用 。）。 */
    fun onPunctuationKey() {
        var base = query
        if (zhMode) {
            commitPickedPhrase(effectivePinyin, pickedChars)?.let {
                base += it
                clearPinyinState()
            }
        } else if (nineKeyMode) {
            t9EnglishCandidates(nineKeyDigits).firstOrNull()?.let {
                base += it
                nineKeyDigits = ""
                segIndex = 0
            }
        }
        emit(base + if (zhMode) "。" else ".")
    }

    /** 空格：中文模式有完整音节时按已选字 + 剩余音节首选字拼整词上屏；九键英文提交首选 T9 词；否则加空格。 */
    fun onSpace() {
        if (zhMode) {
            val first = commitPickedPhrase(effectivePinyin, pickedChars)
            if (first != null) {
                clearPinyinState()
                emit(query + first)
                return
            }
        } else if (nineKeyMode) {
            val word = t9EnglishCandidates(nineKeyDigits).firstOrNull()
            if (word != null) {
                nineKeyDigits = ""
                segIndex = 0
                emit(query + word)
                return
            }
        }
        emit(query + " ")
    }

    /** 9 键档数字键：中文攒数字码（T9 切音节），英文攒数字码（T9 查词）；1 是标点、0 是空格。 */
    fun onNineKeyDigit(digit: String) {
        if (digit == "1") {
            onPunctuationKey()
            return
        }
        if (digit == "0") {
            onSpace()
            return
        }
        if (nineKeyDigits.length >= 24) return
        if (zhMode) {
            val old = effectivePinyin
            val wasSegmented = segIndex != 0
            nineKeyDigits += digit
            segIndex = 0
            val newEffective = segmentDigitCodeAlternatives(nineKeyDigits).firstOrNull()?.joinToString("") ?: ""
            pickedChars = if (wasSegmented) emptyList() else retainPicked(old, newEffective, pickedChars)
        } else {
            nineKeyDigits += digit
            segIndex = 0
        }
    }

    /** 退格：中文模式先删拼音缓冲/数字码，再删搜索框字符；英文九键先删数字码。 */
    fun onBackspace() {
        if (zhMode) {
            if (nineKeyMode) {
                if (nineKeyDigits.isNotEmpty()) {
                    val old = effectivePinyin
                    val wasSegmented = segIndex != 0
                    nineKeyDigits = nineKeyDigits.dropLast(1)
                    segIndex = 0
                    val newEffective = segmentDigitCodeAlternatives(nineKeyDigits).firstOrNull()?.joinToString("") ?: ""
                    pickedChars = if (wasSegmented) emptyList() else retainPicked(old, newEffective, pickedChars)
                    return
                }
            } else if (pinyinBuffer.isNotEmpty()) {
                val old = effectivePinyin
                val wasSegmented = segIndex != 0
                pinyinBuffer = pinyinBuffer.dropLast(1)
                segIndex = 0
                val newEffective = segmentPinyinAlternatives(pinyinBuffer).firstOrNull()?.joinToString("") ?: pinyinBuffer
                pickedChars = if (wasSegmented) emptyList() else retainPicked(old, newEffective, pickedChars)
                return
            }
        } else if (nineKeyMode && nineKeyDigits.isNotEmpty()) {
            nineKeyDigits = nineKeyDigits.dropLast(1)
            return
        }
        if (query.isNotEmpty()) emit(query.dropLast(1))
    }

    /** 搜索键：中文缓冲有完整音节时先把已选字 + 剩余音节首选字拼成的整词上屏，再用完整关键词搜。 */
    fun handleSearch() {
        var finalQuery = query
        if (zhMode) {
            commitPickedPhrase(effectivePinyin, pickedChars)?.let {
                finalQuery += it
                clearPinyinState()
            }
        } else if (nineKeyMode) {
            t9EnglishCandidates(nineKeyDigits).firstOrNull()?.let {
                finalQuery += it
                nineKeyDigits = ""
                segIndex = 0
            }
        }
        if (finalQuery != query) emit(finalQuery)
        onSearch(finalQuery)
    }

    /** 完成（百度右列）：先按搜索语义提交拼写中的词并搜，再收起键盘。 */
    fun handleDone() {
        handleSearch()
        onDismiss()
    }

    LaunchedEffect(query) {
        if (query != lastEmittedQuery) {
            clearPinyinState()
            lastEmittedQuery = query
        }
    }

    // 打开即把焦点落到首键，遥控可直接按方向键选键。
    // 第 6 轮 P2-8：按 nineKeyMode 键——QWERTY↔9 键切换后当前聚焦的切换
    // 按钮随模式重组离开组合，之前没有任何重新请求焦点的调用，焦点丢失；
    // 切换后把焦点还给新模式的首键（首次打开也走同一条路）。
    // Jewel 第 9 轮 P2-6：符号板切档同款——三档任一切换都回新模式首键。
    LaunchedEffect(nineKeyMode, symbolMode) { firstKeyRequester.requestFocus() }

    // v2.0.23 贴底通栏：总高由内容定（固定部分 + 键行 × 64dp），不再由父级
    // fillMaxHeight 撑满半屏；符号档 3 行权重行 + 1 行固定底行。
    val dockedHeightDp = keyboardDockedHeightDp(
        keyRowCount = if (symbolMode) 3 else if (nineKeyMode) 4 else 3,
        showCandidateRow = showCandidateBar,
        extraFixedRows = if (symbolMode) 1 else 0,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(dockedHeightDp.dp)
            // 贴底：顶角圆角、底角直角，面板底边与屏幕底边齐平、无圆角缝。
            .background(
                OrionColors.KeyboardPanel,
                RoundedCornerShape(
                    topStart = OrionDimens.PanelCornerRadius,
                    topEnd = OrionDimens.PanelCornerRadius,
                ),
            )
            .padding(KEYBOARD_COLUMN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(KEYBOARD_ROW_SPACING_DP.dp),
    ) {
        // 顶行：页签只留拼音全键 / 拼音九键（不要笔画键盘）；右侧是拼写中文本。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyboardTab(
                text = "拼音全键",
                selected = !nineKeyMode,
                onClick = {
                    nineKeyMode = false
                    symbolMode = false
                    clearPinyinState()
                },
            )
            KeyboardTab(
                text = "拼音九键",
                selected = nineKeyMode,
                onClick = {
                    nineKeyMode = true
                    symbolMode = false
                    clearPinyinState()
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (composingDisplayText.isNotEmpty()) {
                Text(
                    text = composingDisplayText,
                    style = OrionTextStyles.Body,
                    color = OrionColors.Brand,
                )
            }
        }
        // 候选栏（键盘上方，首选蓝底）：第 80 轮把拼写文本移出本行（之前与候选挤
        // 在同一行，4 字整词预览时当前音节候选只剩 1 个可见位）；现本行只放候选
        // chip，LazyRow 横向可滑，全部候选都能点到。只在中文模式、或九键英文
        // （T9 词）时出现；全键英文没有候选栏。
        if (showCandidateBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KEYBOARD_CANDIDATE_ROW_HEIGHT_DP.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (candidates.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(candidates.take(MAX_CANDIDATES_SHOWN)) { index, ch ->
                            CandidateChip(
                                text = ch,
                                isFirst = index == 0,
                                onClick = { commitCandidate(index) },
                            )
                        }
                    }
                } else if (composingDisplayText.isNotEmpty()) {
                    Text(
                        text = when {
                            zhMode && nineKeyMode -> "继续拼…"
                            zhMode && isLastSegmentPrefix(effectivePinyin) -> "继续拼…"
                            zhMode -> "无此拼音候选，按删除修改（ü 用 v 代替，如 nv=女）"
                            else -> "无候选词"
                        },
                        style = OrionTextStyles.Small,
                        color = OrionColors.TextSecondary,
                    )
                } else {
                    Text(
                        text = when {
                            zhMode && nineKeyMode -> "中文模式：按数字键拼拼音，点候选词上屏；分词可换切分"
                            zhMode -> "中文模式：连续输入拼音，打完一起选字；空格选首选词"
                            else -> "英文模式：按数字键出候选词（如 2288=CCTV），点词上屏"
                        },
                        style = OrionTextStyles.Small,
                        color = OrionColors.TextSecondary,
                    )
                }
            }
        }
        if (symbolMode) {
            // [123] 符号板——搜含 `_`、`-`、`@` 等符号的词不用再绕手机扫码；
            // 行宽与 QWERTY 同款 10 键折算对齐。点符号直接追加进搜索框
            // （不做大小写变换），退格清多击状态与字母档共用。
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
            // 键区：左列（分词 / ?12 / 中·英）+ 中间键盘 + 右列（删除 / 完成），
            // 左右列与键行同高、不再单占行高；键行 weight 均分剩余高度。
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyboardKey(
                        label = "分词",
                        onClick = { onSegmentKey() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    KeyboardKey(
                        label = "?12",
                        onClick = {
                            symbolMode = true
                            clearPinyinState()
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    KeyboardKey(
                        label = if (zhMode) "中文" else "英文",
                        onClick = {
                            zhMode = !zhMode
                            clearPinyinState()
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Column(
                    modifier = Modifier.weight(10f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (nineKeyMode) {
                        val rows = listOf(
                            listOf("1" to ".,?!", "2" to "ABC", "3" to "DEF"),
                            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                        )
                        rows.forEachIndexed { rowIndex, row ->
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
                                                if (rowIndex == 0 && index == 0) {
                                                    Modifier.preferredFocus(firstKeyRequester)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            KeyboardKey(
                                label = "0\n空格",
                                onClick = { onSpace() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    } else {
                        // QWERTY 三行（百度电视版样式）：首行 10 键，次行 9 键两侧
                        // 各补半键占位，第三行 ZXCV + 中间空格（2 键宽）+ BNM。
                        val rows = listOf("QWERTYUIOP", "ASDFGHJKL")
                        rows.forEachIndexed { rowIndex, row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            ) {
                                if (row.length == 9) Spacer(modifier = Modifier.weight(0.5f))
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
                                if (row.length == 9) Spacer(modifier = Modifier.weight(0.5f))
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            Spacer(modifier = Modifier.weight(0.5f))
                            "ZXCV".forEach { ch ->
                                KeyboardKey(
                                    label = ch.toString(),
                                    onClick = { onLetterKey(ch) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            KeyboardKey(
                                label = "空格",
                                onClick = { onSpace() },
                                modifier = Modifier.weight(2f).fillMaxHeight(),
                            )
                            "BNM".forEach { ch ->
                                KeyboardKey(
                                    label = ch.toString(),
                                    onClick = { onLetterKey(ch) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            Spacer(modifier = Modifier.weight(0.5f))
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyboardKey(
                        label = "删除",
                        onClick = { onBackspace() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    KeyboardKey(
                        label = "完成",
                        onClick = { handleDone() },
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                    )
                    if (nineKeyMode) {
                        KeyboardKey(
                            label = "搜索",
                            onClick = { handleSearch() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** 键盘页签（拼音全键 / 拼音九键）：选中态百度蓝底白字，未选中灰底。 */
@Composable
private fun KeyboardTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onClick() }
            .orionFocus(focused, RoundedCornerShape(OrionDimens.CardCornerRadius))
            .background(
                if (selected) OrionColors.BaiduBlue else OrionColors.Overlay,
                RoundedCornerShape(OrionDimens.CardCornerRadius),
            )
            .padding(horizontal = 16.dp, vertical = KEYBOARD_KEY_VERTICAL_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrionTextStyles.Body,
            color = if (selected) Color.White else OrionColors.TextSecondary,
        )
    }
}

/** 候选栏单词：首选百度蓝底白字（百度电视版样式），其余灰底；可聚焦、遥控可选。 */
@Composable
private fun CandidateChip(
    text: String,
    isFirst: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onClick() }
            .orionFocus(focused, Capsule)
            .background(
                if (isFirst) OrionColors.BaiduBlue else OrionColors.Overlay,
                Capsule,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrionTextStyles.Body,
            color = if (isFirst) Color.White else OrionColors.Text,
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
