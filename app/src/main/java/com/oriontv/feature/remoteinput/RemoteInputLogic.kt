package com.oriontv.feature.remoteinput

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程输入纯逻辑（plan §5.4 orion_pair_code，RN remoteControlService.ts 对照）：
 * 配对码 4 位数字（RN 同款长度）、恒定时间比较、按 IP 失败锁定、
 * 极简 HTTP 路由（GET / 配对页、POST /handshake、POST /message）。
 * 不依赖 Android，可单测。
 */
object RemoteInputLogic {
    /** 配对码长度：4 位数字（RN remoteControlService.ts 的 PAIRING_CODE_LENGTH）。 */
    const val PAIR_CODE_LENGTH = 4

    /** 远程消息长度上限 10KB（RN 同款）。 */
    const val MAX_MESSAGE_LENGTH = 10 * 1024

    /** POST 请求体上限 16KB（P1-11：超限直接 413，不读整包体进内存）。 */
    const val MAX_REQUEST_BODY_BYTES = 16 * 1024

    /** 配对码有效期 5 分钟（P1-10：过期作废，由管理器定期轮换）。 */
    const val PAIRING_CODE_TTL_MS = 5 * 60 * 1000L

    /** P2-8（第 5 轮）：轮换后上一轮配对码的宽限期 60 秒（正在配对的那一刻不被轮换打断）。 */
    const val PAIRING_CODE_GRACE_MS = 60_000L

    /** P2-8（第 5 轮）：配对码倒计时刷新间隔 5 秒。 */
    const val PAIRING_CODE_COUNTDOWN_TICK_MS = 5_000L

    /** 配对成功后发的会话 token 有效期 24 小时（P1-10：码只用于换 token）。 */
    const val SESSION_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L

    /** 会话 token 表上限 32 条（P2-14：第 5 轮起加，超限淘汰签发最早的一条）。 */
    const val MAX_SESSION_TOKENS = 32

    /** 同一 IP 连续失败 5 次锁定 60 秒（RN 同款，按 IP 分离计数）。 */
    const val MAX_PAIRING_ATTEMPTS = 5
    const val PAIRING_LOCKOUT_MS = 60_000L

    /** 失败计数有效期 1 小时；跟踪 IP 上限 100 条。 */
    const val FAILURE_WINDOW_MS = 60 * 60 * 1000L
    const val MAX_TRACKED_IPS = 100

    /**
     * 密码学安全随机配对码：拒绝采样（丢弃 250–255，消除 %10 的模偏差），
     * 不用 Math.random（RN 同款注释：可预测）。
     */
    fun generatePairingCode(random: SecureRandom = SecureRandom()): String {
        val digits = StringBuilder()
        val buf = ByteArray(PAIR_CODE_LENGTH * 2)
        while (digits.length < PAIR_CODE_LENGTH) {
            random.nextBytes(buf)
            for (b in buf) {
                if (digits.length >= PAIR_CODE_LENGTH) break
                val unsigned = b.toInt() and 0xFF
                if (unsigned < 250) digits.append((unsigned % 10).toString())
            }
        }
        return digits.toString()
    }

    /** 配对成功后发的会话 token：16 字节随机数转 32 位十六进制（P1-10）。 */
    fun generateSessionToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 配对码剩余时间文案（纯函数，可单测）：“还剩约 5 分”/“还剩约 4 分 30 秒”/“还剩约 45 秒”。 */
    fun formatPairingRemaining(seconds: Int?): String? {
        if (seconds == null) return null
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 60 && s % 60 == 0 -> "还剩约 ${s / 60} 分"
            s >= 60 -> "还剩约 ${s / 60} 分 ${s % 60} 秒"
            else -> "还剩约 $s 秒"
        }
    }

    /** 恒定时间字符串比较：固定遍历全长，不提前返回（RN 同款）。 */
    fun timingSafeEqual(a: String, b: String): Boolean {
        val len = maxOf(a.length, b.length)
        var diff = a.length xor b.length
        for (i in 0 until len) {
            val ca = a.getOrNull(i)?.code ?: 0
            val cb = b.getOrNull(i)?.code ?: 0
            diff = diff or (ca xor cb)
        }
        return diff == 0
    }

    /** 从 JSON 体里取 "message" 字符串字段（只做最小解析，处理转义）。 */
    fun parseMessageJson(body: String): String? {
        val keyIndex = body.indexOf("\"message\"")
        if (keyIndex < 0) return null
        var i = body.indexOf(':', keyIndex + 9)
        if (i < 0) return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    when (val n = body[i + 1]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 5 < body.length) {
                                val hex = body.substring(i + 2, i + 6)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                        }
                        else -> out.append(n)
                    }
                    i += 2
                }
                c == '"' -> return out.toString()
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null // 未闭合引号：JSON 不完整
    }
}

/** HTTP 路由结果（纯数据，供 NanoHTTPD 层翻译成 Response）。 */
data class HttpResult(
    val statusCode: Int,
    val contentType: String,
    val body: String,
)

/**
 * 配对门：按客户端 IP 分离计数失败，5 次失败锁定 60 秒；成功清零；
 * 失败计数 1 小时无新失败即过期。时间由调用方注入，便于单测。
 */
class PairingGate(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class IpState(val failures: Int, val lockoutUntil: Long, val lastFailureAt: Long)

    private val attemptsByIp = mutableMapOf<String, IpState>()

    /**
     * 校验配对码；通过返回 true，失败记一次并返回 false。
     * 第 3 轮收尾 B-P2-1：NanoHTTPD 每请求独立线程调 check()，读-改-写全程
     * 须同步，否则并发握手可丢计数/丢锁定。
     */
    @Synchronized
    fun check(clientIp: String, code: String, expectedCode: String): Boolean {
        val ip = ipKey(clientIp)
        val now = nowMs()
        val state = attemptsByIp[ip]
        if (state != null) {
            if (now < state.lockoutUntil) return false
            if (state.failures == 0 || now - state.lastFailureAt > RemoteInputLogic.FAILURE_WINDOW_MS) {
                attemptsByIp.remove(ip)
            }
        }
        val valid = expectedCode.isNotEmpty() &&
            RemoteInputLogic.timingSafeEqual(code, expectedCode)
        if (valid) {
            attemptsByIp.remove(ip)
            return true
        }
        recordFailureLocked(ip, now)
        return false
    }

    /**
     * B15-P1-1：错码记一次失败（供 [RemoteInputRouter.isPairingValid] 在
     * matchesPairingCode 失败时调用——之前错码直接 403、从不进 check()，
     * 失败计数永不累加，“同一 IP 连续失败 5 次锁定 60 秒”是死代码）。
     * 锁定期内不记数、不延长锁定；过期/清零态先清旧条再记，与 check() 的
     * 失败分支同款记账（含 P2-24 淘汰）。成功仍走 check() 清零。
     */
    @Synchronized
    fun recordFailure(clientIp: String) {
        val ip = ipKey(clientIp)
        val now = nowMs()
        val state = attemptsByIp[ip]
        if (state != null) {
            if (now < state.lockoutUntil) return
            if (state.failures == 0 || now - state.lastFailureAt > RemoteInputLogic.FAILURE_WINDOW_MS) {
                attemptsByIp.remove(ip)
            }
        }
        recordFailureLocked(ip, now)
    }

    /**
     * 失败记账（调用方已持锁、已做锁定与过期复核）：计数 +1，满 5 次锁定
     * 60 秒并清零计数；超跟踪上限时先清过期条、仍超限再淘汰最旧一条。
     */
    private fun recordFailureLocked(ip: String, now: Long) {
        val failures = (attemptsByIp[ip]?.failures ?: 0) + 1
        if (failures >= RemoteInputLogic.MAX_PAIRING_ATTEMPTS) {
            attemptsByIp[ip] = IpState(
                failures = 0,
                lockoutUntil = now + RemoteInputLogic.PAIRING_LOCKOUT_MS,
                lastFailureAt = now,
            )
        } else {
            attemptsByIp[ip] = IpState(failures, 0L, now)
        }
        if (attemptsByIp.size > RemoteInputLogic.MAX_TRACKED_IPS) {
            attemptsByIp.entries.removeIf { (_, s) ->
                (s.failures == 0 && now >= s.lockoutUntil) ||
                    now - s.lastFailureAt > RemoteInputLogic.FAILURE_WINDOW_MS
            }
        }
        // P2-24：仍超限时按 lastFailureAt 最旧淘汰一条（分布式多 IP 各失败 1 次时
        // map 不再只增不减）。
        if (attemptsByIp.size > RemoteInputLogic.MAX_TRACKED_IPS) {
            attemptsByIp.entries
                .minByOrNull { it.value.lastFailureAt }
                ?.key?.let { attemptsByIp.remove(it) }
        }
    }

    /**
     * B4-P2-25：拿不到 IP 时不再全进 "unknown" 同一桶——不同连接
     * 共用一个计数会互相锁定；用唯一键单独计数，失败不聚合、不共享锁定。
     */
    private fun ipKey(clientIp: String): String =
        clientIp.ifBlank { "unknown:" + java.util.UUID.randomUUID().toString() }

    @Synchronized
    fun clear() = attemptsByIp.clear()

    /** 跟踪中的 IP 条数（P2-24 淘汰逻辑单测用）。 */
    @Synchronized
    fun trackedIpCount(): Int = attemptsByIp.size
}

/**
 * 远程输入 HTTP 路由（RN remoteControlService.ts 的 setupRequestHandler 对照）：
 * GET / → 配对页；POST /handshake → 校验码；POST /message → 校验码后分发文本。
 * 其它一律 404；处理异常 500。配对失败 403。
 *
 * P1-10：配对码自签发 5 分钟内有效，过期作废；握手成功发会话 token，
 * 之后 /message 凭 token（或仍在有效期内的码）访问；码本身不长期有效。
 * P1-11：POST 体超过 16KB 直接 413（在路由层兜底，服务端读体前另有
 * Content-Length 预检，见 RemoteInputServer）。
 */
class RemoteInputRouter(
    private val pairingGate: PairingGate = PairingGate(),
    private val onMessage: (String) -> Unit = {},
    private val onHandshake: () -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var pairingCode: String = ""
        private set

    /** 当前配对码签发时间（毫秒）；0 = 未签发。 */
    @Volatile
    var pairingCodeIssuedAtMs: Long = 0L
        private set

    /** 上一轮配对码（P2-8：轮换后 60 秒宽限期内仍可用）；"" = 无上一轮。 */
    @Volatile
    var previousPairingCode: String = ""
        private set

    /** 上一轮配对码签发时间（毫秒）；0 = 无上一轮。 */
    @Volatile
    var previousPairingCodeIssuedAtMs: Long = 0L
        private set

    /**
     * B8-P2-7：配对码四元组（当前码/签发时间 + 上一轮码/签发时间）的轮换锁——
     * 四个 @Volatile 字段各自可见，但轮换不是原子的；HTTP 处理线程读
     * matchesPairingCode 可能读到撕裂的配对（新码 + 旧时间）。轮换、清码、
     * 匹配、可用判断都进同一把锁。
     */
    private val pairingCodeLock = Any()

    /** 已发会话 token（P2-14：第 5 轮起绑定签发时客户端 IP）。 */
    private data class SessionToken(
        val expiresAtMs: Long,
        val clientIp: String,
        val issuedAtMs: Long,
    )

    /**
     * 已发会话 token → [SessionToken]。
     * 第 3 轮收尾 B-P2-1：与 PairingGate 同款问题，NanoHTTPD 多线程并发
     * 读写，换 ConcurrentHashMap（读-改-写单步操作本身已原子）。
     */
    private val sessionTokens = ConcurrentHashMap<String, SessionToken>()

    /** 服务启动成功后轮换配对码（启动失败不废旧码，RN 同款）。 */
    fun rotatePairingCode(): String = synchronized(pairingCodeLock) {
        previousPairingCode = pairingCode
        previousPairingCodeIssuedAtMs = pairingCodeIssuedAtMs
        pairingCode = RemoteInputLogic.generatePairingCode()
        pairingCodeIssuedAtMs = nowMs()
        pairingGate.clear()
        pairingCode
    }

    fun clearPairingCode() {
        synchronized(pairingCodeLock) {
            pairingCode = ""
            pairingCodeIssuedAtMs = 0L
            previousPairingCode = ""
            previousPairingCodeIssuedAtMs = 0L
        }
        pairingGate.clear()
        sessionTokens.clear()
    }

    /** 当前配对码是否在有效期内（未签发/过期都算无效）。 */
    fun isPairingCodeUsable(now: Long = nowMs()): Boolean = synchronized(pairingCodeLock) {
        pairingCode.isNotEmpty() &&
            pairingCodeIssuedAtMs > 0 &&
            now - pairingCodeIssuedAtMs <= RemoteInputLogic.PAIRING_CODE_TTL_MS
    }

    /**
     * P2-8（第 5 轮）：配对码匹配——当前码在 5 分钟有效期内，或上一轮的码
     * 在轮换后 60 秒宽限期内。用户在第 4 分 59 秒输入旧码、提交时刚好轮换，
     * 以前即判过期；宽限期让“正在配对的那一刻”不被轮换打断。
     * 纯逻辑，可单测。
     */
    fun matchesPairingCode(code: String, now: Long = nowMs()): Boolean =
        synchronized(pairingCodeLock) {
            if (code.isEmpty()) return@synchronized false
            if (RemoteInputLogic.timingSafeEqual(code, pairingCode) &&
                pairingCodeIssuedAtMs > 0 &&
                now - pairingCodeIssuedAtMs <= RemoteInputLogic.PAIRING_CODE_TTL_MS
            ) return@synchronized true
            RemoteInputLogic.timingSafeEqual(code, previousPairingCode) &&
                previousPairingCodeIssuedAtMs > 0 &&
                now - previousPairingCodeIssuedAtMs <=
                RemoteInputLogic.PAIRING_CODE_TTL_MS + RemoteInputLogic.PAIRING_CODE_GRACE_MS
        }

    /** 校验会话 token（过期即清掉并返回 false；P2-14：须与签发时同一 IP，嗅到即用不再成立）。 */
    fun isSessionTokenValid(token: String, clientIp: String, now: Long = nowMs()): Boolean {
        if (token.isEmpty()) return false
        val stored = sessionTokens[token] ?: return false
        if (now > stored.expiresAtMs) {
            sessionTokens.remove(token)
            return false
        }
        return stored.clientIp == normalizedIp(clientIp)
    }

    /** 握手成功后发一个会话 token（调用方把它写进响应；P2-14：绑定签发时客户端 IP）。 */
    fun issueSessionToken(now: Long = nowMs(), clientIp: String = ""): String {
        pruneSessionTokens(now)
        // P2-14：token 表加 32 条上限——多次握手不再只增不减；先清过期，
        // 仍超限时淘汰签发最早的一条。
        if (sessionTokens.size >= RemoteInputLogic.MAX_SESSION_TOKENS) {
            sessionTokens.entries.minByOrNull { it.value.issuedAtMs }?.key?.let {
                sessionTokens.remove(it)
            }
        }
        val token = RemoteInputLogic.generateSessionToken()
        sessionTokens[token] = SessionToken(
            expiresAtMs = now + RemoteInputLogic.SESSION_TOKEN_TTL_MS,
            clientIp = normalizedIp(clientIp),
            issuedAtMs = now,
        )
        return token
    }

    private fun pruneSessionTokens(now: Long) {
        sessionTokens.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun normalizedIp(clientIp: String): String = clientIp.ifBlank { "unknown" }

    fun handle(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        clientIp: String,
    ): HttpResult {
        return try {
            when {
                method == "GET" && path == "/" -> HttpResult(
                    200,
                    "text/html; charset=utf-8",
                    RemoteInputPageHtml.html,
                )
                method == "POST" && path == "/handshake" -> {
                    if (!isPairingValid(headers, clientIp)) {
                        return HttpResult(403, "application/json", """{"error":"Invalid pairing code"}""")
                    }
                    val token = issueSessionToken(clientIp = clientIp)
                    onHandshake()
                    HttpResult(200, "application/json", """{"status":"ok","token":"$token"}""")
                }
                method == "POST" && path == "/message" -> {
                    // P1-11：体超限直接 413（路由层兜底；读体前另有预检）。
                    // 第 7 轮 P2-7：按 UTF-8 字节数判（isRequestBodyTooLarge，
                    // 与第 6 轮 P2-13 的 RemoteInputServer.readBody 兜底一致）——
                    // 旧实现 body.length 计 UTF-16 码元，多字节字符为主的体
                    // 可在字符数 ≤16K 时超过 16KB 字节；上游已先按字节拦掉，
                    // 这里是死检查，改过来只为语义一致。
                    if (isRequestBodyTooLarge(body)) {
                        return HttpResult(413, "application/json", """{"error":"Request body too large"}""")
                    }
                    if (!isSessionValid(headers, clientIp)) {
                        return HttpResult(403, "application/json", """{"error":"Invalid pairing code or session"}""")
                    }
                    val message = RemoteInputLogic.parseMessageJson(body)
                    if (message.isNullOrEmpty()) {
                        return HttpResult(400, "application/json", """{"error":"Missing message"}""")
                    }
                    if (message.length > RemoteInputLogic.MAX_MESSAGE_LENGTH) {
                        return HttpResult(413, "application/json", """{"error":"Message too long"}""")
                    }
                    onMessage(message)
                    HttpResult(200, "application/json", """{"status":"ok"}""")
                }
                else -> HttpResult(404, "text/plain", "Not Found")
            }
        } catch (_: Exception) {
            HttpResult(500, "application/json", """{"error":"Internal Server Error"}""")
        }
    }

    /** 握手只认有效期内的配对码（过期码即使数字正确也拒绝；P2-8 含 60 秒轮换宽限期）。 */
    private fun isPairingValid(headers: Map<String, String>, clientIp: String): Boolean {
        val code = headers.entries.firstOrNull {
            it.key.equals("x-pairing-code", ignoreCase = true)
        }?.value.orEmpty()
        if (!matchesPairingCode(code)) {
            // B15-P1-1：错码先记一次失败再返回 403——之前错码直接 return false，
            // 从不进 PairingGate.check()，失败计数永不累加、“5 次锁定 60 秒”是死代码。
            pairingGate.recordFailure(clientIp)
            return false
        }
        // 失败计数与锁定仍走 PairingGate：期望码取实际匹配的那一个（当前码
        // 或宽限期内的上一轮码），否则宽限期内的旧码会被 gate 的比较挡掉。
        val expected = if (RemoteInputLogic.timingSafeEqual(code, pairingCode)) {
            pairingCode
        } else {
            previousPairingCode
        }
        return pairingGate.check(clientIp, code, expected)
    }

    /** /message 认会话 token（P2-14：须与签发时同一 IP），兼容仍在有效期内的配对码（老客户端/首次发送）。 */
    private fun isSessionValid(headers: Map<String, String>, clientIp: String): Boolean {
        val token = headers.entries.firstOrNull {
            it.key.equals("x-session-token", ignoreCase = true)
        }?.value.orEmpty()
        if (token.isNotEmpty() && isSessionTokenValid(token, clientIp)) return true
        return isPairingValid(headers, clientIp)
    }
}

/** 手机端配对/输入页（RN getRemotePageHTML 对照：配对码 4 位，sessionStorage 续配）。 */
object RemoteInputPageHtml {
    val html: String = """
<!DOCTYPE html>
<html>
<head>
  <title>OrionTV Remote</title>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <style>
    body { font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #121212; color: white; }
    h3 { color: #eee; }
    #container { display: flex; flex-direction: column; align-items: center; width: 90%; max-width: 400px; }
    #text, #code { width: 100%; padding: 15px; font-size: 16px; border-radius: 8px; border: 1px solid #333; background-color: #2a2a2a; color: white; margin-bottom: 20px; box-sizing: border-box; }
    button { width: 100%; padding: 15px; font-size: 18px; font-weight: bold; border: none; border-radius: 8px; background-color: #FF6A3D; color: white; cursor: pointer; }
    button:active { background-color: #CC542F; }
    button:disabled { background-color: #444; }
    #inputSection { width: 100%; display: none; flex-direction: column; align-items: center; }
    .hint { color: #888; font-size: 14px; margin-bottom: 20px; text-align: center; }
  </style>
</head>
<body>
  <div id="container">
    <div id="pairSection" style="display: block; width: 100%;">
      <h3>配对验证</h3>
      <p class="hint">请输入电视屏幕上显示的 4 位配对码（5 分钟内有效）</p>
      <input id="code" placeholder="配对码" inputmode="numeric" maxlength="4" />
      <button onclick="pair()">配对</button>
    </div>
    <div id="inputSection">
      <h3>向电视发送文本</h3>
      <input id="text" placeholder="请输入..." />
      <button onclick="send()">发送</button>
    </div>
  </div>
  <script>
    // P1-10：配对成功后服务端发会话 token（24 小时有效），之后发消息凭 token；
    // 配对码只用于一次性换 token，5 分钟过期。token 与码都存在 sessionStorage，
    // 关掉手机浏览器标签页即失效。
    let pairingCode = "";
    let sessionToken = sessionStorage.getItem("orion_session_token") || "";
    const savedCode = sessionStorage.getItem("orion_pair_code");
    if (savedCode) {
      pairingCode = savedCode;
      document.getElementById("pairSection").style.display = "none";
      document.getElementById("inputSection").style.display = "flex";
    }
    function pair() {
      const input = document.getElementById("code");
      const code = input.value.trim();
      if (!code) return;
      fetch('/handshake', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Pairing-Code': code },
        body: JSON.stringify({})
      }).then(r => {
        if (r.ok) {
          return r.json().then(data => {
            pairingCode = code;
            sessionToken = (data && data.token) || "";
            sessionStorage.setItem("orion_pair_code", code);
            if (sessionToken) sessionStorage.setItem("orion_session_token", sessionToken);
            document.getElementById("pairSection").style.display = "none";
            document.getElementById("inputSection").style.display = "flex";
          });
        } else {
          alert("配对码错误或已过期，请核对电视屏幕上的配对码");
        }
      }).catch(err => console.warn('Pair failed:', err));
    }
    function send() {
      const input = document.getElementById("text");
      const value = input.value;
      if (value) {
        const headers = { 'Content-Type': 'application/json' };
        if (sessionToken) headers['X-Session-Token'] = sessionToken;
        else if (pairingCode) headers['X-Pairing-Code'] = pairingCode;
        fetch("/message", {
          method: 'POST',
          headers: headers,
          body: JSON.stringify({ message: value })
        })
        .then(r => {
          if (!r.ok) {
            sessionStorage.removeItem("orion_session_token");
            sessionStorage.removeItem("orion_pair_code");
            sessionToken = "";
            pairingCode = "";
            document.getElementById("pairSection").style.display = "block";
            document.getElementById("inputSection").style.display = "none";
            alert("配对已失效，请重新输入配对码");
          } else {
            input.value = '';
          }
        })
        .catch(err => console.warn('Message send failed:', err));
      }
    }
  </script>
</body>
</html>
    """.trimIndent()
}
