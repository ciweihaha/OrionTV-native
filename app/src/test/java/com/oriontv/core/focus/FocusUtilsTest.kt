package com.oriontv.core.focus

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class FocusUtilsTest {

    @Test
    fun `focus retry exhausts attempts and reports failure on detached requester`() {
        // 第 7 轮 P2-4/P2-8：抽屉初始落焦与错误消失后回控制条首选按钮都改用
        // requestFocusWithRetry——目标尚未组合完成时单次 requestFocus() 静默
        // 失败；重试耗尽后返回 false（调用方据此回退到根），不抛、不挂起。
        // 未挂到任何可聚焦节点上的 requester 永远落不上焦点，是确定的失败路径。
        val requester = FocusRequester()
        val result = runBlocking {
            requester.requestFocusWithRetry(attempts = 3, delayMs = 1L)
        }
        assertFalse(result)
    }

    @Test
    fun `focus retry with one attempt fails fast on detached requester`() {
        // attempts=1 时只试一次即返回，不多等。
        val requester = FocusRequester()
        val result = runBlocking {
            requester.requestFocusWithRetry(attempts = 1, delayMs = 1L)
        }
        assertFalse(result)
    }
}
