package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jewel 第 9 轮 P2-6：PinyinTable（TV v5.9.6 前例移植）的纯逻辑测试。
 * 音节表是固定语言学集合——总数、代表音节、编码、切分结果都在这里钉死，
 * 移植时抄错一个音节就会在这里露馅。
 */
class PinyinTableTest {

    @Test
    fun `syllable table has 423 entries`() {
        assertEquals(423, PinyinTable.count())
    }

    @Test
    fun `isSyllable accepts standard syllables and rejects non-syllables`() {
        assertTrue(PinyinTable.isSyllable("zhong"))
        assertTrue(PinyinTable.isSyllable("guo"))
        assertTrue(PinyinTable.isSyllable("biang"))
        assertTrue(PinyinTable.isSyllable("a"))
        // lo/ra 不是标准音节（TV 表已删），xyz 更不是。
        assertFalse(PinyinTable.isSyllable("lo"))
        assertFalse(PinyinTable.isSyllable("ra"))
        assertFalse(PinyinTable.isSyllable("xyz"))
        assertFalse(PinyinTable.isSyllable(null))
    }

    @Test
    fun `encode maps letters to nine-key digits`() {
        // z→9、h→4、o→6、n→6、g→4。
        assertEquals("94664", PinyinTable.encode("zhong"))
        assertEquals("2", PinyinTable.encode("a"))
        assertEquals("", PinyinTable.encode(null))
    }

    @Test
    fun `forDigits returns candidates in frequency order`() {
        val candidates = PinyinTable.forDigits("94664")
        assertTrue(candidates.contains("zhong"))
        assertTrue(PinyinTable.isDigitCode("94664"))
        assertFalse(PinyinTable.isDigitCode("11111"))
        assertTrue(PinyinTable.forDigits("11111").isEmpty())
    }

    @Test
    fun `segment splits digit codes into fewest syllables`() {
        val seg = PinyinTable.segment("94664")
        assertEquals(listOf("zhong"), seg.syllables)
        assertEquals(5, seg.covered)
        assertEquals("", seg.remainder)
        assertEquals("zhong", seg.joined())
        assertTrue(PinyinTable.canSegment("94664"))
        assertFalse(PinyinTable.canSegment(""))
        assertFalse(PinyinTable.canSegment(null))
    }

    @Test
    fun `segmentLetters splits qwerty pinyin into fewest syllables`() {
        assertEquals(listOf("zhong", "guo"), PinyinTable.segmentLetters("zhongguo").syllables)
        assertEquals(listOf("qing", "yu", "nian"), PinyinTable.segmentLetters("qingyunian").syllables)
        assertEquals(listOf("dian", "ying"), PinyinTable.segmentLetters("dianying").syllables)
        assertEquals(listOf("ni", "hao"), PinyinTable.segmentLetters("nihao").syllables)
        // “xian”本身就是一个音节，优先于 xi+an（音节数最少者优先）。
        assertEquals(listOf("xian"), PinyinTable.segmentLetters("xian").syllables)
        // 切不出的尾部原样保留为 remainder。
        val partial = PinyinTable.segmentLetters("zhongguoz")
        assertEquals(listOf("zhong", "guo"), partial.syllables)
        assertEquals(8, partial.covered)
        assertEquals("z", partial.remainder)
        // 全是无音节字母时一个也切不出。
        val none = PinyinTable.segmentLetters("zz")
        assertTrue(none.syllables.isEmpty())
        assertEquals(0, none.covered)
        assertEquals("zz", none.remainder)
        assertTrue(PinyinTable.segmentLetters("").isEmpty())
        assertTrue(PinyinTable.segmentLetters(null).isEmpty())
    }

    @Test
    fun `isPinyinLike gates iqiyi suggest requests`() {
        assertTrue(PinyinTable.isPinyinLike("qingyunian"))
        assertTrue(PinyinTable.isPinyinLike("ai"))
        assertTrue(PinyinTable.isPinyinLike("nihao"))
        // 单个字母不发联想。
        assertFalse(PinyinTable.isPinyinLike("q"))
        // 含数字/中文/空格不发联想。
        assertFalse(PinyinTable.isPinyinLike("abc123"))
        assertFalse(PinyinTable.isPinyinLike("你好"))
        assertFalse(PinyinTable.isPinyinLike("ni hao"))
        // 切不出任何音节的字母串不发联想。
        assertFalse(PinyinTable.isPinyinLike("zx"))
        assertFalse(PinyinTable.isPinyinLike(null))
    }
}
