package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置拼音输入纯逻辑测试（第 74 轮）：
 * 由 OrionTV-build utils/__tests__/pinyinInput.test.ts 移植（RN v1.7.3–v1.7.4
 * 拼音键盘同款用例），另加 9 键拼音按压（nineKeyPinyinPress）的用例。
 */
class PinyinInputTest {

    @Test
    fun `常用音节首选是常用字`() {
        assertEquals("中", getCandidates("zhong")[0])
        assertEquals("国", getCandidates("guo")[0])
        assertEquals("点", getCandidates("dian")[0])
        assertEquals("一", getCandidates("yi")[0])
        assertEquals("是", getCandidates("shi")[0])
        assertEquals("接", getCandidates("jie")[0])
    }

    @Test
    fun `ü 用 v 表示`() {
        assertEquals("女", getCandidates("nv")[0])
        assertEquals("绿", getCandidates("lv")[0])
    }

    @Test
    fun `生僻读音不混进候选前排`() {
        assertFalse(getCandidates("jie").contains("家"))
        assertFalse(getCandidates("jie").contains("她"))
        assertEquals("家", getCandidates("jia")[0])
        assertTrue(getCandidates("ta").contains("她"))
    }

    @Test
    fun `多音字的次常用读音仍能找到`() {
        assertTrue(getCandidates("xing").contains("行"))
        assertTrue(getCandidates("hang").contains("行"))
        assertTrue(getCandidates("chang").contains("长"))
    }

    @Test
    fun `非法拼音返回空列表`() {
        assertEquals(emptyList<String>(), getCandidates(""))
        assertEquals(emptyList<String>(), getCandidates("zx"))
        assertEquals(emptyList<String>(), getCandidates("zhongguo"))
    }

    @Test
    fun `完整音节与前缀能区分`() {
        assertTrue(isValidSyllable("zhong"))
        assertFalse(isValidSyllable("zh"))
        assertTrue(isSyllablePrefix("zh"))
        assertTrue(isSyllablePrefix("zho"))
        assertFalse(isSyllablePrefix("zhong"))
        assertFalse(isSyllablePrefix("zx"))
    }

    @Test
    fun `合法音节直接收下`() {
        assertEquals(AppendLetterResult("j", null), appendLetter("", 'j'))
        assertEquals(AppendLetterResult("ji", null), appendLetter("j", 'i'))
        assertEquals(AppendLetterResult("jie", null), appendLetter("ji", 'e'))
    }

    @Test
    fun `连续拼词不再自动上屏半路的首选字`() {
        // 第 79 轮改：打 zhong 再打 g，g 开新音节，“中”不再自动上屏，等打完一起选
        assertEquals(AppendLetterResult("zhongg", null), appendLetter("zhong", 'g'))
        assertEquals(AppendLetterResult("air", null), appendLetter("ai", 'r'))
        // 一整串 zhongguo 一路攒下来，全程不提交
        var buffer = ""
        for (ch in "zhongguo") {
            val r = appendLetter(buffer, ch)
            assertNull(r.commit)
            buffer = r.buffer
        }
        assertEquals("zhongguo", buffer)
    }

    @Test
    fun `拼音串贪心切音节`() {
        assertEquals(listOf("zhong", "guo"), segmentPinyin("zhongguo"))
        assertEquals(listOf("xian"), segmentPinyin("xian"))
        assertEquals(listOf("xiao", "cheng", "liang", "fang"), segmentPinyin("xiaochengliangfang"))
        assertEquals(listOf("zhong", "g"), segmentPinyin("zhongg"))
        assertEquals(listOf("zx"), segmentPinyin("zx"))
        assertEquals(emptyList<String>(), segmentPinyin(""))
    }

    @Test
    fun `多音节首选词`() {
        assertEquals("中国", phraseFirstCandidate("zhongguo"))
        // 注意：首选字拼出的不是“小城良方”——cheng 首选是成（城排第 2），
        // liang 首选是两；所以整词预览只是“首选拼法”，用户要靠逐音节选字
        // 才能拼出想要的词（见下一测试）。
        assertEquals("小成两方", phraseFirstCandidate("xiaochengliangfang"))
        assertEquals("中", phraseFirstCandidate("zhong"))
        assertNull(phraseFirstCandidate("zhongg"))
        assertNull(phraseFirstCandidate("zh"))
        assertNull(phraseFirstCandidate(""))
    }

    @Test
    fun `候选行整词在首位其后是当前待选音节的字`() {
        val shown = displayCandidates("zhongguo", emptyList())
        assertEquals("中国", shown[0])
        // 还没给 zhong 选字，当前待选音节是 zhong，第二个是“中”
        assertEquals("中", shown[1])
        assertTrue(shown.contains("重"))
        // 单音节时没有整词位，与旧行为一致
        assertEquals("接", displayCandidates("jie", emptyList())[0])
        assertEquals(emptyList<String>(), displayCandidates("", emptyList()))
        // 末段还没拼完时不给前面音节出字，先等用户打完
        assertEquals(emptyList<String>(), displayCandidates("zhongg", emptyList()))
    }

    @Test
    fun `点选整词与逐音节选字`() {
        // 整词位：直接上屏
        assertEquals("中国" to emptyList<String>(), selectCandidateStep("zhongguo", emptyList(), 0))
        // 字位：给 zhong 选“中”，还没选完，继续选 guo
        assertEquals(null to listOf("中"), selectCandidateStep("zhongguo", emptyList(), 1))
        // 选完 zhong 后整词位仍可点，直接上屏整词
        assertEquals("中国" to emptyList<String>(), selectCandidateStep("zhongguo", listOf("中"), 0))
        // 选完 guo 的“过”，整词拼好上屏
        assertEquals("中过" to emptyList<String>(), selectCandidateStep("zhongguo", listOf("中"), 2))
        // 单音节：点字直接上屏（新词典按字频排：zhong 是中、种、重）
        assertEquals("中" to emptyList<String>(), selectCandidateStep("zhong", emptyList(), 0))
        assertEquals("种" to emptyList<String>(), selectCandidateStep("zhong", emptyList(), 1))
        assertEquals(null to emptyList<String>(), selectCandidateStep("zhongg", emptyList(), 0))
        assertEquals(null to emptyList<String>(), selectCandidateStep("", emptyList(), 0))
    }

    @Test
    fun `逐音节选字拼出小城良方`() {
        // 词典补全后：liang 候选第 4 位是良（两、量、亮、良），逐音节选字能拼出“小城良方”。
        // xiao 选“小”（展示位 1）
        assertEquals(null to listOf("小"), selectCandidateStep("xiaochengliangfang", emptyList(), 1))
        // cheng 选“城”（展示位 2：整词位 0，成 1，城 2）
        assertEquals(
            null to listOf("小", "城"),
            selectCandidateStep("xiaochengliangfang", listOf("小"), 2),
        )
        // liang 选“良”（展示位 4：整词位 0，两 1，量 2，亮 3，良 4），还没选完 fang
        assertEquals(
            null to listOf("小", "城", "良"),
            selectCandidateStep("xiaochengliangfang", listOf("小", "城"), 4),
        )
        // fang 选“方”（展示位 1：整词位 0，方 1），最后一音节选完直接上屏
        assertEquals(
            "小城良方" to emptyList<String>(),
            selectCandidateStep("xiaochengliangfang", listOf("小", "城", "良"), 1),
        )
        // 词典里 liang 候选第 4 位就是良；中途整词预览也跟随已选字变化
        assertEquals(3, getCandidates("liang").indexOf("良"))
        assertEquals("小城两方", phrasePreview("xiaochengliangfang", listOf("小", "城")))
        assertEquals("小城良方", phrasePreview("xiaochengliangfang", listOf("小", "城", "良")))
    }

    @Test
    fun `改拼音字母时保留前面没变的已选字`() {
        assertEquals(listOf("中"), retainPicked("zhongguo", "zhonggu", listOf("中")))
        assertEquals(listOf("中"), retainPicked("zhongguo", "zhongguo", listOf("中")))
        assertEquals(listOf("中", "国"), retainPicked("zhongguo", "zhongguo", listOf("中", "国")))
        // 只删掉末尾音节、首音节没变，已选字保留
        assertEquals(listOf("中"), retainPicked("zhongguo", "zhong", listOf("中")))
        // 前面音节真变了，已选字全丢
        assertEquals(emptyList<String>(), retainPicked("zhong", "zho", listOf("中")))
        assertEquals(emptyList<String>(), retainPicked("zhongguo", "chongguo", listOf("中")))
    }

    @Test
    fun `空格提交按已选字拼整词`() {
        assertEquals("中国", commitPickedPhrase("zhongguo", emptyList()))
        assertEquals("中国", commitPickedPhrase("zhongguo", listOf("中")))
        assertEquals("中", commitPickedPhrase("zhong", emptyList()))
        assertNull(commitPickedPhrase("zhongg", emptyList()))
        assertNull(commitPickedPhrase("zh", emptyList()))
        assertNull(commitPickedPhrase("", emptyList()))
    }

    @Test
    fun `空格语义多音节返回整词否则返回 null`() {
        assertEquals("中国", commitFirstCandidate("zhongguo"))
        assertEquals("接", commitFirstCandidate("jie"))
        assertNull(commitFirstCandidate("zhongg"))
        assertNull(commitFirstCandidate("zh"))
        assertNull(commitFirstCandidate(""))
    }

    @Test
    fun `合法音节还能往长里拼时不提前上屏`() {
        assertEquals(AppendLetterResult("zhan", null), appendLetter("zha", 'n'))
    }

    @Test
    fun `误输的非法缓冲被新字母重开`() {
        assertEquals(AppendLetterResult("j", null), appendLetter("zx", 'j'))
    }

    @Test
    fun `按下标取候选字越界返回 null`() {
        assertEquals("接", selectCandidate("jie", 0))
        assertEquals("解", selectCandidate("jie", 1)) // 新词典按字频排：jie 是接、解、姐……（旧版是接、街）
        assertNull(selectCandidate("jie", 999))
        assertNull(selectCandidate("zx", 0))
    }

    @Test
    fun `空格语义合法音节返回首选字否则返回 null`() {
        assertEquals("接", commitFirstCandidate("jie"))
        assertNull(commitFirstCandidate("zh"))
        assertNull(commitFirstCandidate(""))
    }

    @Test
    fun `九键拼音拼出 zhong 空格选首选字上屏`() {
        val composer = PinyinNineKeyComposer()
        // z(9 四下) h(4 两下) o(6 三下)，每步内连按间隔 300ms；
        // o、n 同在 6 键，o 落定须停顿 1 秒以上再按 6。
        var now = 0L
        fun tap(digit: String, gap: Long = 300L) {
            now += gap
            assertNull(composer.press(digit, now))
        }
        tap("9"); tap("9"); tap("9"); tap("9") // 待定 z
        tap("4"); tap("4") // h 落定 z→buffer z，待定 h
        tap("6"); tap("6"); tap("6") // o 落定 h→buffer zh，待定 o
        assertEquals("zho", composer.composingText)
        tap("6", gap = 1500L) // 停顿后按 6：o 落定→buffer zho，待定 m
        tap("6") // 待定 n
        assertEquals("zhon", composer.composingText)
        tap("4") // n 落定→buffer zhon，待定 g
        assertEquals("zhong", composer.composingText)
        assertEquals("中", getCandidates(composer.composingText)[0])
        // 空格：先落定 g→buffer zhong，再取首选字上屏
        assertNull(composer.settle())
        assertEquals("zhong", composer.buffer)
        assertEquals("中", commitFirstCandidate(composer.buffer))
    }

    @Test
    fun `九键拼音同一键连按只循环待定字母不毁掉音节`() {
        val composer = PinyinNineKeyComposer()
        // buffer 先拼到 zh：z(9 四下) h(4 两下)
        var now = 0L
        fun tap(digit: String) {
            now += 300L
            assertNull(composer.press(digit, now))
        }
        tap("9"); tap("9"); tap("9"); tap("9")
        tap("4"); tap("4")
        // 按 6 第一 下：h 落定→buffer zh，待定 m
        tap("6")
        assertEquals("zhm", composer.composingText)
        // 1 秒内再按 6：多击延续，待定 m -> n，只改待定字母，buffer 不动
        tap("6")
        assertEquals("zhn", composer.composingText)
        assertEquals("zh", composer.buffer)
        // 再按 6：待定 n -> o
        tap("6")
        assertEquals("zho", composer.composingText)
        assertEquals("zh", composer.buffer)
    }

    @Test
    fun `九键拼音退格先删待定字母再删 buffer`() {
        val composer = PinyinNineKeyComposer()
        var now = 0L
        fun tap(digit: String) {
            now += 300L
            composer.press(digit, now)
        }
        tap("9"); tap("9"); tap("9"); tap("9") // 待定 z
        assertTrue(composer.backspace()) // 删待定 z
        assertEquals("", composer.composingText)
        assertFalse(composer.backspace()) // 都空了
        tap("2") // 待定 a
        tap("2") // 待定 b
        tap("2") // 待定 c
        assertEquals("c", composer.composingText)
        assertTrue(composer.backspace())
        assertEquals("", composer.composingText)
    }
}
