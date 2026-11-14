package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class BodyDecodeTest {

    private val gbk: Charset = Charset.forName("GBK")

    @Test
    fun `utf8 text decodes as utf8`() {
        val text = "#EXTM3U\n#EXTINF:-1,CCTV-1 高清\nhttp://a/1.m3u8\n"
        assertEquals(text, decodeBodyBytes(text.toByteArray(Charsets.UTF_8), "text/plain"))
    }

    @Test
    fun `gbk bytes fall back to gbk instead of mojibake`() {
        // A7-P2-3：GBK 源按 UTF-8 硬解只会出乱码（String(bytes, UTF_8) 永不抛错）；
        // 严格 UTF-8 解码失败时回退 GBK，台名不再乱码。
        val text = "#EXTM3U\n#EXTINF:-1,中央电视台\nhttp://a/1.m3u8\n"
        val decoded = decodeBodyBytes(text.toByteArray(gbk), "text/plain")
        assertEquals(text, decoded)
    }

    @Test
    fun `content-type charset wins`() {
        val text = "#EXTINF:-1,广东卫视\n"
        assertEquals(
            text,
            decodeBodyBytes(text.toByteArray(gbk), "text/plain; charset=GBK"),
        )
    }

    @Test
    fun `xml declaration encoding wins for epg`() {
        // EPG 的 StringReader 会忽略 XML 声明里的 encoding，解码层必须先认出来。
        val body = "<?xml version=\"1.0\" encoding=\"GBK\"?><tv><channel id=\"cctv1\">" +
            "<display-name>中央电视台</display-name></channel></tv>"
        assertEquals(body, decodeBodyBytes(body.toByteArray(gbk), "application/xml"))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val text = "#EXTM3U\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        assertEquals(text, decodeBodyBytes(bytes, null))
    }

    @Test
    fun `unknown charset declaration falls through to utf8 or gbk`() {
        val text = "#EXTINF:-1,中央电视台\n"
        assertEquals(text, decodeBodyBytes(text.toByteArray(gbk), "text/plain; charset=BOGUS-XXX"))
    }
}
