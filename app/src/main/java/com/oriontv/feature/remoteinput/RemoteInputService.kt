package com.oriontv.feature.remoteinput

/**
 * 远程输入服务常量（plan §4.1 / §6 M3）。
 * 实现见 [RemoteInputManager]（启停 + 配对码分发）、[RemoteInputServer]
 * （12346 端口 NanoHTTPD 服务）、[RemoteInputRouter]（纯路由逻辑）。
 */
object RemoteInputService {
    /** 远程输入 HTTP 服务端口。 */
    const val PORT = 12346

    /**
     * 第 76 轮 v2.0.14：候选端口（按顺序试）——12346 被占（RN 版 com.oriontv
     * 同端口，点“手机扫码输入”报 Address already in use）时顺延到下一端口，
     * 不再直接报错。见 RemoteInputServer.startOnFirstFreePort。
     */
    val PORT_CANDIDATES: List<Int> = listOf(12346, 12347, 12348, 12349, 12350)

    /** 配对码长度（4 位数字，RN remoteControlService.ts 同款）。 */
    const val PAIR_CODE_LENGTH = RemoteInputLogic.PAIR_CODE_LENGTH
}
