package com.oriontv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToastEventTest {

    @Test
    fun `same message twice gets distinct ids`() {
        // B5-P2-11：2 秒内同文案失败两次，id 不同，LaunchedEffect 的 key 才会变。
        val first = nextToastEvent("收藏失败，请检查网络后重试")
        val second = nextToastEvent("收藏失败，请检查网络后重试")
        assertEquals(first.message, second.message)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `ids are strictly increasing`() {
        val events = List(5) { nextToastEvent("x") }
        val ids = events.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.toSet().size, ids.size)
    }
}
