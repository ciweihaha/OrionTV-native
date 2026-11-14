package com.oriontv.feature.live

import kotlinx.serialization.Serializable

/**
 * 直播收藏频道（plan §5.4：`mytv_live_fav_channels`，按 url 判重，新的插头部，上限 100 条）。
 */
@Serializable
data class LiveFavChannel(
    val url: String,
    val name: String,
)

/** 上次频道（plan §5.4：`mytv_live_last_channel`，`{url, name}`，无 TTL）。 */
@Serializable
data class LiveLastChannel(
    val url: String,
    val name: String,
)

/** 断流后的自动处理结果（plan §5.3/§4.5：先试同台备用线路，全部失败再跳下一台，>5 次停手）。 */
sealed interface LiveErrorAction {
    /** 试同台的另一条线路。 */
    data class TryLine(val lineIndex: Int, val message: String) : LiveErrorAction

    /** 跳下一台（自动，不清零失败计数）。 */
    data class NextChannel(val message: String) : LiveErrorAction

    /** 连续失败超上限，停手并提示手动换台。 */
    data class GiveUp(val message: String) : LiveErrorAction

    /** 面板打开时不自动跳台，只提示。 */
    data class NotifyOnly(val message: String) : LiveErrorAction
}

/** 数字键换台的结果。 */
sealed interface DigitAction {
    /** 仍在等更多数字（显示缓冲）。 */
    data class Buffer(val text: String) : DigitAction

    /** 切到该下标的频道。 */
    data class SwitchTo(val index: Int) : DigitAction

    /** 缓冲提交时发现无此号频道。 */
    data class Invalid(val number: Int) : DigitAction

    /** 无动作（缓冲为空）。 */
    data object None : DigitAction
}

/**
 * 直播换台/切线路/断流/数字键/收藏的纯逻辑（无 Android 依赖，可单测）。
 *
 * 对照 RN `app/live.tsx`：
 * - 上/下循环换台、左/右切同台线路（单线路频道无动作）；
 * - 换台回到主线路（lineIndex=0），手动换台清零失败计数，自动跳台不清零；
 * - 断流：失败计数 >5 停手；面板打开时只提示不跳台；先试未试过的备用线路，全部试完才跳台；
 * - 播放成功清零失败计数与已试线路集合；
 * - 数字键：最多 4 位，1500ms 缓冲提交；唯一匹配（不存在以它为前缀的更大号）立即切换；
 * - 收藏：按 url 判重，新的插头部，上限 100 条。
 */
class LiveController(
    channels: List<M3uParser.Channel> = emptyList(),
    favorites: List<LiveFavChannel> = emptyList(),
    currentIndex: Int = 0,
    lineIndex: Int = 0,
) {
    var channels: List<M3uParser.Channel> = channels
        private set

    var currentIndex: Int = currentIndex
        private set

    var lineIndex: Int = lineIndex
        private set

    var favorites: List<LiveFavChannel> = favorites
        private set

    /** 连续失败计数（>5 停手）。 */
    var failCount: Int = 0
        private set

    /** 本轮自动备用已试过的线路下标。 */
    private val triedLines = mutableSetOf<Int>()

    /** 数字键缓冲（最多 4 位）。 */
    var digitBuffer: String = ""
        private set

    val currentChannel: M3uParser.Channel? get() = channels.getOrNull(currentIndex)
    val currentUrl: String? get() = currentChannel?.urls?.getOrNull(lineIndex)

    /** 台号（1-based，数字键与面板显示用）。 */
    fun channelNumber(index: Int): Int = index + 1

    /** 装入频道列表；按上次频道 URL 恢复（只恢复一次，由调用方保证只调一次）。 */
    fun setChannels(newChannels: List<M3uParser.Channel>, restoreUrl: String?) {
        channels = newChannels
        val restored = restoreUrl?.let { url -> newChannels.indexOfFirst { it.url == url } }
            .takeIf { it != null && it >= 0 }
        currentIndex = restored ?: 0
        lineIndex = 0
        failCount = 0
        triedLines.clear()
        digitBuffer = ""
    }

    fun setFavorites(newFavorites: List<LiveFavChannel>) {
        favorites = newFavorites
    }

    /** 选台：换台回到主线路；手动换台清零失败计数，自动跳台不清零。 */
    fun selectChannel(index: Int, isAuto: Boolean = false): Boolean {
        if (index !in channels.indices) return false
        if (index != currentIndex) {
            lineIndex = 0
            triedLines.clear()
        }
        currentIndex = index
        if (!isAuto) failCount = 0
        return true
    }

    /** 上一台（循环）。 */
    fun prevChannel(isAuto: Boolean = false): Boolean {
        if (channels.isEmpty()) return false
        val next = (currentIndex - 1 + channels.size) % channels.size
        return selectChannel(next, isAuto)
    }

    /** 下一台（循环）。 */
    fun nextChannel(isAuto: Boolean = false): Boolean {
        if (channels.isEmpty()) return false
        val next = (currentIndex + 1) % channels.size
        return selectChannel(next, isAuto)
    }

    /** 切线路：单线路频道无动作；手动切线路清零失败计数与已试集合。 */
    fun changeLine(delta: Int): Boolean {
        val channel = currentChannel ?: return false
        if (channel.urls.size <= 1) return false
        lineIndex = (lineIndex + delta + channel.urls.size) % channel.urls.size
        failCount = 0
        triedLines.clear()
        return true
    }

    /** 播放成功：清零失败计数与已试线路集合。 */
    fun onPlaybackSuccess() {
        failCount = 0
        triedLines.clear()
    }

    /** 手动重试当前台（失败计数不清零上限内才有意义，由调用方判断）。 */
    fun resetFailures() {
        failCount = 0
        triedLines.clear()
    }

    /**
     * 断流处理（RN `handlePlaybackError` 同款）：
     * 失败计数 +1；>5 停手；面板打开时只提示；否则先试未试过的备用线路，全部试完跳下一台。
     */
    fun onPlaybackError(panelOpen: Boolean): LiveErrorAction {
        if (channels.isEmpty()) return LiveErrorAction.GiveUp("暂无频道")
        failCount++
        if (failCount > MAX_AUTO_FAIL) {
            return LiveErrorAction.GiveUp("连续切换失败，请按上下键手动换台")
        }
        if (panelOpen) {
            return LiveErrorAction.NotifyOnly("信号异常，请重新选择频道")
        }
        val channel = currentChannel
        val urlCount = channel?.urls?.size ?: 0
        if (urlCount > 1) {
            triedLines.add(lineIndex)
            for (i in 1 until urlCount) {
                val nextIdx = (lineIndex + i) % urlCount
                if (nextIdx !in triedLines) {
                    lineIndex = nextIdx
                    return LiveErrorAction.TryLine(
                        nextIdx,
                        "信号异常，正在尝试备用线路 ${nextIdx + 1}/$urlCount…",
                    )
                }
            }
            // 全部线路都试过，往下跳台
        }
        val beforeIndex = currentIndex
        nextChannel(isAuto = true)
        if (currentIndex == beforeIndex) {
            // 单频道列表自动跳台回到同一台：也要清空已试线路并回主线路，
            // 否则之后每次失败直接跳台，不再重试主线路。
            lineIndex = 0
            triedLines.clear()
        }
        return LiveErrorAction.NextChannel("信号异常，自动切换下一频道…")
    }

    // ---- 数字键换台（RN V1.5 同款） ----

    /** 按一位数字；唯一匹配立即切换，否则只更新缓冲（调用方起 1500ms 提交定时器）。 */
    fun appendDigit(digit: Char): DigitAction {
        if (!digit.isDigit()) return DigitAction.None
        val next = (digitBuffer + digit).takeLast(4)
        digitBuffer = next
        val n = next.toIntOrNull() ?: return DigitAction.Buffer(next)
        // 以 n 为前缀的最小更大号是 n*10（如 5 → 50）：列表长度达到它才说明可能存在更大号。
        val hasLonger = channels.size >= n * 10
        if (n in 1..channels.size && !hasLonger) {
            digitBuffer = ""
            return DigitAction.SwitchTo(n - 1)
        }
        return DigitAction.Buffer(next)
    }

    /** 1500ms 缓冲提交：有此号切台，无此号提示。 */
    fun commitDigits(): DigitAction {
        val text = digitBuffer
        digitBuffer = ""
        val n = text.toIntOrNull() ?: return DigitAction.None
        return if (n in 1..channels.size) {
            DigitAction.SwitchTo(n - 1)
        } else {
            DigitAction.Invalid(n)
        }
    }

    fun clearDigitBuffer() {
        digitBuffer = ""
    }

    // ---- 分组与收藏 ----

    /** 分组 chip 顺序：★ 收藏在最前，其次全部，再按首次出现顺序排 group-title。 */
    fun groups(): List<String> {
        val distinct = channels.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }.distinct()
        return listOf(FAV_GROUP, ALL_GROUP) + distinct
    }

    /** 某分组下的频道（收藏分组按 url 判重过滤）。 */
    fun channelsForGroup(group: String): List<M3uParser.Channel> = when (group) {
        FAV_GROUP -> {
            val favUrls = favorites.map { it.url }.toSet()
            channels.filter { it.url in favUrls }
        }
        ALL_GROUP -> channels
        else -> channels.filter { it.group == group }
    }

    /** 收藏/取消收藏当前频道；返回 true=已收藏，false=已取消。新的插头部，上限 100 条。 */
    fun toggleFavorite(channel: M3uParser.Channel): Boolean {
        return if (favorites.any { it.url == channel.url }) {
            favorites = favorites.filter { it.url != channel.url }
            false
        } else {
            favorites = (listOf(LiveFavChannel(channel.url, channel.name)) + favorites)
                .take(MAX_FAV_CHANNELS)
            true
        }
    }

    fun isFavorite(channel: M3uParser.Channel): Boolean =
        favorites.any { it.url == channel.url }

    companion object {
        /** 断流自动处理上限：连续失败 >5 次停手（plan §5.3）。 */
        const val MAX_AUTO_FAIL = 5

        /** 数字键缓冲提交 1500ms（RN DIGIT_COMMIT_MS）。 */
        const val DIGIT_COMMIT_MS = 1500L

        /** 频道面板 15 秒无操作自动关闭（RN PANEL_AUTO_CLOSE_MS）。 */
        const val PANEL_AUTO_CLOSE_MS = 15_000L

        /** 换台信息条 3 秒后消失（ui-design.md §5.4）。 */
        const val INFO_BAR_MS = 3000L

        /** 提示消息条 3 秒后消失（RN MESSAGE_MS）。 */
        const val MESSAGE_MS = 3000L

        /** M3U 缓存 TTL 7 天（plan §5.4）。 */
        const val M3U_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** 收藏频道上限 100 条（plan §5.4）。 */
        const val MAX_FAV_CHANNELS = 100

        const val FAV_GROUP = "★ 收藏"
        const val ALL_GROUP = "全部"

        /** 节目进度 0..1（EPG 面板/信息条的进度条用）。 */
        fun programmeProgress(programme: EpgParser.Programme, nowMs: Long): Float {
            val duration = (programme.endAt - programme.startAt).coerceAtLeast(1L)
            return ((nowMs - programme.startAt).toFloat() / duration).coerceIn(0f, 1f)
        }
    }
}
