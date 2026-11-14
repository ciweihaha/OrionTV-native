package com.oriontv.feature.play

/**
 * 跳过片头胶囊的轮询状态机（P0-1，第 4 轮审计）：
 * PlayViewModel 每 500ms 轮询调一次 [update]，返回胶囊是否应可见。
 * 之前“已出现过”标志与“当前可见”混在一起：显示胶囊的同时置位
 * skipIntroShownThisEpisode，下一轮询入口提前返回又把它藏起来，
 * 胶囊最多闪现一个轮询间隔（0–500ms），10 秒超时分支永不可达。
 * 这里把两者分开：
 * - “已跳过片头”才隐藏/不再出现（唯一的提前返回）；
 * - “本集已出现过”只管“每集只显示一次”，不触发隐藏；
 * - 已可见且超时 10 秒才隐藏，且超时判断在“只出现一次”判断之前；
 * - 是否出现只看片头窗口，与自动跳开关解耦（关掉自动跳的用户靠胶囊手动跳）。
 * 纯逻辑类，时钟可注入，可单测（模拟两轮轮询）。
 */
class SkipIntroCapsule(private val nowMs: () -> Long = System::currentTimeMillis) {

    /** 本集是否已出现过（每集只出现一次，隐藏后不再出现）。 */
    var shownThisEpisode: Boolean = false
        private set

    /** 当前是否可见。 */
    var visible: Boolean = false
        private set

    private var shownAtMs: Long = 0L

    /** 换集时重置（由 PlayViewModel.startEpisode 调用）。 */
    fun resetForEpisode() {
        shownThisEpisode = false
        visible = false
        shownAtMs = 0L
    }

    /**
     * 每轮询调用一次，返回胶囊是否应可见。
     * @param inWindow 当前位置是否在片头窗口内（introEndMs > 0 且 position < introEndMs）
     * @param alreadySkipped 本集是否已跳过片头（自动跳或手动跳过）
     */
    fun update(inWindow: Boolean, alreadySkipped: Boolean): Boolean {
        // 已跳过片头：胶囊不再出现，当前可见的立即隐藏。
        if (alreadySkipped) {
            visible = false
            return false
        }
        // 已可见且超时：隐藏。须在“只出现一次”判断之前，
        // 否则超时分支像旧实现一样永不可达。
        if (visible && nowMs() - shownAtMs > CAPSULE_VISIBLE_MS) {
            visible = false
            return false
        }
        // P2-4：已可见、但快进（或自然播放）已越过片头窗口时立即隐藏，
        // 不等 10 秒超时：否则胶囊挂在已过片头的画面上，点了会 seek 回片头结束点附近。
        // 只消耗过一次（shownThisEpisode 已置位），隐藏后本集不再出现。
        if (visible && !inWindow) {
            visible = false
            return false
        }
        if (!visible && IntroSkip.shouldShowSkipIntroCapsule(inWindow, shownThisEpisode, alreadySkipped)) {
            shownThisEpisode = true
            shownAtMs = nowMs()
            visible = true
        }
        return visible
    }

    companion object {
        /** 胶囊出现后可停留的最长时间（ui-design.md §5.3：出现 10 秒后消失）。 */
        const val CAPSULE_VISIBLE_MS = 10_000L
    }
}
