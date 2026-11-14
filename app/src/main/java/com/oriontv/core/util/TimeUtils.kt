package com.oriontv.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 时间工具。 */
object TimeUtils {
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

    /** XMLTV 时间主格式 yyyyMMddHHmmss +0800，带兜底（无时区/冒号时区）。 */
    fun parseXmltvTime(raw: String): Long? {
        val trimmed = raw.trim()
        val patterns = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmss ZZZ",
            "yyyyMMddHHmmss",
        )
        // 冒号时区 "+08:00" → "+0800"
        val normalized = trimmed.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(normalized)?.time
            } catch (_: Exception) {
                // 换下一个格式重试
            }
        }
        return null
    }
}
