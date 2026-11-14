package com.oriontv.core.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 焦点工具，照抄 plan §3.3 的焦点规则。
 *
 * 1. 松键去重：RN 只响应松键（eventKeyAction===1），因为原生层 DOWN/UP 各发一次；
 *    Compose 的 onKeyEvent 同样只处理 [KeyEventType.KeyUp]，并在真机上核实是否双发。
 * 2. 首选焦点锚点：每个弹窗/页面打开时 requestFocus 到“当前项”，不是第 0 项。
 * 3. 返回后显式恢复位置：NavHost pop 后保存 index + scrollToItem + requestFocus。
 * 4. 长按后松手会跟一次 onClick，需用 1 秒时间窗挡掉（见 [LongPressGuard]）。
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
 * 长按挡误触：长按后松手会跟一次 onClick，用 1 秒时间窗挡掉
 *（plan §3.3 第 8 条：首页卡片长按 1000ms、直播卡片长按收藏、播放长按快进 300ms/10s）。
 */
class LongPressGuard(private val windowMs: Long = 1000L) {
    private var lastLongPressAtMs: Long = -1L

    fun markLongPress(nowMs: Long) {
        lastLongPressAtMs = nowMs
    }

    /** 长按后 1 秒内的点击应被挡掉。 */
    fun shouldSuppressClick(nowMs: Long): Boolean =
        lastLongPressAtMs >= 0 && nowMs - lastLongPressAtMs < windowMs
}
