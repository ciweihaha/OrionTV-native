package com.oriontv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第 7 轮 P2-2：设置页返回键决策的纯函数测试。
 * BackHandler/Box onKeyUp 本体是 Compose + 按键分发，JVM 单测覆盖不到——
 * “弹窗在上先关弹窗”的决策抽成纯函数钉在这里；三层分工（弹窗自己的
 * handler / 本页与 NavHost 的 handler / Box onKeyUp）由 CI 编译覆盖。
 */
class SettingsBackLogicTest {

    @Test
    fun `no modal exits settings`() {
        assertEquals(SettingsBackAction.ExitSettings, settingsBackAction(showUpdateCard = false, showQrModal = false))
    }

    @Test
    fun `update card open dismisses card instead of exiting`() {
        // 焦点回到内容区而卡片仍开着时，Back 应先关卡片，不直接退出设置页。
        assertEquals(
            SettingsBackAction.DismissUpdateCard,
            settingsBackAction(showUpdateCard = true, showQrModal = false),
        )
    }

    @Test
    fun `qr modal open closes qr modal instead of exiting`() {
        assertEquals(
            SettingsBackAction.CloseQrModal,
            settingsBackAction(showUpdateCard = false, showQrModal = true),
        )
    }

    @Test
    fun `update card wins when both modals are open`() {
        assertEquals(
            SettingsBackAction.DismissUpdateCard,
            settingsBackAction(showUpdateCard = true, showQrModal = true),
        )
    }
}
