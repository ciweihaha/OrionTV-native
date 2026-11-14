package com.oriontv.feature.play

/**
 * 快进/快退落点预览状态机（T6/T7，参考 OrionTV-android 的 SeekPreviewController 思路，
 * 用自己的实现）：控制条可见时，左右键只移动一个 *待定* 落点（每次 10 秒），
 * 预览气泡显示方向与落点时间；此时并不 seek。按 OK 提交跳跃；按返回（或反向
 * 按过起点）取消；2 秒无操作自动提交，播放器永不显得卡住。
 * 纯逻辑类，无 Android 依赖，可单测。
 */
class SeekPreviewController(private val durationMs: Long) {

    var pendingTargetMs: Long? = null
        private set

    val hasPending: Boolean get() = pendingTargetMs != null

    /** 快进落点上限：片尾前 1 秒，seek 到正好片尾会立即 STATE_ENDED。 */
    private val maxTargetMs: Long get() = (durationMs - SEEK_END_MARGIN_MS).coerceAtLeast(0L)

    /**
     * 落点移动一步 10 秒，返回新落点。
     * 时长未出（durationMs<=0）时返回 null、不产生预览（P1-3）：
     * 加载阶段按 1ms 时长算出的 00:00 落点会在 2 秒后自动 seek 回片头。
     * 落点已在起点再反向按即取消预览、返回 null（P2-2，按类注释实现）：
     * 不再像以前那样重定基址跳变到“播头-10s”。
     */
    fun nudge(currentMs: Long, direction: Int): Long? {
        if (durationMs <= 0) return null
        val pending = pendingTargetMs
        if (direction < 0 && pending == 0L) {
            cancel()
            return null
        }
        // 待定落点若被钳制在 0 或片尾边缘、且播头已不在该处，说明它是旧的
        // 钳制值（例如先按左顶到 0，播头又走到 55 秒），从当前播头重新起步；
        // 否则在待定落点上继续累加（连续快进 10 秒一步）。
        val base = if (pending != null &&
            (pending == 0L || pending == maxTargetMs) && pending != currentMs
        ) {
            currentMs
        } else {
            pending ?: currentMs
        }
        val target = (base + direction * STEP_MS).coerceIn(0L, maxTargetMs)
        // P2-12：无待定落点、一步后仍在原地时不产生预览（播头按左得 00:00、
        // 片尾前按右也顶在原地）：否则气泡显示一个无意义的落点，2 秒后还 seek 回原地。
        // A8-P2-4：防呆只覆盖了 pending==null 的情形——待定落点已顶在播头处
        // （pending == target == currentMs，例如落点 59 秒、播头也走到 59 秒时再按右），
        // 旧实现会返回一个原地落点、气泡 2 秒后 seek 回原地；此时也返回 null 并清掉
        // 已失效的待定落点。pending 与 target 不同时仍返回 target（例如落点 10 秒、
        // 播头在 0 秒时按左回 0 秒是有效预览，见单测）。
        if (target == currentMs && (pending == null || pending == target)) {
            cancel()
            return null
        }
        pendingTargetMs = target
        return target
    }

    /** 提交待定落点（无待定时返回 null）。 */
    fun commit(): Long? {
        val target = pendingTargetMs
        pendingTargetMs = null
        return target
    }

    fun cancel() {
        pendingTargetMs = null
    }

    companion object {
        const val STEP_MS = 10_000L
        const val AUTO_COMMIT_MS = 2_000L

        /** 落点离片尾的安全余量：钳制到片尾前 1 秒，免得 seek 到片尾立即 STATE_ENDED。 */
        const val SEEK_END_MARGIN_MS = 1_000L
    }
}
