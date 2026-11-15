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
    fun `pairing gate blank ip does not share lockout`() {
        var now = 1_000_000L
        val gate = PairingGate(nowMs = { now })
        // B4-P2-25：拿不到 IP 时失败不聚合——两次空 IP 失败后合法配对仍能通过
        //（之前全进 "unknown" 同一桶，5 次失败会把所有无 IP 客户端一起锁掉）。
        repeat(2) { assertFalse(gate.check("", "0000", "1234")) }
        assertTrue(gate.check("", "1234", "1234"))
        // 空 IP 再失败 5 次，也不锁定其它客户端与后续空 IP 合法配对。
        repeat(5) { assertFalse(gate.check("", "0000", "1234")) }
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
        assertTrue(gate.check("", "1234", "1234"))
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
    fun `pairing gate recordFailure counts toward lockout`() {
        // B15-P1-1：错码分支调 recordFailure 记数——之前只有 check() 记数，
        // 而错码根本到不了 check()，锁定是死代码。
        var now = 1_000_000L
        val gate = PairingGate(nowMs = { now })
        repeat(4) { gate.recordFailure("1.2.3.4") }
        // 4 次未锁定，正确码仍通过、并清零计数。
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
        repeat(5) { gate.recordFailure("1.2.3.4") }
        // 第 5 次失败后锁定：锁定期内正确码也被拒。
        assertFalse(gate.check("1.2.3.4", "1234", "1234"))
        // 其它 IP 不受影响。
        assertTrue(gate.check("5.6.7.8", "1234", "1234"))
        // 60 秒锁定期过后恢复。
        now += 61_000L
        assertTrue(gate.check("1.2.3.4", "1234", "1234"))
    }

    @Test
    fun `wrong pairing codes lock ip after 5 attempts via router`() {
        // B15-P1-1（路由级）：错码握手 5 次即锁定同一 IP——之前错码在
        // isPairingValid 里直接 403、从不进 PairingGate，锁定永不生效。
        var now = 1_000_000L
        var handshakes = 0
        val router = RemoteInputRouter(
            pairingGate = PairingGate(nowMs = { now }),
            onHandshake = { handshakes++ },
            nowMs = { now },
        )
        router.rotatePairingCode()
        val code = router.pairingCode
        // 错码保证与真码不同（真码随机，万一撞上 "0000" 就换一个）。
        val wrongCode = if (code == "0000") "0001" else "0000"
        repeat(5) {
            val bad = router.handle(
                "POST", "/handshake", mapOf("X-Pairing-Code" to wrongCode), "{}", "1.2.3.4",
            )
            assertEquals(403, bad.statusCode)
        }
        assertEquals(0, handshakes)
        // 锁定期内正确码也被拒。
        val locked = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(403, locked.statusCode)
        assertEquals(0, handshakes)
        // 其它 IP 不受影响。
        val other = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "5.6.7.8",
        )
        assertEquals(200, other.statusCode)
        assertEquals(1, handshakes)
        // 60 秒锁定期过后恢复（码 5 分钟有效期未过）。
        now += 61_000L
        val ok = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(200, ok.statusCode)
        assertEquals(2, handshakes)
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

    @Test
    fun `pairing code expires after ttl`() {
        var now = 1_000_000L
        val router = RemoteInputRouter(nowMs = { now })
        router.rotatePairingCode()
        val code = router.pairingCode
        assertTrue(router.isPairingCodeUsable())
        // TTL 内握手成功。
        val ok = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(200, ok.statusCode)
        // 过期后数字正确的码也被拒绝。
        now += RemoteInputLogic.PAIRING_CODE_TTL_MS + 1
        assertFalse(router.isPairingCodeUsable())
        val expired = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(403, expired.statusCode)
        // 轮换后新码可用。
        router.rotatePairingCode()
        assertTrue(router.isPairingCodeUsable())
        val fresh = router.handle(
            "POST", "/handshake",
            mapOf("X-Pairing-Code" to router.pairingCode), "{}", "1.2.3.4",
        )
        assertEquals(200, fresh.statusCode)
    }

    @Test
    fun `handshake issues session token accepted for messages`() {
        var now = 1_000_000L
        val router = RemoteInputRouter(nowMs = { now })
        router.rotatePairingCode()
        val code = router.pairingCode
        val handshake = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to code), "{}", "1.2.3.4",
        )
        assertEquals(200, handshake.statusCode)
        // 响应里带 32 位十六进制 token。
        val token = handshake.body.substringAfter("\"token\":\"").substringBefore("\"")
        assertEquals(32, token.length)
        assertTrue(token.all { it.isDigit() || it in 'a'..'f' })
        // 凭 token 发消息，不用再带码；错 token 拒绝。
        // P2-14（第 5 轮）：token 绑定签发时客户端 IP，同一 IP 才能用。
        val viaToken = router.handle(
            "POST", "/message", mapOf("X-Session-Token" to token),
            """{"message":"你好"}""", "1.2.3.4",
        )
        assertEquals(200, viaToken.statusCode)
        val otherIp = router.handle(
            "POST", "/message", mapOf("X-Session-Token" to token),
            """{"message":"你好"}""", "9.9.9.9",
        )
        assertEquals(403, otherIp.statusCode)
        val badToken = router.handle(
            "POST", "/message", mapOf("X-Session-Token" to "0".repeat(32)),
            """{"message":"你好"}""", "9.9.9.9",
        )
        assertEquals(403, badToken.statusCode)
        // token 过期后拒绝。
        now += RemoteInputLogic.SESSION_TOKEN_TTL_MS + 1
        assertFalse(router.isSessionTokenValid(token, "1.2.3.4"))
        val expiredToken = router.handle(
            "POST", "/message", mapOf("X-Session-Token" to token),
            """{"message":"你好"}""", "9.9.9.9",
        )
        assertEquals(403, expiredToken.statusCode)
    }

    @Test
    fun `oversized post body rejected with 413 before routing`() {
        val router = RemoteInputRouter()
        router.rotatePairingCode()
        val code = router.pairingCode
        val huge = """{"message":"${"x".repeat(20 * 1024)}"}"""
        assertTrue(huge.length > RemoteInputLogic.MAX_REQUEST_BODY_BYTES)
        val result = router.handle(
            "POST", "/message", mapOf("X-Pairing-Code" to code), huge, "1.2.3.4",
        )
        assertEquals(413, result.statusCode)
    }

    @Test
    fun `pairing gate evicts oldest ip over limit`() {
        var now = 1_000_000L
        val gate = PairingGate(nowMs = { now })
        // 150 个不同 IP 各失败 1 次（时间递增，最旧的是 10.0.0.1）。
        for (i in 1..150) {
            now += 1_000L
            assertFalse(gate.check("10.0.0.$i", "0000", "1234"))
        }
        assertEquals(RemoteInputLogic.MAX_TRACKED_IPS, gate.trackedIpCount())
    }

    @Test
    fun `previous pairing code works during grace after rotation`() {
        var now = 1_000_000L
        val router = RemoteInputRouter(nowMs = { now })
        router.rotatePairingCode()
        val oldCode = router.pairingCode
        // 轮换发生在旧码 5 分钟到期时。
        now += RemoteInputLogic.PAIRING_CODE_TTL_MS
        router.rotatePairingCode()
        val newCode = router.pairingCode
        // 轮换后 30 秒：旧码仍在 60 秒宽限期内，新码也可用。
        now += 30_000L
        assertTrue(router.matchesPairingCode(oldCode))
        val oldOk = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to oldCode), "{}", "1.2.3.4",
        )
        assertEquals(200, oldOk.statusCode)
        val newOk = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to newCode), "{}", "1.2.3.4",
        )
        assertEquals(200, newOk.statusCode)
        // 宽限期过后：旧码拒绝，新码仍可用。
        now += 31_000L
        assertFalse(router.matchesPairingCode(oldCode))
        val oldExpired = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to oldCode), "{}", "1.2.3.4",
        )
        assertEquals(403, oldExpired.statusCode)
        val newStillOk = router.handle(
            "POST", "/handshake", mapOf("X-Pairing-Code" to newCode), "{}", "1.2.3.4",
        )
        assertEquals(200, newStillOk.statusCode)
    }

    @Test
    fun `format pairing remaining`() {
        assertEquals("还剩约 5 分", RemoteInputLogic.formatPairingRemaining(300))
        assertEquals("还剩约 4 分 30 秒", RemoteInputLogic.formatPairingRemaining(270))
        assertEquals("还剩约 45 秒", RemoteInputLogic.formatPairingRemaining(45))
        assertEquals("还剩约 0 秒", RemoteInputLogic.formatPairingRemaining(0))
        assertNull(RemoteInputLogic.formatPairingRemaining(null))
    }

    @Test
    fun `session token is 32 hex chars and varies`() {
        val tokens = (1..10).map { RemoteInputLogic.generateSessionToken() }.toSet()
        assertEquals(10, tokens.size)
        tokens.forEach { token ->
            assertEquals(32, token.length)
            assertTrue(token.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    // 第 3 轮收尾 B-P2-1：并发握手不丢计数/不丢锁定。
    @Test
    fun `pairing gate survives concurrent failures from one ip`() {
        val gate = PairingGate(nowMs = { 1_000L })
        val threads = (1..10).map {
            Thread { gate.check("1.2.3.4", "0000", "1234") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertEquals(1, gate.trackedIpCount())
        // 5 次失败即锁定：正确码也被拒。
        assertFalse(gate.check("1.2.3.4", "1234", "1234"))
    }

    @Test
    fun `session tokens survive concurrent issue`() {
        val router = RemoteInputRouter(nowMs = { 1_000L })
        val tokens = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..20).map {
            // P2-14：发 token 时绑定签发 IP，校验时须用同一 IP（无 IP 的
            // 1 参调用在 P2-14 后已不存在，见 isSessionTokenValid 签名）。
            Thread { tokens.add(router.issueSessionToken(clientIp = "1.2.3.4")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertEquals(20, tokens.toSet().size)
        assertTrue(tokens.all { router.isSessionTokenValid(it, "1.2.3.4") })
    }

    // B8-P2-7：轮换与匹配并发时不读到撕裂的配对——8 线程各 50 次轮换/
    // 匹配/可用判断交错跑，结束后最终态必须自洽（当前码能匹配上、且在有效期）。
    @Test
    fun `pairing code rotation and matching stay consistent under concurrency`() {
        var now = 1_000_000L
        val router = RemoteInputRouter(nowMs = { now })
        router.rotatePairingCode()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..8).map {
            Thread {
                try {
                    repeat(50) {
                        router.rotatePairingCode()
                        val code = router.pairingCode
                        router.matchesPairingCode(code)
                        router.isPairingCodeUsable()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertTrue(errors.isEmpty())
        assertTrue(router.matchesPairingCode(router.pairingCode))
        assertTrue(router.isPairingCodeUsable())
    }

    @Test
    fun `message body limit counts utf8 bytes not characters`() {
        // 第 7 轮 P2-7：路由层 /message 体长兜底改按 UTF-8 字节数（与第 6 轮
        // P2-13 的 RemoteInputServer.readBody 一致）。6000 个中文字符的体
        // 只有约 6013 个字符、却约 18013 字节：旧实现按字符数会放行（再按
        // message 长度 6000 < 10K 也放行），新实现按字节数判 413。
        val router = RemoteInputRouter()
        router.rotatePairingCode()
        val code = router.pairingCode
        val multiByteBody = """{"message":"${"测".repeat(6000)}"}"""
        assertTrue(multiByteBody.length < RemoteInputLogic.MAX_REQUEST_BODY_BYTES)
        assertTrue(multiByteBody.toByteArray(Charsets.UTF_8).size > RemoteInputLogic.MAX_REQUEST_BODY_BYTES)
        val tooLarge = router.handle(
            "POST", "/message", mapOf("X-Pairing-Code" to code), multiByteBody, "1.2.3.4",
        )
        assertEquals(413, tooLarge.statusCode)

        // 字节数恰好等于上限时不拦：16384 个 ASCII 字符 = 16384 字节，
        // 过体长检查后因无合法会话落 403（不是 413）。
        val atLimit = router.handle(
            "POST", "/message", emptyMap(), "a".repeat(16 * 1024), "1.2.3.4",
        )
        assertEquals(403, atLimit.statusCode)
        // 超 1 字节即拦。
        val overLimit = router.handle(
            "POST", "/message", emptyMap(), "a".repeat(16 * 1024 + 1), "1.2.3.4",
        )
        assertEquals(413, overLimit.statusCode)

        // 正常中文消息仍放行。
        val ok = router.handle(
            "POST", "/message", mapOf("X-Pairing-Code" to code),
            """{"message":"周星驰的电影真好看"}""", "1.2.3.4",
        )
        assertEquals(200, ok.statusCode)
    }

    @Test
    fun `service start does not steal focus from global modal`() {
        // C12-P2-3：扫码弹窗服务拉起后落焦 effect 原来无全局弹窗门控——全局
        // 弹窗开着时服务拉起会把焦点偷回“关闭”按钮；开着时跳过，关闭后由
        // 各页的 tick effect 恢复。
        assertTrue(shouldFocusModalCloseOnEnableChange(showEnableButton = false, globalModalOpen = false))
        assertFalse(shouldFocusModalCloseOnEnableChange(showEnableButton = false, globalModalOpen = true))
        assertFalse(shouldFocusModalCloseOnEnableChange(showEnableButton = true, globalModalOpen = false))
    }
}
