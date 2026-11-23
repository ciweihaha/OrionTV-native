package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度输入法电视版样式 T9 九键 + 分词 + 英文 T9（v2.0.21 第 80 轮）：
 * 只测纯逻辑（PinyinInput.kt 新增函数），不碰 UI；拼音引擎与词典本身不动。
 */
class BaiduKeyboardTest {

    @Test
    fun `nineKey digit code 887484826 segments to tu shu guan`() {
        val alts = segmentDigitCodeAlternatives("887484826")
        assertTrue("887484826 应能切出音节", alts.isNotEmpty())
        // 常用词优先：默认切分是 tu-shu-guan（图书馆），不是单字常用度的 tu-qiu-huan。
        assertEquals(listOf("tu", "shu", "guan"), alts.first())
        // 单字常用度切分仍在候选里，分词键可轮换回去。
        assertTrue(alts.contains(listOf("tu", "qiu", "huan")))
    }

    @Test
    fun `common word list entries are all valid syllables`() {
        for (word in T9_COMMON_WORDS) {
            for (syllable in word) {
                assertTrue("常用词音节 $syllable 不合法", isValidSyllable(syllable))
            }
        }
    }

    @Test
    fun `nineKey digit code 887484826 yields 图书馆 by picking each char`() {
        // tu 首选是突（词典字频如此），逐字选 图 -> 书 -> 馆 拼出图书馆。
        val pinyin = segmentDigitCodeAlternatives("887484826").first().joinToString("")
        assertEquals("tushuguan", pinyin)
        val cands0 = displayCandidates(pinyin, emptyList())
        val tuIndex = cands0.indexOf("图")
        assertTrue("tu 候选里应有图", tuIndex >= 0)
        val (_, picked1) = selectCandidateStep(pinyin, emptyList(), tuIndex)
        assertEquals(listOf("图"), picked1)
        val cands1 = displayCandidates(pinyin, picked1)
        val shuIndex = cands1.indexOf("书")
        assertTrue("shu 候选里应有书", shuIndex >= 0)
        val (_, picked2) = selectCandidateStep(pinyin, picked1, shuIndex)
        assertEquals(listOf("图", "书"), picked2)
        val cands2 = displayCandidates(pinyin, picked2)
        val guanIndex = cands2.indexOf("馆")
        assertTrue("guan 候选里应有馆", guanIndex >= 0)
        val (text, picked3) = selectCandidateStep(pinyin, picked2, guanIndex)
        assertEquals("图书馆", text)
        assertTrue(picked3.isEmpty())
    }

    @Test
    fun `nineKey English 2288 gives CCTV`() {
        assertEquals(listOf("CCTV"), t9EnglishCandidates("2288"))
    }

    @Test
    fun `t9 English unknown code gives empty`() {
        assertTrue(t9EnglishCandidates("9999").isEmpty())
        assertTrue(t9EnglishCandidates("").isEmpty())
        // 0/1 不进 T9 码。
        assertTrue(t9EnglishCandidates("2280").isEmpty())
    }

    @Test
    fun `letter segmentation alternatives for xian include xian and xi-an greedy first`() {
        val alts = segmentPinyinAlternatives("xian")
        assertTrue(alts.isNotEmpty())
        assertEquals(listOf("xian"), alts.first())
        assertTrue("xian 应有 xi+an 切法", alts.contains(listOf("xi", "an")))
    }

    @Test
    fun `segmented xian as xi-an shows 西安 first not xian 现`() {
        // 第 81 轮 A7 回归：分词把 xian 切成 xi+an 后，候选栏首选必须是“西安”，
        // 不能悄悄贪心重切回 xian 的“现”——旧实现只传拼音串，分词按了等于没按。
        assertEquals("现", displayCandidates("xian", emptyList())[0])
        val segmented = displayCandidatesForSegments(listOf("xi", "an"), emptyList())
        assertEquals("西安", segmented[0])
        assertTrue("xi+an 候选里应有西", segmented.contains("西"))
        // 点首选整词位直接上屏“西安”。
        val (text, picked) = selectCandidateStepForSegments(listOf("xi", "an"), emptyList(), 0)
        assertEquals("西安", text)
        assertTrue(picked.isEmpty())
        // 空格/搜索同样按 xi+an 拼词。
        assertEquals("西安", commitPickedPhraseForSegments(listOf("xi", "an"), emptyList()))
        // 逐字选：西 -> 安，选完上屏“西安”。
        val xiIndex = segmented.indexOf("西")
        assertTrue(xiIndex >= 0)
        val (_, picked1) = selectCandidateStepForSegments(listOf("xi", "an"), emptyList(), xiIndex)
        assertEquals(listOf("西"), picked1)
        val cands1 = displayCandidatesForSegments(listOf("xi", "an"), picked1)
        val anIndex = cands1.indexOf("安")
        assertTrue(anIndex >= 0)
        val (text2, picked2) = selectCandidateStepForSegments(listOf("xi", "an"), picked1, anIndex)
        assertEquals("西安", text2)
        assertTrue(picked2.isEmpty())
    }

    @Test
    fun `digit segmentation alternatives for 626 include man mao nao`() {
        val alts = segmentDigitCodeAlternatives("626")
        assertTrue("626 应有多种切分", alts.size >= 3)
        assertTrue(alts.contains(listOf("man")))
        assertTrue(alts.contains(listOf("mao")))
        assertTrue(alts.contains(listOf("nao")))
    }

    @Test
    fun `digit code with 0 or 1 has no segmentation`() {
        assertTrue(segmentDigitCodeAlternatives("80").isEmpty())
        assertTrue(segmentDigitCodeAlternatives("21").isEmpty())
        assertTrue(segmentDigitCodeAlternatives("").isEmpty())
    }

    @Test
    fun `nineKey digit groups are the standard T9 mapping`() {
        // 任务要求核对：2=ABC、3=DEF、4=GHI、5=JKL、6=MNO、7=PQRS、8=TUV、9=WXYZ。
        assertEquals(
            mapOf(
                "1" to ".,?!",
                "2" to "abc",
                "3" to "def",
                "4" to "ghi",
                "5" to "jkl",
                "6" to "mno",
                "7" to "pqrs",
                "8" to "tuv",
                "9" to "wxyz",
            ),
            NineKeyInput.KEY_GROUPS,
        )
    }
}
