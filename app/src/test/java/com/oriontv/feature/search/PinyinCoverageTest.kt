package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音词典常用字覆盖率测试：
 * 《通用规范汉字表》一级字表 3500 字，必须全部出现在词典候选里（目标 0 缺失）。
 * 字表文件在 app/src/test/resources/common3500.txt（一行 3500 字）。
 */
class PinyinCoverageTest {

    private fun loadCommon3500(): List<String> {
        val text = javaClass.classLoader
            ?.getResourceAsStream("common3500.txt")
            ?.bufferedReader(Charsets.UTF_8)
            ?.readText()
            ?: error("common3500.txt not found on test classpath")
        return text.filter { it.code in 0x4E00..0x9FFF }.map { it.toString() }.distinct()
    }

    @Test
    fun `通用规范汉字表一级3500字全部在词典里`() {
        val common = loadCommon3500()
        assertEquals(3500, common.size)
        val inDict = PINYIN_CANDIDATES.values.flatten().toSet()
        val missing = common.filter { it !in inDict }
        assertTrue("词典缺 ${missing.size} 个常用字：${missing.take(20)}", missing.isEmpty())
    }

    @Test
    fun `良在liang候选前排城在cheng候选前排`() {
        val liang = getCandidates("liang")
        assertEquals(3, liang.indexOf("良")) // 两、量、亮、良
        val cheng = getCandidates("cheng")
        assertEquals(1, cheng.indexOf("城")) // 成、城
    }

    @Test
    fun `词典音节数与每音节上限`() {
        assertEquals(412, PINYIN_CANDIDATES.size)
        // 每音节上限 40，常用字兜底补丁可把拥挤音节撑大（最大 52，ji），设 60 为上限 guardrail
        assertTrue(PINYIN_CANDIDATES.values.all { it.size <= 60 })
        assertTrue(PINYIN_CANDIDATES.values.all { it.distinct().size == it.size })
    }
}
