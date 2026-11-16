package com.oriontv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 消费落在该节点上的点按本身（A28-P2-2；A29-P2-1/P2-2 窄化）。
 *
 * 覆盖层（弹窗/面板/抽屉）的空白区点按必须到此为止：下面叠着视频点按层
 * （LiveScreen/PlayScreen 的 detectTapGestures），无消费节点的点按会继续
 * 落到它身上，关错层级或开错面板（EPG 开着时点卡片外会连面板一起关、
 * 退出确认开着时点卡片外会在弹窗背后打开频道面板、面板/抽屉内空白区
 * 点按会把面板/抽屉关掉）。
 *
 * 只消费点按本身需要的变化：down 与 up。移动事件原样放过——
 * LazyColumn/LazyVerticalGrid/LazyRow 的拖动检测与 detectTapGestures 的
 * 长按/点按等待只在 Final pass 查到“移动被消费”时才取消，放过移动即
 * 恢复面板/EPG/抽屉内的触摸滚动、频道行长按收藏与点按。
 *
 * 窄化后仍能拦住视频点按层：down 被消费后，下方 detectTapGestures 的
 * awaitFirstDown(requireUnconsumed = true) 会跳过这个已消费的 down，
 * 整个点按手势不再触发。
 *
 * 子元素先于父节点收到事件（Compose 按命中路径由内向外分发），所以子级
 * 的 clickable/detectTapGestures 先消费自己需要的变化；这里只吞子级没
 * 处理掉的空白区点按的 down/up。只消费指针事件，不建焦点节点，
 * 不动遥控焦点路径。
 *
 * 注意：只加在覆盖层“自身内容”的根上；抽屉外暗区点按关抽屉是既定行为
 * （与遥控返回同款），暗区必须继续漏到视频点按层，所以 PlayDrawerPanel
 * 只给内层 480dp 的 Column 加，不给全屏根 Box 加。
 */
fun Modifier.consumeTaps(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // 默认 Main pass：子级先于父级收到事件（子级 clickable 先消费），
            // 这里只吞子级没处理掉的空白区点按的 down/up；移动事件放过，
            // 否则滚动与长按/点按的 Final pass 核查到移动被消费即取消
            // （A29-P2-1/P2-2）。同层下方的视频点按层随后看到已被消费的
            // down，awaitFirstDown(requireUnconsumed = true) 跳过，不再触发。
            val event = awaitPointerEvent()
            event.changes.forEach {
                // 本仓 Compose 版本无 changedToDown()/changedToUp()，用
                // pressed 与 previousPressed 的翻转等价判定 down/up。
                val isDown = it.pressed && !it.previousPressed
                val isUp = !it.pressed && it.previousPressed
                if (isDown || isUp) it.consume()
            }
        }
    }
}
