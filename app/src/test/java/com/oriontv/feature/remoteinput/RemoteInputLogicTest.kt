package com.oriontv.feature.remoteinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputLogicTest {

    @Test
    fun `pairing code is 4 digits and varies`() {
        val code = RemoteInputLogic.generatePairingCode()
        assertEquals(4, code.length)
        assertTrue(code.all { it.isDigit() })
        // 两次生成大概率不同（随机性冒烟测试，极小概率误报可接受）。
        val codes = (1..10).map { RemoteInputLogic.generatePairingCode() }.toSet()
        assertTrue(codes.size > 1)
    }

    @Test
    fun `timing safe equal compares full length`() {
        assertTrue(RemoteInputLogic.timingSafeEqual("1234", "1234"))
        assertFalse(RemoteInputLogic.timingSafeEqual("1234", "1235"))
        assertFalse(RemoteInputLogic.timingSafeEqual("1234", "123"))
        assertFalse(RemoteInputLogic.timingSafeEqual("", "1234"))
        assertTrue(RemoteInputLogic.timingSafeEqual("", ""))
    }

    @Test
    fun `parse message json handles escapes and rejects bad input`() {
        assertEquals("hello", RemoteInputLogic.parseMessageJson("""{"message":"hello"}"""))
        assertEquals("a\"b\nc", RemoteInputLogic.parseMessageJson("""{"message":"a\"b\nc"}"""))
        assertEquals("中文搜索", RemoteInputLogic.parseMessageJson("""{"message":"中文搜索"}"""))
        assertNull(RemoteInputLogic.parseMessageJson("""{"text":"hello"}"""))
        assertNull(RemoteInputLogic.parseMessageJson("""{"message":123}"""))
        assertNull(RemoteInputLogic.parseMessageJson("""{"message":"unclosed}"""))
        assertNull(RemoteInputLogic.parseMessageJson("not json"))
    }

    @Test
    fun `qr code encode returns matrix for text and null for blank`() {
        val matrix = QrCodeGenerator.encode("http://192.168.1.2:12346", 256)
        assertTrue(matrix != null)
        assertEquals(256, matrix!!.width)
        // 点阵里既有黑点也有白点。
        var black = 0
        var white = 0
        for (x in 0 until matrix.width step 7) {
            for (y in 0 until matrix.height step 7) {
                if (matrix[x, y]) black++ else white++
            }
        }
        assertTrue(black > 0)
        assertTrue(white > 0)
        assertNull(QrCodeGenerator.encode("", 256))
        assertNull(QrCodeGenerator.encode("  ", 256))
    }

    @Test
    fun `pairing gate locks ip after 5 failures and expires`() {
        var now = 1_000_000L
        val gate = PairingGate(nowMs = { now })
        repeat(4) { assertFalse(gate.check("1.2.3.4", "0000", "1234")) }
        // 第 5 次失败后锁定：即使给对的码也拒绝。
        assertFalse(gate.check("1.2.3.4", "0000", "1234"))
        assertFalse(gate.check("1.2.3.4", "1234", "1234"))
        // 其它 IP 不受影响。
        assertTrue(gate.check("5.6.7.8", "1234", "1234"))
        // 60 秒锁定期过后恢复。
        now += 61_000L
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
    }

    @Test
    fun `pairing gate success clears failure count`() {
        val gate = PairingGate()
        repeat(4) { assertFalse(gate.check("1.2.3.4", "0000", "1234")) }
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
        // 成功后计数清零：再错 4 次仍未锁定。
        repeat(4) { assertFalse(gate.check("1.2.3.4", "0000", "1234")) }
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
    }

    @Test
    fun `router serves pairing page handshake and message`() {
        var received: String? = null
        var handshakes = 0
        val router = RemoteInputRouter(
            onMessage = { received = it },
            onHandshake = { handshakes++ },
        )
        router.rotatePairingCode()
        val code = router.pairingCode
        assertEquals(4, code.length)

        val page = router.handle("GET", "/", emptyMap(), "", "1.2.3.4")
        assertEquals(200, page.statusCode)
        assertTrue(page.body.contains("orion_pair_code"))

        val badHandshake = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to "0000"), "{}", "1.2.3.4",
        )
        assertEquals(403, badHandshake.statusCode)
        assertEquals(0, handshakes)

        val okHandshake = router.handle(
            "POST", "/handshake", mapOf("x-pairing-code" to code), "{}", "1.2.3.4",
        )
        assertEquals(200, okHandshake.statusCode)
        assertEquals(1, handshakes)

        val okMessage = router.handle(
            "POST", "/message",
            mapOf("X-Pairing-Code" to code),
            """{"message":"周星驰"}""", "1.2.3.4",
        )
        assertEquals(200, okMessage.statusCode)
        assertEquals("周星驰", received)

        val missing = router.handle(
            "POST", "/message", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(400, missing.statusCode)

        val tooLong = router.handle(
            "POST", "/message", mapOf("X-Pairing-Code" to code),
            """{"message":"${"x".repeat(11 * 1024)}"}""", "1.2.3.4",
        )
        assertEquals(413, tooLong.statusCode)

        val notFound = router.handle("GET", "/nope", emptyMap(), "", "1.2.3.4")
        assertEquals(404, notFound.statusCode)
    }
}
