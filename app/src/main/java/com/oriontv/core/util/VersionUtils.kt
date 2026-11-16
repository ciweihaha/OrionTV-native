package com.oriontv.core.util

/**
 * 版本号比较（plan §5.1）：
 * - 按数字段比较；
 * - 带 -beta/-rc 等后缀的低于同数字正式版；
 * - 版本号先过正则 ^\d+\.\d+\.\d+(-[\w.]+)?$。
 */
object VersionUtils {
    private val VERSION_REGEX = Regex("""^\d+\.\d+\.\d+(-[\w.]+)?$""")

    fun isValidVersion(version: String): Boolean = VERSION_REGEX.matches(version)

    /**
     * 去 "v"/"V" 前缀（D-P2-4）：服务端一旦带 v 返回，根治点在数据层
     * （AggApi.fetchVersion 先 strip 再校验），调用方不再各自 strip。
     */
    fun stripVersionPrefix(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    /**
     * 比较两个版本号。
     * @return 负数表示 [a] < [b]，0 表示相等，正数表示 [a] > [b]。
     *
     * 预发布段按 semver §11 逐标识符比较（第 37 轮）：只含数字的标识符按数值比
     * （`rc.10` > `rc.9`，之前整串字典序会算反）；数字标识符低于字母数字标识符；
     * 前缀相同的段数少的更小。之前是 `String.compareTo` 整串比，`rc.10` 会小于 `rc.9`。
     */
    fun compare(a: String, b: String): Int {
        val (aNums, aPre) = split(a)
        val (bNums, bPre) = split(b)
        for (i in 0 until maxOf(aNums.size, bNums.size)) {
            val av = aNums.getOrElse(i) { 0 }
            val bv = bNums.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        // 数字段相同：无后缀（正式版）> 有后缀（预发布）
        if (aPre == null && bPre == null) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1
        return comparePrerelease(aPre, bPre)
    }

    /**
     * 按 semver §11 比较预发布段（`aPre`/`bPre` 为 `-` 后的部分）：
     * - 以 `.` 切标识符逐个比；
     * - 两个都是纯数字：按数值比（去前导零后先比长度再比字典序，不溢出）；
     * - 一个数字一个字母数字：数字的小；
     * - 两个字母数字：ASCII 字典序；
     * - 前缀全等时段数少的更小。
     */
    internal fun comparePrerelease(aPre: String, bPre: String): Int {
        val aIds = aPre.split('.')
        val bIds = bPre.split('.')
        for (i in 0 until minOf(aIds.size, bIds.size)) {
            val aId = aIds[i]
            val bId = bIds[i]
            if (aId == bId) continue
            val aIsNum = aId.isNotEmpty() && aId.all { it.isDigit() }
            val bIsNum = bId.isNotEmpty() && bId.all { it.isDigit() }
            if (aIsNum && bIsNum) return compareNumericStrings(aId, bId)
            if (aIsNum) return -1 // 数字标识符低于字母数字标识符
            if (bIsNum) return 1
            return aId.compareTo(bId)
        }
        return aIds.size.compareTo(bIds.size)
    }

    /**
     * 纯数字串按数值比：去前导零后先比长度再比字典序，不溢出；
     * `007` 与 `7` 数值相等（semver 禁前导零，比较时按数值等同处理）。
     */
    private fun compareNumericStrings(a: String, b: String): Int {
        val aTrimmed = a.trimStart('0').ifEmpty { "0" }
        val bTrimmed = b.trimStart('0').ifEmpty { "0" }
        if (aTrimmed.length != bTrimmed.length) return aTrimmed.length.compareTo(bTrimmed.length)
        return aTrimmed.compareTo(bTrimmed)
    }

    /** 当前版本是否低于目标版本（即需要更新）。 */
    fun isOlderThan(current: String, target: String): Boolean = compare(current, target) < 0

    private fun split(version: String): Pair<List<Int>, String?> {
        val dash = version.indexOf('-')
        val core = if (dash >= 0) version.substring(0, dash) else version
        val pre = if (dash >= 0) version.substring(dash + 1) else null
        val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
        return nums to pre
    }
}
