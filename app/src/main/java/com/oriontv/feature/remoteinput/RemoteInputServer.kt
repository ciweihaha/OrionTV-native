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
 *
 * 安全残留（第 5 轮 P1-3 已记录，换不掉）：NanoHTTPD 2.3.1 有
 * CVE-2022-21230（>1KB 的体以不安全权限写临时文件），且无修复版本。
 * 本服务的缓解是 16KB 上限 + 拒绝 chunked 体（见 readBody），把暴露面
 * 压到 16KB 以内；长期应评估替换 NanoHTTPD。
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
        } catch (_: ChunkedNotSupportedException) {
            newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "application/json",
                """{"error":"Chunked transfer encoding not supported"}""",
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
     * （鉴权在路由层，读体发生在鉴权前，所以上限必须卡在读体之前）。
     * P1-3（第 5 轮）：无 Content-Length 的 chunked 体直接拒绝，不读——
     * parseBody 会先把整包体读进内存/临时文件再检查长度，chunked 没有
     * Content-Length 可预检，超大体直接打爆内存（P1-11 的同款漏洞换了个
     * 编码触发）。正常客户端（手机浏览器 fetch 发 JSON）都带
     * Content-Length，不受影响；chunked 发送方只会拿到 413。
     * 读完后仍按长度兜底 413（双重保险）。
     * parseBody 失败时（体过大/格式异常）返回空串，由路由报 400。
     */
    private fun readBody(session: IHTTPSession): String {
        if (session.method != Method.POST) return ""
        val declaredLength = session.headers["content-length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > RemoteInputLogic.MAX_REQUEST_BODY_BYTES) {
            throw BodyTooLargeException()
        }
        if (isChunkedTransfer(session.headers)) {
            throw ChunkedNotSupportedException()
        }
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            // 第 6 轮 P2-13：兜底按字节数判（见 isRequestBodyTooLarge）——
            // 之前 body.length 计 UTF-16 码元，多字节字符为主的体可在字符数
            // ≤16K 时超过 16KB 字节。
            if (isRequestBodyTooLarge(body)) {
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

    /** chunked 体直接拒绝（只做控制流用；见 readBody 的 P1-3 说明）。 */
    private class ChunkedNotSupportedException : Exception()

    companion object {
        /**
         * 第 76 轮 v2.0.14：按候选顺序起服务，12346 被占（RN 版同端口）时顺延
         * 到下一端口。返回（服务，实际端口）；全部被占时返回 null，调用方报错。
         * 起失败的 server 立即 stop，不留占着端口的僵尸（NanoHTTPD 的 start()
         * 抛错时端口可能已绑上）。
         * [onServerCreated] 在每次创建 server 后立即回调（供调用方把候选
         * server 记到 pending 变量里，启动中途被取消时 catch 能停掉它，
         * 与 RemoteInputManager 的 B7-P1-1 兜底同款）。
         */
        fun startOnFirstFreePort(
            router: RemoteInputRouter,
            ports: List<Int> = RemoteInputService.PORT_CANDIDATES,
            onServerCreated: (RemoteInputServer) -> Unit = {},
        ): Pair<RemoteInputServer, Int>? {
            for (port in ports) {
                val server = RemoteInputServer(router, port)
                onServerCreated(server)
                try {
                    server.start()
                    return server to port
                } catch (_: Exception) {
                    runCatching { server.stop() }
                }
            }
            return null
        }

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

/**
 * P1-3（第 5 轮）：chunked 编码判定（纯函数，可单测）。
 * NanoHTTPD 的 headers 键已是小写，但这里仍做不区分大小写的查找，
 * 防止上游改动导致漏判；值里含 chunked 即算（可能与其他编码并列）。
 */
fun isChunkedTransfer(headers: Map<String, String>): Boolean =
    headers.entries.any {
        it.key.equals("transfer-encoding", ignoreCase = true) &&
            it.value.contains("chunked", ignoreCase = true)
    }

/**
 * 第 6 轮 P2-13：POST 体是否超 16KB 上限——按 UTF-8 字节数判，不按字符数。
 * body 是 String，length 计 UTF-16 码元；多字节字符（中文 3 字节/字符）
 * 为主的体可在字符数 ≤16K 时超过 16KB 字节，与 P1-3 的 16KB“字节”语义
 * 对齐。纯函数，可单测。
 */
fun isRequestBodyTooLarge(
    body: String,
    maxBytes: Int = RemoteInputLogic.MAX_REQUEST_BODY_BYTES,
): Boolean = body.toByteArray(Charsets.UTF_8).size > maxBytes
