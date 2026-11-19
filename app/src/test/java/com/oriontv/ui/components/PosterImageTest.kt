package com.oriontv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterImageTest {

    @Test
    fun `douban poster retries original then switches img host`() {
        // v2.0.18：原地址先重试一次，再换 img 机子（同图在 img1/img2/img9 上都有）。
        val url = "https://img3.doubanio.com/view/photo/s_ratio_poster/public/p2935903589.jpg"
        assertEquals(
            listOf(
                url,
                url,
                "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p2935903589.jpg",
                "https://img2.doubanio.com/view/photo/s_ratio_poster/public/p2935903589.jpg",
            ),
            posterLoadAttempts(url),
        )
    }

    @Test
    fun `douban poster on img1 skips itself in alternates`() {
        val url = "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"
        val attempts = posterLoadAttempts(url)
        assertEquals(url, attempts[0])
        assertEquals(url, attempts[1])
        // 换的机子不含 img1 本身、不重复。
        assertEquals(2, attempts.drop(2).size)
        assertTrue(attempts.drop(2).none { it.contains("img1.doubanio.com") })
        assertEquals(attempts.drop(2).distinct().size, attempts.drop(2).size)
    }

    @Test
    fun `non-douban poster only retries original once`() {
        val url = "https://img.lzipic.com/upload/vod/20220929-1/x.jpg"
        assertEquals(listOf(url, url), posterLoadAttempts(url))
    }

    @Test
    fun `blank poster url has no attempts`() {
        assertEquals(emptyList<String>(), posterLoadAttempts(""))
        assertEquals(emptyList<String>(), posterLoadAttempts("   "))
    }
}
