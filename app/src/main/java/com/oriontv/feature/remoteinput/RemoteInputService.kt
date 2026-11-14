package com.oriontv.feature.remoteinput

/**
 * 远程输入服务常量（plan §4.1 / §6 M3）。
 * 实现见 [RemoteInputManager]（启停 + 配对码分发）、[RemoteInputServer]
 * （12346 端口 NanoHTTPD 服务）、[RemoteInputRouter]（纯路由逻辑）。
 */
object RemoteInputService {
    /** 远程输入 HTTP 服务端口。 */
    const val PORT = 12346

    /** 配对码长度（4 位数字，RN remoteControlService.ts 同款）。 */
    const val PAIR_CODE_LENGTH = RemoteInputLogic.PAIR_CODE_LENGTH
}
