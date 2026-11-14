package com.oriontv.feature.play

/**
 * 片头/片尾标记纯逻辑（RN utils/introSkip.ts + playerStore.ts 对照）：
 * - 片头结束时间：手动标记优先；没标过时用设置里的默认片头时长兜底
 *   （0 表示不跳，只认手动标记）；
 * - 片尾开始时间：用户在播放时手动标记的绝对位置（毫秒）。
 *   RN 存的是“剩余时长”（duration - position），且从未被消费；
 *   原生版存绝对位置，并用它提前弹出下一集预告（见 PlayViewModel）。
 */
object IntroSkip {
    /** 算出本集真正用来跳过的片头结束时间（毫秒），没得跳时为 0。 */
    fun resolveIntroEndMs(markedMs: Long?, skipSeconds: Int): Long {
        if (markedMs != null && markedMs > 0) return markedMs
        if (skipSeconds > 0) return skipSeconds * 1000L
        return 0L
    }

    /** 是否到了片尾（已标记片尾开始时间且播到该位置）。 */
    fun isAtOutro(positionMs: Long, durationMs: Long, outroStartMs: Long?): Boolean {
        if (outroStartMs == null || durationMs <= 0) return false
        return positionMs >= outroStartMs
    }
}
