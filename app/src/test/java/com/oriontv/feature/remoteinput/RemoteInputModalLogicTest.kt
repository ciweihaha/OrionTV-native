package com.oriontv.feature.remoteinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫码弹窗焦点门控的纯决策测试（Jewel 第 9 轮 P2-5 + C12-P2-3）。 */
class RemoteInputModalLogicTest {

    @Test
    fun `mount focuses main action only when no global modal is open`() {
        // Jewel 第 9 轮 P2-5：挂载抢焦补门控——全局弹窗开着时跳过本次抢焦，
        // 关闭后 effect 因 key 变化重跑再落焦。
        assertTrue(shouldFocusRemoteInputModalOnMount(globalModalOpen = false))
        assertFalse(shouldFocusRemoteInputModalOnMount(globalModalOpen = true))
    }

    @Test
    fun `close button refocus only when enable button disappears without modal`() {
        // C12-P2-3：回落“关闭”只在“一键开启”消失且全局弹窗未开时。
        assertTrue(shouldFocusModalCloseOnEnableChange(showEnableButton = false, globalModalOpen = false))
        assertFalse(shouldFocusModalCloseOnEnableChange(showEnableButton = true, globalModalOpen = false))
        assertFalse(shouldFocusModalCloseOnEnableChange(showEnableButton = false, globalModalOpen = true))
    }
}
