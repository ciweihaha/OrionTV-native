package com.oriontv.core.focus

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun `focus retry rechecks validity before every attempt`() {
        // Jewel 第 9 轮 P2-1：谓词在每次重试前都复核——全局弹窗在重试窗口内
        // 打开时重试即停，不把焦点偷回弹窗背后的按钮。谓词恒假时一次也不落焦。
        val requester = FocusRequester()
        var checks = 0
        val neverValid = runBlocking {
            requester.requestFocusWithRetry(attempts = 5, delayMs = 1L, isStillValid = {
                checks++
                false
            })
        }
        assertFalse(neverValid)
        assertEquals(1, checks)

        // 谓词第一次真、第二次假：只试了一次就停（第二次复核即返回）。
        checks = 0
        val flipped = runBlocking {
            requester.requestFocusWithRetry(attempts = 5, delayMs = 1L, isStillValid = {
                checks++
                checks == 1
            })
        }
        assertFalse(flipped)
        assertEquals(2, checks)
    }

    @Test
    fun `focus retry with always valid predicate exhausts attempts`() {
        // 谓词恒真时行为与旧版一致：每次尝试前都复核、耗尽才返回 false。
        val requester = FocusRequester()
        var checks = 0
        val result = runBlocking {
            requester.requestFocusWithRetry(attempts = 3, delayMs = 1L, isStillValid = {
                checks++
                true
            })
        }
        assertFalse(result)
        assertEquals(3, checks)
    }
}
