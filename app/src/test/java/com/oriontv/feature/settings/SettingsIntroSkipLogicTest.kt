package com.oriontv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 片头时长循环切换档位（B19-P2-2：纯函数部分）。 */
class SettingsIntroSkipLogicTest {

    @Test
    fun `cycle advances one step per press`() {
        // 连按两次各前进一档的档位计算：0→30→60，待定值保证第二次
        // 读到的是第一次算出的 30 而不是流里还没更新的旧值 0。
        assertEquals(30, nextIntroSkipSeconds(0))
        assertEquals(60, nextIntroSkipSeconds(30))
        assertEquals(90, nextIntroSkipSeconds(60))
        assertEquals(120, nextIntroSkipSeconds(90))
    }

    @Test
    fun `cycle wraps from last step to zero`() {
        assertEquals(0, nextIntroSkipSeconds(120))
    }

    @Test
    fun `unknown value falls back to first step`() {
        assertEquals(0, nextIntroSkipSeconds(45))
    }
}
