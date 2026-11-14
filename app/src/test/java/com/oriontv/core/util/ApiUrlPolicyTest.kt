package com.oriontv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** ApiUrlPolicy 测试（D-P2-8）：公网 http 提示，局域网/回环除外。 */
class ApiUrlPolicyTest {

    @Test
    fun `https is safe`() {
        assertEquals(ApiUrlPolicy.Check.Https, ApiUrlPolicy.check("https://example.com"))
        assertEquals(ApiUrlPolicy.Check.Https, ApiUrlPolicy.check("  https://example.com/api  "))
    }

    @Test
    fun `public http warns`() {
        assertEquals(ApiUrlPolicy.Check.PlainHttpPublic, ApiUrlPolicy.check("http://example.com"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpPublic, ApiUrlPolicy.check("http://example.com:8080/api"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpPublic, ApiUrlPolicy.check("HTTP://EXAMPLE.COM"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpPublic, ApiUrlPolicy.check("http://172.32.0.1"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpPublic, ApiUrlPolicy.check("http://8.8.8.8"))
    }

    @Test
    fun `lan and loopback http are exempt`() {
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://192.168.1.10:8080"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://10.0.0.5"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://172.16.0.1"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://172.31.255.255"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://127.0.0.1:8080"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://localhost:8080"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://nas.local"))
        assertEquals(ApiUrlPolicy.Check.PlainHttpLocal, ApiUrlPolicy.check("http://moon-tv"))
    }

    @Test
    fun `empty or schemeless is invalid`() {
        assertEquals(ApiUrlPolicy.Check.Invalid, ApiUrlPolicy.check(""))
        assertEquals(ApiUrlPolicy.Check.Invalid, ApiUrlPolicy.check("   "))
        assertEquals(ApiUrlPolicy.Check.Invalid, ApiUrlPolicy.check("example.com"))
        assertEquals(ApiUrlPolicy.Check.Invalid, ApiUrlPolicy.check("http://"))
    }
}
