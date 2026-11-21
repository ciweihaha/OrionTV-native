package com.oriontv.feature.search

/**
 * 电视端内置拼音输入的纯逻辑（与 UI 无关，可单测；第 74 轮）：
 * 无声调拼音 -> 候选汉字，输入一个音节、选字上屏。
 * 由 OrionTV-build utils/pinyinInput.ts 移植（RN v1.7.3–v1.7.4 拼音键盘同款逻辑），
 * 词典见 PinyinCandidates（由 RN 的 pinyinCandidates.ts 转来）。
 * ü 用 v 表示（nv=女、lv=绿），与常见输入法惯例一致。
 */

/** 全部合法无声调音节。 */
private val SYLLABLES: Set<String> = PINYIN_CANDIDATES.keys

/** 拼音缓冲总长度上限（字母数）：多音节一起拼，如 xiaochengliangfang 18 字母，24 够 4~5 个音节。 */
private const val MAX_PINYIN_BUFFER_LENGTH = 24

/** 合法音节的全部真前缀（用于边打边判断“还能不能继续拼”）。 */
private val SYLLABLE_PREFIXES: Set<String> = buildSet {
    for (s in SYLLABLES) {
        for (i in 1 until s.length) add(s.substring(0, i))
    }
}

/** 该拼音的候选字（按常用度排好）；不是合法音节时返回空列表。 */
fun getCandidates(pinyin: String): List<String> {
    if (pinyin.isEmpty()) return emptyList()
    return PINYIN_CANDIDATES[pinyin] ?: emptyList()
}

/** 是不是一个完整合法音节（如 zhong、jie）。 */
fun isValidSyllable(pinyin: String): Boolean = SYLLABLES.contains(pinyin)

/** 是不是某个合法音节的前缀（如 zh、zho：还能继续往下拼）。 */
fun isSyllablePrefix(pinyin: String): Boolean = SYLLABLE_PREFIXES.contains(pinyin)

/** 把拼音字母串贪心切成音节（每段取能匹配的最长合法音节；最后一段可能只是还没拼完的前缀）。 */
fun segmentPinyin(input: String): List<String> {
    val segments = mutableListOf<String>()
    var rest = input
    while (rest.isNotEmpty()) {
        var matched: String? = null
        for (len in minOf(6, rest.length) downTo 1) {
            val head = rest.substring(0, len)
            if (isValidSyllable(head)) {
                matched = head
                break
            }
        }
        if (matched != null) {
            segments.add(matched)
            rest = rest.substring(matched.length)
        } else {
            // 剩下的拼不成完整音节，整体当最后一段（前缀或非法）。
            segments.add(rest)
            break
        }
    }
    return segments
}

/** 拼音串是否可继续拼：前面每段都是完整音节、最后一段是完整音节或合法前缀。 */
fun isComposablePinyin(input: String): Boolean {
    if (input.isEmpty()) return false
    val segments = segmentPinyin(input)
    return segments.dropLast(1).all { isValidSyllable(it) } &&
        (isValidSyllable(segments.last()) || isSyllablePrefix(segments.last()))
}

/** 全是完整音节时，各音节首选字拼成的词（如 zhongguo -> 中国）；否则 null。 */
fun phraseFirstCandidate(input: String): String? {
    val segments = segmentPinyin(input)
    if (segments.isEmpty() || segments.any { !isValidSyllable(it) }) return null
    return segments.joinToString("") { getCandidates(it).firstOrNull() ?: "" }
}

/** 末段是不是还没拼完的前缀（候选行显示“继续拼…”用）。 */
fun isLastSegmentPrefix(input: String): Boolean {
    val last = segmentPinyin(input).lastOrNull() ?: return false
    return !isValidSyllable(last) && isSyllablePrefix(last)
}

/** 已选字 + 其余完整音节的首选字拼成的整词预览；有未完成音节时 null。 */
fun phrasePreview(input: String, picked: List<String>): String? {
    val segments = segmentPinyin(input)
    if (segments.isEmpty() || segments.any { !isValidSyllable(it) }) return null
    return segments.mapIndexed { i, seg ->
        picked.getOrNull(i) ?: getCandidates(seg).firstOrNull() ?: ""
    }.joinToString("")
}

/** 当前待选的音节（picked 覆盖之后的第一个完整音节）；没有时 null。
 * 末段还没拼完时返回 null——用户还在打字，先不急着选前面的音节。 */
fun currentSyllable(input: String, picked: List<String>): String? {
    val segments = segmentPinyin(input)
    if (segments.isEmpty() || segments.any { !isValidSyllable(it) }) return null
    return segments.getOrNull(picked.size)
}

/** 当前待选音节的候选字。 */
fun currentSyllableCandidates(input: String, picked: List<String>): List<String> {
    val seg = currentSyllable(input, picked) ?: return emptyList()
    return getCandidates(seg)
}

/** 候选行展示顺序：整词预览在首位（多音节且全是完整音节时），其后是当前待选音节的字（UI 层再截断到 10 个）。 */
fun displayCandidates(input: String, picked: List<String>): List<String> {
    if (input.isEmpty()) return emptyList()
    val segments = segmentPinyin(input)
    val preview = if (segments.size >= 2) phrasePreview(input, picked) else null
    return listOfNotNull(preview) + currentSyllableCandidates(input, picked)
}

/** 点选候选行第 index 个（展示顺序与 [displayCandidates] 一致）。
 * 返回（要上屏的整词，没有时 null；选好后的 picked 列表）：
 * 整词位：直接上屏整词；字位：记下该字，未选完继续选下一音节，选完则上屏整词。 */
fun selectCandidateStep(input: String, picked: List<String>, index: Int): Pair<String?, List<String>> {
    val segments = segmentPinyin(input)
    if (segments.isEmpty()) return null to picked
    val preview = if (segments.size >= 2) phrasePreview(input, picked) else null
    if (preview != null && index == 0) return preview to emptyList()
    val charIndex = if (preview != null) index - 1 else index
    val ch = currentSyllableCandidates(input, picked).getOrNull(charIndex) ?: return null to picked
    val next = picked + ch
    return if (next.size >= segments.size) {
        next.joinToString("") to emptyList()
    } else {
        null to next
    }
}

/** 空格/搜索：已选字 + 剩余音节首选字拼成的整词上屏；有未完成音节时 null。 */
fun commitPickedPhrase(input: String, picked: List<String>): String? = phrasePreview(input, picked)

/** 缓冲改动后保留仍有效的已选字：前面音节没变的保留，变了的及之后全丢。 */
fun retainPicked(oldInput: String, newInput: String, picked: List<String>): List<String> {
    val oldSeg = segmentPinyin(oldInput)
    val newSeg = segmentPinyin(newInput)
    val kept = mutableListOf<String>()
    for (i in picked.indices) {
        if (i < newSeg.size && i < oldSeg.size && newSeg[i] == oldSeg[i] && isValidSyllable(newSeg[i])) {
            kept.add(picked[i])
        } else break
    }
    return kept
}

data class AppendLetterResult(
    /** 拼完后的拼音缓冲。 */
    val buffer: String,
    /** 需要立即上屏的字（旧音节的首选字），没有时为 null。 */
    val commit: String?,
)

/**
 * 往拼音缓冲里加一个字母（中文模式；第 79 轮改：不再自动上屏）。
 * - 接得上（整串可拼：前面是完整音节、末段是完整音节或合法前缀）：直接收下，不提交任何字——
 *   用户打完整串拼音再一起选，首选字不再半路自动上屏。
 * - 接不上：丢掉旧缓冲，用这个字母重开（纠正误输），同样不提交任何字。
 * 任何情况下 commit 恒为 null；上屏只发生在点选候选、空格、搜索时。
 */
fun appendLetter(buffer: String, letter: Char): AppendLetterResult {
    val next = buffer + letter
    if (next.length <= MAX_PINYIN_BUFFER_LENGTH && isComposablePinyin(next)) {
        return AppendLetterResult(buffer = next, commit = null)
    }
    return AppendLetterResult(buffer = letter.toString(), commit = null)
}

/** 从当前音节的候选里取第 index 个字；没有时返回 null。 */
fun selectCandidate(buffer: String, index: Int): String? = getCandidates(buffer).getOrNull(index)

/** 空格/搜索键语义：整串都是完整音节时返回首选词（多音节拼成词，如 zhongguo -> 中国），否则返回 null（调用方改为空格/不提交）。 */
fun commitFirstCandidate(buffer: String): String? = phraseFirstCandidate(buffer)

/**
 * 9 键中文拼音拼写器（可单测，第 74 轮）：
 * 多击输入的字母在“落定”前只是待定字母——同一键 1 秒内连按是在循环切字母，
 * 循环中的字母还不是定下来的音节字母，不能直接走 appendLetter（否则 zho
 * 拼到一半会被中间字母毁掉）；换键、超时、空格、选候选、搜索时待定字母才落定，
 * 落定时走 appendLetter（第 79 轮起落定也不自动上屏，只并入缓冲，等空格/选词/搜索再上屏）。
 * 同键连续字母（如 zhong 里的 o、n 都在 6 键）须停顿 1 秒以上再按下一字母——
 * 停顿后按同键算换键（待定字母落定、新字母重开），这是多击输入的老规矩，
 * 键盘提示里会写明。
 */
class PinyinNineKeyComposer(
    private val multiTapWindowMs: Long = NineKeyInput.MULTI_TAP_WINDOW_MS,
) {
    /** 已落定的音节片段（恒为合法前缀或合法音节）。 */
    var buffer: String = ""
        private set

    /** 正在循环的待定字母（未并入 buffer），没有时为 null。 */
    var pendingLetter: Char? = null
        private set

    private var lastDigit: String? = null
    private var lastTapAtMs: Long = -1L
    private var cycleIndex: Int = 0

    /** 当前拼写中的完整文本（buffer + 待定字母），供候选查询与展示。 */
    val composingText: String get() = buffer + (pendingLetter?.toString() ?: "")

    /**
     * 按数字键。第 79 轮起不再自动上屏：旧待定字母落定只并入 buffer，
     * 返回恒为 null（保留返回值是为了不改调用方签名）；上屏只走候选行
     * 点选、空格、搜索。未知键不改状态、返回 null。
     */
    fun press(digit: String, nowMs: Long): String? {
        val letters = NineKeyInput.KEY_GROUPS[digit] ?: return null
        val continuing = digit == lastDigit && lastTapAtMs >= 0 &&
            nowMs - lastTapAtMs < multiTapWindowMs && pendingLetter != null
        return if (continuing) {
            cycleIndex = (cycleIndex + 1) % letters.length
            pendingLetter = letters[cycleIndex]
            lastTapAtMs = nowMs
            null
        } else {
            // 旧待定字母落定：并入 buffer（第 79 轮起不再自动上屏）。
            var commit: String? = null
            pendingLetter?.let {
                val r = appendLetter(buffer, it)
                buffer = r.buffer
                commit = r.commit
            }
            lastDigit = digit
            cycleIndex = 0
            pendingLetter = letters[0]
            lastTapAtMs = nowMs
            commit
        }
    }

    /**
     * 待定字母落定（空格/选候选/搜索前调）。第 79 轮起返回恒为 null
     *（不再自动上屏旧音节首选字）；落定后 buffer 即当前音节，可再用
     * commitPickedPhrase(buffer, picked) 取整词。
     */
    fun settle(): String? {
        val letter = pendingLetter ?: return null
        pendingLetter = null
        lastDigit = null
        lastTapAtMs = -1L
        cycleIndex = 0
        val r = appendLetter(buffer, letter)
        buffer = r.buffer
        return r.commit
    }

    /**
     * 退格：先删待定字母，再删 buffer 末字母；都空时返回 false
     *（调用方再删搜索框里的字符）。
     */
    fun backspace(): Boolean {
        if (pendingLetter != null) {
            pendingLetter = null
            lastDigit = null
            lastTapAtMs = -1L
            cycleIndex = 0
            return true
        }
        if (buffer.isNotEmpty()) {
            buffer = buffer.dropLast(1)
            return true
        }
        return false
    }

    fun reset() {
        buffer = ""
        pendingLetter = null
        lastDigit = null
        lastTapAtMs = -1L
        cycleIndex = 0
    }
}
