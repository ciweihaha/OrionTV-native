package com.oriontv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `valid versions pass regex`() {
        assertTrue(VersionUtils.isValidVersion("2.0.0"))
        assertTrue(VersionUtils.isValidVersion("1.7.2"))
        assertTrue(VersionUtils.isValidVersion("2.0.0-beta.1"))
        assertFalse(VersionUtils.isValidVersion("2.0"))
        assertFalse(VersionUtils.isValidVersion("v2.0.0"))
        assertFalse(VersionUtils.isValidVersion("2.0.0-"))
    }

    @Test
    fun `numeric segments compare numerically`() {
        assertTrue(VersionUtils.compare("2.0.0", "1.7.2") > 0)
        assertTrue(VersionUtils.compare("1.7.10", "1.7.2") > 0)
        assertEquals(0, VersionUtils.compare("2.0.0", "2.0.0"))
        assertTrue(VersionUtils.compare("1.7.2", "2.0.0") < 0)
    }

    @Test
    fun `prerelease is lower than same-number release`() {
        assertTrue(VersionUtils.compare("2.0.0-beta", "2.0.0") < 0)
        assertTrue(VersionUtils.compare("2.0.0-rc.1", "2.0.0") < 0)
        assertTrue(VersionUtils.compare("2.0.0-beta", "2.0.0-rc") < 0)
    }

    @Test
    fun `stripVersionPrefix removes v prefix for AggApi validation`() {
        // D-P2-4：根治点在 AggApi，strip 在校验之前。
        assertEquals("2.0.0", VersionUtils.stripVersionPrefix("v2.0.0"))
        assertEquals("2.0.0", VersionUtils.stripVersionPrefix("V2.0.0"))
        assertEquals("2.0.0", VersionUtils.stripVersionPrefix("  v2.0.0  "))
        assertEquals("2.0.0", VersionUtils.stripVersionPrefix("2.0.0"))
        assertTrue(VersionUtils.isValidVersion(VersionUtils.stripVersionPrefix("v2.0.0")))
    }

    @Test
    fun `prerelease numeric identifiers compare numerically semver 11`() {
        // 第 37 轮：之前整串字典序，rc.10 < rc.9（'1' < '9'）是错的。
        assertTrue(VersionUtils.compare("2.0.0-rc.10", "2.0.0-rc.9") > 0)
        assertTrue(VersionUtils.compare("2.0.0-beta.10", "2.0.0-beta.2") > 0)
        assertTrue(VersionUtils.compare("2.0.0-rc.9", "2.0.0-rc.10") < 0)
        assertEquals(0, VersionUtils.compare("2.0.0-rc.1", "2.0.0-rc.1"))
        // 前导零按数值等同；超大数字不溢出仍按数值比。
        assertEquals(0, VersionUtils.compare("2.0.0-rc.007", "2.0.0-rc.7"))
        assertTrue(
            VersionUtils.compare("2.0.0-rc.99999999999999999999", "2.0.0-rc.10") > 0,
        )
    }

    @Test
    fun `prerelease numeric identifier is lower than alphanumeric semver 11`() {
        assertTrue(VersionUtils.compare("2.0.0-1", "2.0.0-alpha") < 0)
        assertTrue(VersionUtils.compare("2.0.0-alpha.1", "2.0.0-alpha.beta") < 0)
        // 前缀全等时段数少的更小；字母数字段仍按字典序。
        assertTrue(VersionUtils.compare("2.0.0-alpha", "2.0.0-alpha.1") < 0)
        assertTrue(VersionUtils.compare("2.0.0-alpha.1", "2.0.0-alpha.2") < 0)
        // 影响点：rc.9 装机收到 rc.10 不再被判定为“已是新版”。
        assertTrue(VersionUtils.isOlderThan("2.0.0-rc.9", "2.0.0-rc.10"))
        assertFalse(VersionUtils.isOlderThan("2.0.0-rc.10", "2.0.0-rc.9"))
    }

    @Test
    fun `isOlderThan detects need for update`() {
        assertTrue(VersionUtils.isOlderThan("1.7.2", "2.0.0"))
        assertFalse(VersionUtils.isOlderThan("2.0.0", "2.0.0"))
        assertFalse(VersionUtils.isOlderThan("2.0.0", "2.0.0-beta"))
    }
}
