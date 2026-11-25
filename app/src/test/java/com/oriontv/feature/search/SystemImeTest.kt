package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** 系统输入法引导判定（2026-11-25 输入法改版）：只测纯逻辑，不碰 Android 框架。 */
class SystemImeTest {

    @Test
    fun `no enabled ime asks to install from dangbei market`() {
        assertEquals(ImeGuidance.NO_IME, decideImeGuidance(emptyList(), null))
    }

    @Test
    fun `baidu enabled and default needs no guidance`() {
        assertEquals(
            ImeGuidance.NONE,
            decideImeGuidance(
                listOf("com.baidu.input/.ImeService", "com.android.inputmethod.latin/.LatinIME"),
                "com.baidu.input/.ImeService",
            ),
        )
    }

    @Test
    fun `baidu enabled but not default asks to switch`() {
        assertEquals(
            ImeGuidance.BAIDU_NOT_DEFAULT,
            decideImeGuidance(
                listOf("com.baidu.input/.ImeService", "com.android.inputmethod.latin/.LatinIME"),
                "com.android.inputmethod.latin/.LatinIME",
            ),
        )
    }

    @Test
    fun `other ime default and no baidu needs no guidance`() {
        assertEquals(
            ImeGuidance.NONE,
            decideImeGuidance(
                listOf("com.huawei.inputmethod/.HwImeService"),
                "com.huawei.inputmethod/.HwImeService",
            ),
        )
    }

    @Test
    fun `baidu id match is case insensitive`() {
        assertEquals(
            ImeGuidance.BAIDU_NOT_DEFAULT,
            decideImeGuidance(
                listOf("com.BAIDU.input/.ImeService"),
                "com.android.inputmethod.latin/.LatinIME",
            ),
        )
    }
}
