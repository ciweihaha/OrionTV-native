package com.oriontv.feature.remoteinput

import java.security.SecureRandom

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

    /** 校验配对码；通过返回 true，失败记一次并返回 false。 */
    fun check(clientIp: String, code: String, expectedCode: String): Boolean {
        val ip = clientIp.ifBlank { "unknown" }
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
        return false
    }

    fun clear() = attemptsByIp.clear()
}

/**
 * 远程输入 HTTP 路由（RN remoteControlService.ts 的 setupRequestHandler 对照）：
 * GET / → 配对页；POST /handshake → 校验码；POST /message → 校验码后分发文本。
 * 其它一律 404；处理异常 500。配对失败 403。
 */
class RemoteInputRouter(
    private val pairingGate: PairingGate = PairingGate(),
    private val onMessage: (String) -> Unit = {},
    private val onHandshake: () -> Unit = {},
) {
    @Volatile
    var pairingCode: String = ""
        private set

    /** 服务启动成功后轮换配对码（启动失败不废旧码，RN 同款）。 */
    fun rotatePairingCode(): String {
        pairingCode = RemoteInputLogic.generatePairingCode()
        pairingGate.clear()
        return pairingCode
    }

    fun clearPairingCode() {
        pairingCode = ""
        pairingGate.clear()
    }

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
                    onHandshake()
                    HttpResult(200, "application/json", """{"status":"ok"}""")
                }
                method == "POST" && path == "/message" -> {
                    if (!isPairingValid(headers, clientIp)) {
                        return HttpResult(403, "application/json", """{"error":"Invalid pairing code"}""")
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

    private fun isPairingValid(headers: Map<String, String>, clientIp: String): Boolean {
        val code = headers.entries.firstOrNull {
            it.key.equals("x-pairing-code", ignoreCase = true)
        }?.value.orEmpty()
        return pairingGate.check(clientIp, code, pairingCode)
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
      <p class="hint">请输入电视屏幕上显示的 4 位配对码</p>
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
    let pairingCode = "";
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
          pairingCode = code;
          sessionStorage.setItem("orion_pair_code", code);
          document.getElementById("pairSection").style.display = "none";
          document.getElementById("inputSection").style.display = "flex";
        } else {
          alert("配对码错误，请重试");
        }
      }).catch(err => console.warn('Pair failed:', err));
    }
    function send() {
      const input = document.getElementById("text");
      const value = input.value;
      if (value) {
        fetch("/message", {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Pairing-Code': pairingCode },
          body: JSON.stringify({ message: value })
        })
        .then(r => {
          if (!r.ok) {
            sessionStorage.removeItem("orion_pair_code");
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
