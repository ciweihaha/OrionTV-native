package com.oriontv.feature.search

/**
 * 标准普通话拼音音节表（不带声调）与手机九键 T9 映射——由 ciweihaha/TV 的
 * PinyinTable.java（v5.9.6 键盘前例）原样移植，音节/RANK/切分算法均未改动。
 *
 * 音节表是 423 条音节（ü 记作 v，不含 lo 等非音节），不是编造的词库：其中
 * 约 403–412 条是标准音节，另含 ê、biang、cei/dei/kei/sei/tei、nun/den/din、
 * fiao、shong、yai 等非常见/方言音节——多出的条目不影响常见输入的切分：
 * DP 按音节数最少、覆盖最长优先，合法音节恒占优；非常见条目只在输入本身
 * 就是该音节时才会被切出。本类不产生任何中文词——音节只用于把遥控敲出的
 * 拼音字母切分成音节，切分结果再走 getSuggest → iQIYI 建议链出中文候选
 * （见 IqiyiSuggest），中文候选全部来自 iQIYI 联想接口，不自造词典。
 *
 * Jewel 第 9 轮 P2-6：屏上键盘补拼音候选。QWERTY 逐字敲出的是拼音字母，
 * [segmentLetters] 用同一张音节表做 DP 切分（音节数最少、覆盖最长优先）；
 * 第 10 轮 S10-P2-1：九键并不走数字码切分 [segment]——屏上 9 键是
 * NineKeyInput 多击（同键连按循环切字母），[segment]/[forDigits]/
 * [isDigitCode]/[canSegment]/[encode] 目前只有单测在用、未接入生产 UI，
 * 留作数字码切分的工具 API（勿按旧注释把它当成 9 键的切分路径）。
 *
 * 九键数字键位（与手机一致）：2 abc、3 def、4 ghi、5 jkl、6 mno、7 pqrs、8 tuv、9 wxyz。
 */
object PinyinTable {

    /** 标准拼音音节（不带声调），同一数字编码内按常用程度大致排序，排在前面的优先显示。 */
    private val SYLLABLES: List<String> = listOf(
            "a", "ai", "an", "ang", "ao", "e", "ê", "ei", "en", "eng",
            "er", "o", "ou", "ba", "bo", "bu", "bi", "bao", "ban", "bai",
            "bei", "ben", "beng", "bang", "bian", "biang", "biao", "bie", "bin", "bing",
            "pa", "po", "pu", "pi", "pao", "pan", "pai", "pei", "pen", "peng",
            "pang", "pian", "piao", "pie", "pin", "ping", "pou", "ma", "mo", "mou",
            "mu", "mi", "mao", "man", "mai", "mei", "men", "meng", "mang", "mian",
            "miao", "mie", "min", "ming", "miu", "me", "fa", "fo", "fu", "fan",
            "fang", "fei", "fen", "feng", "fou", "fiao", "da", "de", "du", "di",
            "dia", "din", "dao", "dan", "dai", "dang", "deng", "dong", "dou", "dian",
            "diang", "diao", "die", "ding", "diu", "duan", "dui", "dun", "duo", "dei",
            "den", "ta", "te", "tei", "tu", "ti", "tao", "tan", "tai", "tang",
            "teng", "tong", "tou", "tian", "tiao", "tie", "ting", "tuan", "tui", "tun",
            "tuo", "na", "ni", "nia", "nu", "nun", "nv", "nao", "nan", "nai",
            "nang", "neng", "nian", "niao", "nie", "nin", "ning", "niu", "nong", "nou",
            "nuan", "nuo", "nve", "ne", "nei", "nen", "niang", "la", "li", "lu",
            "lv", "lao", "lan", "lai", "lang", "leng", "lian", "liang", "liao", "lie",
            "lin", "ling", "liu", "long", "lou", "luan", "lvan", "lun", "luo", "lvn",
            "lve", "le", "lei", "lia", "ga", "ge", "gu", "gao", "gan", "gai",
            "geng", "gong", "gou", "gua", "guo", "guai", "gui", "guan", "guang", "gun",
            "gei", "gen", "gang", "ka", "ke", "kei", "ku", "kao", "kan", "kai",
            "keng", "kong", "kou", "kua", "kuo", "kuai", "kui", "kuan", "kuang", "kun",
            "ken", "kang", "ha", "he", "hu", "hao", "han", "hai", "hang", "heng",
            "hong", "hou", "hua", "huo", "huai", "hui", "huan", "huang", "hun", "hei",
            "hen", "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiu",
            "ju", "juan", "jue", "jun", "jiong", "qi", "qia", "qian", "qiang", "qiao",
            "qie", "qin", "qing", "qiu", "qu", "quan", "que", "qun", "qiong", "xi",
            "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiu", "xu", "xuan",
            "xue", "xun", "xiong", "zha", "zhe", "zhi", "zhu", "zhao", "zhan", "zhai",
            "zhang", "zheng", "zhong", "zhou", "zhua", "zhuo", "zhuai", "zhui", "zhuan", "zhuang",
            "zhun", "zhei", "zhen", "cha", "che", "chi", "chu", "chao", "chan", "chai",
            "chang", "cheng", "chong", "chou", "chua", "chuo", "chuai", "chui", "chuan", "chuang",
            "chun", "chen", "sha", "she", "shi", "shu", "shao", "shan", "shai", "shang",
            "sheng", "shong", "shou", "shua", "shuo", "shuai", "shui", "shuan", "shuang", "shun",
            "shei", "shen", "re", "ri", "ru", "rao", "ran", "rang", "reng", "rong",
            "rou", "rua", "ruan", "rui", "run", "ruo", "ren", "za", "ze", "zi",
            "zu", "zao", "zan", "zai", "zang", "zeng", "zong", "zou", "zuo", "zui",
            "zuan", "zun", "zei", "zen", "ca", "ce", "cei", "ci", "cu", "cao",
            "can", "cai", "cang", "ceng", "cong", "cou", "cuo", "cui", "cuan", "cun",
            "cen", "sa", "se", "sei", "si", "su", "sao", "san", "sai", "sang",
            "seng", "song", "sou", "suo", "sui", "suan", "sun", "sen", "ya", "yai",
            "yi", "yu", "yao", "yan", "yang", "ye", "yin", "ying", "you", "yuan",
            "yue", "yun", "yo", "yong", "wa", "wu", "wo", "wai", "wan", "wang",
            "wei", "wen", "weng",    )

    /**
     * 常用音节优先顺序（粗略按现代汉语使用频率排列，仅用于决定同一数字编码下默认显示哪一个音节；
     * 用户点拼写条上的音节可切换到同编码的任一其他音节）。RANK 已覆盖全部多候选编码的音节：同一数字编码的默认项按频率排，未列出的音节（只在单候选编码里）按音节表顺序排在后面。
     */
    private val RANK: List<String> = listOf(
            "de", "shi", "yi", "le", "ji", "ge", "zhi", "wo", "ni", "bu",
            "you", "ta", "he", "zai", "ren", "shen", "me", "dou", "zhe", "na",
            "lai", "qu", "shuo", "hui", "neng", "xia", "shang", "zhong", "guo", "jiu",
            "mei", "kan", "xiang", "dao", "huan", "gang", "bian", "zhen", "hao", "gao",
            "dian", "ying", "sou", "suo", "kong", "zhan", "dong", "xi", "ai", "qing",
            "wei", "wen", "jian", "xin", "xiao", "da", "xian", "zhao", "zuo", "wan",
            "yang", "qi", "si", "fa", "ma", "ju", "li", "lu", "tu", "cheng",
            "chu", "shou", "tou", "tian", "guan", "chan", "chang", "fang", "dang", "tang",
            "lang", "kang", "hang", "zhang", "qiang", "wang", "huang", "guang", "kuang", "zhuang",
            "chuang", "shuang", "feng", "peng", "meng", "deng", "teng", "geng", "keng", "heng",
            "zheng", "sheng", "reng", "zeng", "ceng", "seng", "bing", "ping", "ming", "ding",
            "ting", "ning", "ling", "jing", "xing", "an", "ao", "ba", "bai", "ban",
            "bang", "bao", "ben", "beng", "bi", "biao", "bie", "bo", "ca", "cai",
            "can", "cang", "cao", "cen", "chao", "che", "chun", "chuo", "ci", "cu",
            "cun", "cuo", "dan", "dei", "den", "di", "diao", "du", "dun", "duo",
            "ei", "en", "fan", "fei", "fen", "fo", "fou", "fu", "ga", "gai",
            "gan", "gei", "gen", "gong", "gou", "gu", "gua", "guai", "gui", "gun",
            "ha", "hai", "han", "hei", "hen", "hong", "hou", "hu", "hua", "huai",
            "hun", "huo", "jia", "jiao", "jie", "jin", "juan", "jue", "jun", "ka",
            "kai", "kao", "ke", "kou", "ku", "kuan", "kun", "kuo", "la", "lan",
            "lao", "leng", "lia", "lian", "liao", "lie", "lin", "liu", "long", "lou",
            "luan", "lun", "luo", "lv", "lve", "mai", "man", "mang", "mao", "men",
            "mi", "mian", "miao", "mie", "min", "miu", "mu", "nai", "nan", "nang",
            "nao", "ne", "nei", "nen", "nian", "niao", "nie", "nin", "niu", "nu",
            "nv", "ou", "pa", "pai", "pan", "pang", "pao", "pen", "pi", "pian",
            "piao", "pie", "pin", "pou", "pu", "qia", "qian", "qiao", "qie", "qin",
            "qiu", "quan", "qun", "ran", "rang", "rao", "re", "ri", "rong", "rou",
            "ru", "ruan", "rui", "run", "ruo", "sa", "sai", "san", "sang", "sao",
            "se", "sen", "sha", "shan", "shao", "she", "shu", "shun", "song", "su",
            "suan", "sui", "sun", "tan", "tao", "tiao", "tun", "tuo", "wa", "wai",
            "weng", "wu", "xie", "xiong", "xiu", "xu", "xuan", "xue", "xun", "ya",
            "yan", "yao", "ye", "yin", "yo", "yong", "yu", "yuan", "yue", "yun",
            "za", "zan", "zang", "zao", "ze", "zei", "zen", "zha", "zhu", "zhun",
            "zhuo", "zi", "zong", "zou", "zu", "zuan", "zun", "liang", "mou", "kei",
            "cei", "sei", "nun", "shong", "biang", "fiao", "lvn", "bei", "jiang", "lei",
            "nou", "nuo", "pei", "qiong", "yai", "lvan",    )

    private val BY_DIGITS: Map<String, List<String>> by lazy {
        val rankIndex = RANK.withIndex().associate { (i, s) -> s to i }
        val map = mutableMapOf<String, MutableList<String>>()
        for (syllable in SYLLABLES) {
            map.getOrPut(encode(syllable)) { mutableListOf() }.add(syllable)
        }
        // 同一编码的候选按常用程度排序，排在第一位的作为默认切分结果。
        for (list in map.values) {
            list.sortWith(compareBy { rankIndex[it] ?: Int.MAX_VALUE })
        }
        map
    }

    private val SYLLABLE_SET: Set<String> by lazy { SYLLABLES.toSet() }

    /** 音节总数。 */
    fun count(): Int = SYLLABLES.size

    /** 全部音节（只读）。 */
    fun syllables(): List<String> = SYLLABLES

    /** 是否为标准音节。 */
    fun isSyllable(text: String?): Boolean = text != null && SYLLABLE_SET.contains(text)

    /** 把一个音节编码成九键数字串（a/b/c→2，d/e/f→3，g/h/i→4，j/k/l→5，m/n/o→6，p/q/r/s→7，t/u/v→8，w/x/y/z→9）。 */
    fun encode(syllable: String?): String {
        if (syllable == null) return ""
        val sb = StringBuilder()
        for (c in syllable) sb.append(digitOf(c))
        return sb.toString()
    }

    private fun digitOf(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        else -> '9'
    }

    /** 编码成该数字串的全部音节（按常用程度排序），没有则返回空列表。 */
    fun forDigits(digits: String): List<String> = BY_DIGITS[digits] ?: emptyList()

    /** 该数字串是否恰好是一个音节的编码。 */
    fun isDigitCode(digits: String?): Boolean =
        digits != null && BY_DIGITS.containsKey(digits)

    /**
     * 把数字序列切分成音节：取能覆盖的最长前缀（音节数最少者优先），
     * 覆盖不到的尾部数字原样保留为 remainder（用户继续按键后会重新切分）。
     */
    fun segment(digits: String?): Segmentation {
        if (digits.isNullOrEmpty()) return Segmentation(emptyList(), 0, "")
        val n = digits.length
        val best = arrayOfNulls<List<String>>(n + 1)
        best[0] = emptyList()
        for (i in 0 until n) {
            val current = best[i] ?: continue
            for (len in 1..6) {
                if (i + len > n) break
                val cands = BY_DIGITS[digits.substring(i, i + len)] ?: continue
                val next = current + cands[0]
                if (best[i + len] == null || next.size < best[i + len]!!.size) best[i + len] = next
            }
        }
        var covered = 0
        for (i in n downTo 0) {
            if (best[i] != null) {
                covered = i
                break
            }
        }
        val syllables = if (covered == 0) emptyList() else best[covered]!!
        return Segmentation(syllables, covered, digits.substring(covered))
    }

    /** 数字序列能否完整切分成音节。 */
    fun canSegment(digits: String?): Boolean {
        if (digits.isNullOrEmpty()) return false
        return segment(digits).covered == digits.length
    }

    /**
     * 把 QWERTY 敲出的拼音字母切分成音节（与 [segment] 同款 DP，只是直接比对
     * 字母串而非数字码）：音节数最少者优先、覆盖最长前缀；覆盖不到的尾部字母
     * 原样保留为 remainder（用户继续按键后会重新切分）。输入须已是小写字母串。
     */
    fun segmentLetters(text: String?): Segmentation {
        if (text.isNullOrEmpty()) return Segmentation(emptyList(), 0, "")
        val n = text.length
        val best = arrayOfNulls<List<String>>(n + 1)
        best[0] = emptyList()
        for (i in 0 until n) {
            val current = best[i] ?: continue
            for (len in 1..6) {
                if (i + len > n) break
                val piece = text.substring(i, i + len)
                if (!SYLLABLE_SET.contains(piece)) continue
                val next = current + piece
                if (best[i + len] == null || next.size < best[i + len]!!.size) best[i + len] = next
            }
        }
        var covered = 0
        for (i in n downTo 0) {
            if (best[i] != null) {
                covered = i
                break
            }
        }
        val syllables = if (covered == 0) emptyList() else best[covered]!!
        return Segmentation(syllables, covered, text.substring(covered))
    }

    /**
     * 是否像拼音输入（可单测）：2 个以上纯小写字母、且至少能切出一个标准音节。
     * 只有这类输入才走 iQIYI 联想取中文候选；单个字母、含中文/符号/空格的输入
     * 不发联想请求（中文直接走服务端搜索、历史联想照常）。
     */
    fun isPinyinLike(text: String?): Boolean {
        if (text == null || text.length < 2) return false
        if (!text.all { it in 'a'..'z' }) return false
        return segmentLetters(text).syllables.isNotEmpty()
    }

    /** 切分结果：syllables 为已切出的音节，covered 为覆盖的字符个数，remainder 为未覆盖的尾部字符。 */
    data class Segmentation(
        val syllables: List<String>,
        val covered: Int,
        val remainder: String,
    ) {
        /** 已切出的音节连写（不带空格），用作联想的拼音串；remainder 另行附加。 */
        fun joined(): String = syllables.joinToString("")

        fun isEmpty(): Boolean = syllables.isEmpty() && remainder.isEmpty()
    }
}
