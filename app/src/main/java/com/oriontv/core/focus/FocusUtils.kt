package com.oriontv.core.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

/**
 * 焦点工具，照抄 plan §3.3 的焦点规则。
 *
 * 1. 松键去重：RN 只响应松键（eventKeyAction===1），因为原生层 DOWN/UP 各发一次；
 *    Compose 的 onKeyEvent 同样只处理 [KeyEventType.KeyUp]，并在真机上核实是否双发。
 * 2. 首选焦点锚点：每个弹窗/页面打开时 requestFocus 到“当前项”，不是第 0 项。
 * 3. 返回后显式恢复位置：NavHost pop 后保存 index + scrollToItem + requestFocus。
 * 4. 长按后松手会跟一次 onClick，需用 1 秒时间窗挡掉（plan §3.3 的第 8 条：
 * 首页卡片长按 1000ms、直播卡片长按收藏；各组件内联实现，如直播频道行）。
 */
object FocusUtils {

    /** 只在松键时执行 [onKeyUp]，按下/重复事件直接放过。返回 true 表示已消费。 */
    fun handleKeyUp(event: KeyEvent, onKeyUp: () -> Boolean): Boolean {
        if (event.type != KeyEventType.KeyUp) return false
        return onKeyUp()
    }
}

/** 把 [FocusRequester] 挂到组件上，并对 KeyUp 事件做统一去重处理。 */
fun Modifier.onKeyUp(onKeyUp: (KeyEvent) -> Boolean): Modifier = this.onKeyEvent { event ->
    if (event.type != KeyEventType.KeyUp) false else onKeyUp(event)
}

// 注意：不要再加一个无参的 onKeyUp(() -> Boolean) 重载——两个重载并存时，
// 调用处用隐式 it（{ it.key == ... }）会报 Overload resolution ambiguity。
// 所有调用方都按是哪个键分流，本来就需要 KeyEvent。

/** 首选焦点锚点：挂到“当前项”上，打开时 requestFocus 到它。 */
fun Modifier.preferredFocus(requester: FocusRequester): Modifier =
    this.focusRequester(requester)

/**
 * 可靠的焦点请求（C4-P2-2/B4-P2-23）：目标尚未组合完成时 requestFocus()
 * 静默返回 false——固定延时 120ms 猜时序不可靠；循环重试直到成功或耗尽。
 * 返回是否最终落上焦点（调用方据此决定是否回退到默认锚点）。
 * Jewel 第 9 轮 P2-1：每次重试前复核 [isStillValid]——旧实现重试期间不复核
 * 任何门控，目标未组合 + 全局弹窗恰好在约 400ms 重试窗口内打开时，重试
 * 会把焦点偷回被遮罩盖住的页面按钮（用户盲按会在弹窗背后触发播放/重试）。
 * 各焦点 effect 传入 `!globalModalOpen`（与第 8 轮 P2-8 同一意图），一次
 * 根治全仓同类窗口；不传时默认恒真，行为与旧版一致。
 */
suspend fun FocusRequester.requestFocusWithRetry(
    attempts: Int = 5,
    delayMs: Long = 100L,
    isStillValid: () -> Boolean = { true },
): Boolean {
    repeat(attempts) {
        if (!isStillValid()) return false
        if (requestFocus()) return true
        delay(delayMs)
    }
    return false
}
