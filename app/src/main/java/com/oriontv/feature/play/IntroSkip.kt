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

    /**
     * 学习式片头（第 12 轮后补）：agg 接口不给片头时间、HLS 流里也没有章节
     * 信息，真正的片头只能从用户的行为里学——用户在片头窗口内手动往前跳，
     * 落点大概率就是这部剧的片头结束点。学一次记到本片的播放器设置里（与
     * 手动标记同一把键、同一个 introEndTime 字段），下集起自动按学到的点跳。
     *
     * 只认“从片头内起跳、越过当前认定结束点”的大跳跃：
     * - 已有手动标记的不覆盖（显式标记优先）；
     * - 起点已在认定结束点之外不学——那是正片中途的场景快进，学了会把下集
     *   的正片开头也跳掉；想改已认定的点请用手动标记（markIntroEnd）；
     * - 落点等于认定结束点不学——自动跳和胶囊点的就是这个点，不是用户行为；
     * - 跳跃太小（<30 秒）不学，过滤误触的单次 +10 秒；
     * - 落点超过 10 分钟不学，那不是片头，是跳到正片中间去了；
     * - 片长已知时，落点距片尾不到 10 秒不学（与 shouldAutoSkip 同款守卫）。
     * @param durationMs 片长（毫秒）；<=0 表示未知，不做片尾守卫。
     */
    fun shouldLearnIntroEnd(
        fromMs: Long,
        toMs: Long,
        introEndMs: Long,
        hasMark: Boolean,
        durationMs: Long = 0L,
    ): Boolean {
        if (hasMark) return false
        if (introEndMs <= 0) return false
        if (fromMs < 0 || fromMs >= introEndMs) return false
        if (toMs <= introEndMs) return false
        if (toMs - fromMs < MIN_LEARN_JUMP_MS) return false
        if (toMs > MAX_LEARNABLE_INTRO_MS) return false
        if (durationMs > 0 && toMs >= durationMs - INTRO_SKIP_MIN_REMAINING_MS) return false
        return true
    }

    /** 学习式片头：一次手动跳跃至少这么大才算“跳片头”，过滤误触的单次 +10 秒。 */
    const val MIN_LEARN_JUMP_MS = 30_000L

    /** 学习式片头：落点超过这个值就不是片头了（用户跳到正片中间去了）。 */
    const val MAX_LEARNABLE_INTRO_MS = 600_000L

    /**
     * 是否该自动跳过片头（可单测）：开关开、片头结束点 >0、当前位置仍在片头内、
     * 本集还没跳过。断点续播位置已过片头（position >= introEnd）的不触发；
     * 有标记用标记，无标记用设置的片头时长，0=不跳（introEndMs==0 即不触发）。
     * 第 6 轮 P1-2 片长守卫：片头结束点距片尾不到余量（或已越过片尾）时不自动跳——
     * 默认 90 秒兜底会把 60 秒的短片开播即送到片尾前 1 秒；手动跳（skipIntro）
     * 是用户当场点的意图，不走这条守卫。
     * @param durationMs 片长（毫秒）；<=0 表示未知，不做守卫（保持旧行为）。
     */
    fun shouldAutoSkip(
        positionMs: Long,
        introEndMs: Long,
        autoSkipEnabled: Boolean,
        alreadySkipped: Boolean,
        durationMs: Long = 0L,
    ): Boolean {
        if (!autoSkipEnabled || introEndMs <= 0 || alreadySkipped) return false
        if (positionMs >= introEndMs) return false
        // 片长守卫：跳完剩不到 10 秒就没得看了，不自动跳。
        if (durationMs > 0 && introEndMs >= durationMs - INTRO_SKIP_MIN_REMAINING_MS) return false
        return true
    }

    /**
     * 自动跳片头后至少要剩的正片时长（第 6 轮 P1-2）：跳点距片尾不到它时
     * 不自动跳；与“临近片尾 10 秒视为看完”（PlayViewModel.END_THRESHOLD_MS）
     * 取同一量级。
     */
    const val INTRO_SKIP_MIN_REMAINING_MS = 10_000L

    /**
     * 跳过片头胶囊是否该出现（可单测）：在片头窗口内、本集没出现过、还没跳过。
     * 每集只出现一次，隐藏后下一轮询不再出现。
     */
    fun shouldShowSkipIntroCapsule(
        inWindow: Boolean,
        shownThisEpisode: Boolean,
        alreadySkipped: Boolean,
    ): Boolean = inWindow && !shownThisEpisode && !alreadySkipped

    /** 是否到了片尾（已标记片尾开始时间且播到该位置）。 */
    fun isAtOutro(positionMs: Long, durationMs: Long, outroStartMs: Long?): Boolean {
        if (outroStartMs == null || durationMs <= 0) return false
        return positionMs >= outroStartMs
    }
}
