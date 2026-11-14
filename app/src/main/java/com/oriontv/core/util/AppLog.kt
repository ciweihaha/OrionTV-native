package com.oriontv.core.util

/**
 * 日志（D-P2-11）：数据层之前用 `java.util.logging`，Android 上默认不进 logcat，
 * 脏数据跳过与 EPG 防护失效都无法在线上排查。这里统一走 `android.util.Log`；
 * JVM 单测里 `android.util.Log` 是 stub（调即抛），回落到 `java.util.logging`，
 * 保证单测不炸。只在“记一条日志”的低频路径用，不进热路径。
 */
object AppLog {
    fun w(tag: String, message: String) {
        try {
            android.util.Log.w(tag, message)
        } catch (_: RuntimeException) {
            java.util.logging.Logger.getLogger(tag).warning(message)
        }
    }

    fun i(tag: String, message: String) {
        try {
            android.util.Log.i(tag, message)
        } catch (_: RuntimeException) {
            java.util.logging.Logger.getLogger(tag).info(message)
        }
    }
}
