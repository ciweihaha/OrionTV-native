package com.oriontv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 消费落在该节点上的全部指针事件（A28-P2-2）。
 *
 * 覆盖层（弹窗/面板/抽屉）的空白区点按必须到此为止：下面叠着视频点按层
 * （LiveScreen/PlayScreen 的 detectTapGestures），无消费节点的点按会继续
 * 落到它身上，关错层级或开错面板（EPG 开着时点卡片外会连面板一起关、
 * 退出确认开着时点卡片外会在弹窗背后打开频道面板、面板/抽屉内空白区
 * 点按会把面板/抽屉关掉）。
 *
 * 子元素先于父节点收到事件（Compose 按命中路径由内向外分发），所以子级
 * 的 clickable/detectTapGestures 不受影响——只有没被子级处理掉的空白区
 * 点按才在这里被吞掉。只消费指针事件，不建焦点节点，不动遥控焦点路径。
 *
 * 注意：只加在覆盖层“自身内容”的根上；抽屉外暗区点按关抽屉是既定行为
 * （与遥控返回同款），暗区必须继续漏到视频点按层，所以 PlayDrawerPanel
 * 只给内层 480dp 的 Column 加，不给全屏根 Box 加。
 */
fun Modifier.consumeTaps(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // 默认 Main pass：子级先于父级收到事件（子级 clickable 先消费），
            // 这里只吞子级没处理掉的空白区点按；同层下方的视频点按层随后
            // 看到已被消费的事件，不再触发。
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
