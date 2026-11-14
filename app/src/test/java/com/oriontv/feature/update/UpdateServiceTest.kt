package com.oriontv.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateServiceTest {

    @Test
    fun `download url is built from version`() {
        assertEquals(
            "https://dl.ciweihaha.win/orionTV.2.0.0.apk",
            UpdateService.downloadUrl("2.0.0"),
        )
        assertEquals(
            "https://dl.ciweihaha.win/orionTV.2.0.0.apk.sha256",
            UpdateService.sha256Url("2.0.0"),
        )
    }

    @Test
    fun `sha256 file parses both formats`() {
        assertEquals(
            "abc123",
            UpdateService.parseSha256File("abc123  orionTV.2.0.0.apk"),
        )
        assertEquals("abc123", UpdateService.parseSha256File("abc123"))
        assertEquals("abc123", UpdateService.parseSha256File("  abc123\n"))
        assertNull(UpdateService.parseSha256File(""))
        assertNull(UpdateService.parseSha256File("   "))
    }

    @Test
    fun `sha256 hex is stable`() {
        val hex = UpdateService.sha256Hex("hello".toByteArray())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hex)
    }
}
