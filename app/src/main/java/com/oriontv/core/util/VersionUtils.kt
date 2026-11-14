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
        return aPre.compareTo(bPre)
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
