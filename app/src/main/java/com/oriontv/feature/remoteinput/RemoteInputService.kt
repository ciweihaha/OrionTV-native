package com.oriontv.feature.remoteinput

/**
 * 远程输入服务骨架（plan §4.1 / §6 M3）：
 * 电视上起 12346 端口的极简 HTTP 服务（NanoHTTPD），配对码 + 二维码弹窗 +
 * 按当前聚焦字段分发输入。服务随设置开关启停，异常时设置页显示红字状态。
 * 完整实现在 M3。
 */
object RemoteInputService {
    /** 远程输入 HTTP 服务端口。 */
    const val PORT = 12346

    /** 配对码长度（6 位数字）。 */
    const val PAIR_CODE_LENGTH = 6
}
