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
        } catch (e: BodyTooLargeException) {
            newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "application/json",
                """{"error":"Request body too large"}""",
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
     * P1-11：读体前先用 Content-Length 预检，超 16KB 直接 413，不读整包体
     * （鉴权在路由层，读体发生在鉴权前，所以上限必须卡在读体之前）；
     * 无 Content-Length（chunked）时读完再按长度兜底 413。
     * parseBody 失败时（体过大/格式异常）返回空串，由路由报 400。
     */
    private fun readBody(session: IHTTPSession): String {
        if (session.method != Method.POST) return ""
        val declaredLength = session.headers["content-length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > RemoteInputLogic.MAX_REQUEST_BODY_BYTES) {
            throw BodyTooLargeException()
        }
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            if (body.length > RemoteInputLogic.MAX_REQUEST_BODY_BYTES) {
                throw BodyTooLargeException()
            }
            body
        } catch (e: BodyTooLargeException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    /** 请求体超限（只做控制流用，不带任何敏感信息）。 */
    private class BodyTooLargeException : Exception()

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
