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
    fun `isOlderThan detects need for update`() {
        assertTrue(VersionUtils.isOlderThan("1.7.2", "2.0.0"))
        assertFalse(VersionUtils.isOlderThan("2.0.0", "2.0.0"))
        assertFalse(VersionUtils.isOlderThan("2.0.0", "2.0.0-beta"))
    }
}
