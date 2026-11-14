package com.oriontv.feature.remoteinput

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 12346 端口的 NanoHTTPD 极简 HTTP 服务（只收文本输入）。
 * 路由逻辑在 [RemoteInputRouter]（纯逻辑、可单测），这里只做 NanoHTTPD 适配：
 * 读请求方法/路径/头/体，调路由，回固定长度响应；异常一律 500，不抛回框架。
 *
 * 注意 NanoHTTPD 的 serve() 跑在它自己的线程上，回调里不做耗时操作，
 * 只把消息交给 [RemoteInputRouter.onMessage]（Manager 再转 StateFlow）。
 */
class RemoteInputServer(
    private val router: RemoteInputRouter,
    port: Int = RemoteInputService.PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            val result = router.handle(
                method = session.method.name,
                path = session.uri,
                headers = session.headers,
                body = body,
                clientIp = session.remoteIpAddress.orEmpty(),
            )
            newFixedLengthResponse(
                Response.Status.lookup(result.statusCode) ?: Response.Status.OK,
                result.contentType,
                result.body,
            )
        } catch (_: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Internal Server Error"}""",
            )
        }
    }

    /**
     * 读 POST 体：NanoHTTPD 的 parseBody 把非表单体放进 "postData"。
     * parseBody 失败时（体过大/格式异常）返回空串，由路由报 400。
     */
    private fun readBody(session: IHTTPSession): String {
        if (session.method != Method.POST) return ""
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        /** 本机局域网 IPv4（优先站点本地地址）；拿不到时返回 null。 */
        fun localIpAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                var fallback: String? = null
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            if (addr.isSiteLocalAddress) return addr.hostAddress
                            if (fallback == null) fallback = addr.hostAddress
                        }
                    }
                }
                fallback
            } catch (_: Exception) {
                null
            }
        }
    }
}
