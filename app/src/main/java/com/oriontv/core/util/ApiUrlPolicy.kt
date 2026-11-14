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
        if (lower.startsWith("https://")) return Check.Https
        if (!lower.startsWith("http://")) return Check.Invalid
        val host = lower.removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
            .substringBefore("?")
        if (host.isEmpty()) return Check.Invalid
        return if (isLocalHost(host)) Check.PlainHttpLocal else Check.PlainHttpPublic
    }

    /**
     * 回环 / 局域网 / .local：localhost、127.*、10.*、172.16-31.*、
     * 192.168.*、::1、*.local、单段主机名（无点，只在局域网解析）。
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim().trim('[', ']')
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local")) return true
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
