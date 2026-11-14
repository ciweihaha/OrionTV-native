package com.oriontv.core.util

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** XMLTV 时间解析测试（第 4 轮 P1-4）。锚点：2026-11-14 12:00 +0800 = 1794628800000。 */
class TimeUtilsTest {

    @Test
    fun `parses spaced timezone`() {
        assertEquals(1794628800000L, TimeUtils.parseXmltvTime("20261114120000 +0800"))
    }

    @Test
    fun `parses glued timezone without space`() {
        assertEquals(1794628800000L, TimeUtils.parseXmltvTime("20261114120000+0800"))
    }

    @Test
    fun `parses colon timezone`() {
        assertEquals(1794628800000L, TimeUtils.parseXmltvTime("20261114120000 +08:00"))
    }

    @Test
    fun `parses timezone-less time as local time`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            // 12:00 本地（+08:00）= 04:00 UTC
            assertEquals(1794628800000L, TimeUtils.parseXmltvTime("20261114120000"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // 12:00 本地（UTC）= 12:00 UTC，比 +08:00 的同一写法晚 8 小时
            assertEquals(1794657600000L, TimeUtils.parseXmltvTime("20261114120000"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `returns null for invalid input`() {
        assertNull(TimeUtils.parseXmltvTime("not-a-time"))
        assertNull(TimeUtils.parseXmltvTime(""))
        assertNull(TimeUtils.parseXmltvTime("20261301120000 +0800"))
    }
}
