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

data class AppendLetterResult(
    /** 拼完后的拼音缓冲。 */
    val buffer: String,
    /** 需要立即上屏的字（旧音节的首选字），没有时为 null。 */
    val commit: String?,
)

/**
 * 往拼音缓冲里加一个字母（中文模式）。
 * - 新缓冲是合法音节或合法音节前缀：直接收下。
 * - 新缓冲不合法、但旧缓冲是合法音节：先把旧音节的首选字上屏，再用这个
 *   字母开新音节（连续打 zhongguo 时，g 一来就把“中”上屏，再继续拼 guo）。
 * - 新缓冲不合法、旧缓冲也不合法：丢掉旧缓冲，用这个字母重开（纠正误输）。
 */
fun appendLetter(buffer: String, letter: Char): AppendLetterResult {
    val next = buffer + letter
    if (next.length <= 6 && (isValidSyllable(next) || isSyllablePrefix(next))) {
        return AppendLetterResult(buffer = next, commit = null)
    }
    if (isValidSyllable(buffer)) {
        return AppendLetterResult(buffer = letter.toString(), commit = getCandidates(buffer).firstOrNull())
    }
    return AppendLetterResult(buffer = letter.toString(), commit = null)
}

/** 从当前音节的候选里取第 index 个字；没有时返回 null。 */
fun selectCandidate(buffer: String, index: Int): String? = getCandidates(buffer).getOrNull(index)

/** 空格键语义：当前是合法音节时返回首选字（上屏），否则返回 null（调用方改为空格）。 */
fun commitFirstCandidate(buffer: String): String? {
    if (!isValidSyllable(buffer)) return null
    return getCandidates(buffer).firstOrNull()
}

/**
 * 9 键中文拼音拼写器（可单测，第 74 轮）：
 * 多击输入的字母在“落定”前只是待定字母——同一键 1 秒内连按是在循环切字母，
 * 循环中的字母还不是定下来的音节字母，不能直接走 appendLetter（否则 zho
 * 拼到一半会被中间字母毁掉）；换键、超时、空格、选候选、搜索时待定字母才落定，
 * 落定时走 appendLetter（旧音节合法即把首选字上屏）。
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
     * 按数字键。返回需要立即上屏的字（落定的旧音节首选字），没有时 null。
     * 未知键不改状态、返回 null。
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
            // 旧待定字母落定：并入 buffer（可能触发旧音节首选字上屏）。
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
     * 待定字母落定（空格/选候选/搜索前调）。返回需要上屏的字（落定的旧音节
     * 首选字），没有时 null；落定后 buffer 即当前音节，可再用
     * commitFirstCandidate(buffer) 取首选字。
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
