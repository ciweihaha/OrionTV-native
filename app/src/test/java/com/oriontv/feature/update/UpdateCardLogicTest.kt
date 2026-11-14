package com.oriontv.feature.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCardLogicTest {

    @Test
    fun `focus trap target follows card state`() {
        // C7-P2-1：禁用态的主按钮不能当陷阱落点——下载中指“取消下载”，
        // 超限指“跳过此版”，否则指主按钮。
        assertEquals(UpdateCardTrapTarget.Primary, updateCardTrapTarget(false, false))
        assertEquals(UpdateCardTrapTarget.Cancel, updateCardTrapTarget(true, false))
        assertEquals(UpdateCardTrapTarget.Skip, updateCardTrapTarget(false, true))
        // 下载中且超限：取消下载仍在，优先指它。
        assertEquals(UpdateCardTrapTarget.Cancel, updateCardTrapTarget(true, true))
    }
}
