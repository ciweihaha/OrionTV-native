package com.oriontv.core.util

/**
 * 服务器地址明文校验（D-P2-8）：用户填 `http://` 地址时登录密码明文传输。
 * 只做判定、不拦截保存——自建 MoonTV 常在局域网跑 http（局域网/回环除外，
 * 由产品定）；公网 http 由调用方（设置页保存时）提示用户。
 */
object ApiUrlPolicy {
    sealed interface Check {
        /** https：安全。 */
        data object Https : Check

        /** 公网 http：密码明文传输，应提示。 */
        data object PlainHttpPublic : Check

        /** 局域网/回环 http：例外，不提示。 */
        data object PlainHttpLocal : Check

        /** 空或无 http(s) scheme：不是有效地址。 */
        data object Invalid : Check
    }

    fun check(rawUrl: String): Check {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Check.Invalid
        val lower = url.lowercase()
        val isHttps = when {
            lower.startsWith("https://") -> true
            lower.startsWith("http://") -> false
            else -> return Check.Invalid
        }
        // B14-P2-5：https 分支同样取 host 判空/合法——旧实现 https 直接放行，
        // `https://` 空地址可保存（设置页只拦 Invalid）；IPv6 字面地址按 [...]
        // 取完整 host，不按 : 截断（`http://[2001:db8::1]` 旧实现截成 `[2001`
        // 当成本地）。
        val host = extractHost(lower.substringAfter("://"))
        if (host.isEmpty() || host.any { it.isWhitespace() }) return Check.Invalid
        if (isHttps) return Check.Https
        return if (isLocalHost(host)) Check.PlainHttpLocal else Check.PlainHttpPublic
    }

    /**
     * 从 authority（scheme 之后的部分）取 host：IPv6 字面地址按 [...] 取完整
     * host（含括号，供 [isLocalHost] 去括号判断）；未闭合的 `[` 即非法；
     * 其余按 : 截端口、按 / 与 ? 截路径。
     */
    internal fun extractHost(authority: String): String {
        val hostPort = authority.substringBefore("/").substringBefore("?")
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return ""
            return hostPort.substring(0, end + 1)
        }
        return hostPort.substringBefore(":")
    }

    /**
     * 回环 / 局域网 / .local：localhost、127.*、10.*、172.16-31.*、
     * 192.168.*、::1、*.local、单段主机名（无点，只在局域网解析）。
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim().trim('[', ']')
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local")) return true
        // B14-P2-5：IPv6 字面地址（取完整 host 后才到这里）：含 : 即 IPv6，
        // 只有回环 ::1、链路本地 fe80::/10、唯一本地 fc00::/7 算本地；
        // 公网地址（如 2001:db8::1）旧实现经 substringBefore(":") 截断后
        // 无点、一律当成本地。未压缩全写 ::1（0:0:0:0:0:0:0:1）同样算回环。
        if (h.contains(':')) {
            if (h == "0:0:0:0:0:0:0:1") return true
            val first = h.substringBefore(":")
            if (first.isEmpty()) return false
            val firstGroup = first.toIntOrNull(16) ?: return false
            if (firstGroup in 0xfe80..0xfebf) return true
            if (firstGroup in 0xfc00..0xfdff) return true
            return false
        }
        if (!h.contains('.')) return true
        val parts = h.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 127) return true
        }
        return false
    }
}
