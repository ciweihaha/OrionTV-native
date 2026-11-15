package com.oriontv.feature.auth

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginModalLogicTest {

    @Test
    fun `login field consumes left and right key up`() {
        // C10-P2-1：登录弹窗输入框左右键只移光标，焦点不出弹窗——光标已在
        // 边界时也消费（不像设置页那样放行回菜单）；上下键走 focusProperties
        // 的上/下循环，不消费
        assertTrue(shouldConsumeLoginFieldArrowKeyUp(Key.DirectionLeft))
        assertTrue(shouldConsumeLoginFieldArrowKeyUp(Key.DirectionRight))
        assertFalse(shouldConsumeLoginFieldArrowKeyUp(Key.DirectionUp))
        assertFalse(shouldConsumeLoginFieldArrowKeyUp(Key.DirectionDown))
        assertFalse(shouldConsumeLoginFieldArrowKeyUp(Key.DirectionCenter))
    }
}
