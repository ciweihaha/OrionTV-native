package com.oriontv.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 时间工具。 */
object TimeUtils {
    /**
     * 第 70 轮：parseXmltvTime 的两个 Regex 只编一次——原来每调一次
     *（每条 EPG 节目两条时间，整份 EPG 即数万次调用）都现编两个 Regex。
     */
    private val COLON_TZ_REGEX = Regex("([+-]\\d{2}):(\\d{2})$")
    private val ATTACHED_TZ_REGEX = Regex("^(\\d{14})([+-]\\d{4})$")
    /** 秒 → "mm:ss" / "hh:mm:ss"。 */
    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = TimeUnit.SECONDS.toHours(s)
        val minutes = TimeUnit.SECONDS.toMinutes(s) % 60
        val secs = s % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    /** 毫秒时间戳 → "HH:mm"。 */
    fun formatClock(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(millis))
    }

    /** XMLTV 时间主格式 yyyyMMddHHmmss +0800，带兜底（无时区/冒号时区/无空格贴时区）。 */
    fun parseXmltvTime(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 冒号时区 "+08:00" → "+0800"；无空格贴时区 "…120000+0800" → 补空格。
        val normalized = trimmed
            .replace(COLON_TZ_REGEX, "$1$2")
            .replace(ATTACHED_TZ_REGEX, "$1 $2")
        // 带时区分支：以字符串里的时区为准；无时区分支：按本地时间解析
        //（RN 同款；之前统一按 UTC，CST 下会偏 8 小时，P1-4）。
        for (pattern in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss ZZZ")) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(normalized)?.time
            } catch (_: Exception) {
                // 换下一个格式重试
            }
        }
        return try {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            sdf.isLenient = false
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(normalized)?.time
        } catch (_: Exception) {
            null
        }
    }
}
