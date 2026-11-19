package com.oriontv.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Assrt 在线字幕解析单测（第 76 轮 v2.0.15）：只测纯解析函数，不发网络请求。
 * 响应结构照 FongMi AssrtResponse：{"status":0,"sub":{"subs":[...]}}，
 * lang 可能是字符串或对象（取 desc）。
 */
class AssrtApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parseSearchResponse maps subs and skips invalid ones`() {
        val text = """
            {"status":0,"sub":{"subs":[
              {"id":123,"native_name":"哪吒之魔童闹海.2025.简体.srt","lang":{"desc":"简体中文"},
               "filelist":[{"f":"nezha.srt","url":"https://example.com/1.srt"}]},
              {"id":0,"native_name":"无效","lang":"简体"},
              {"id":456,"title":"流浪地球","lang":"简体中文",
               "filelist":[{"f":"readme.txt","url":"https://example.com/readme.txt"}]},
              {"id":789,"videoname":"孤注一掷","lang":"简体中文"}
            ]}}
        """.trimIndent()
        val results = AssrtApi.parseSearchResponse(json, text)
        // id=0 跳过；id=456 的 filelist 全是不支持的扩展名，跳过；
        // id=789 无 filelist，认为有文件，保留。
        assertEquals(2, results.size)
        assertEquals(123, results[0].id)
        assertEquals("哪吒之魔童闹海.2025.简体.srt", results[0].name)
        assertEquals("简体中文", results[0].lang)
        assertEquals(789, results[1].id)
        assertEquals("孤注一掷", results[1].name)
    }

    @Test
    fun `parseSearchResponse throws on non-zero status`() {
        val text = """{"status":20001,"errmsg":"invalid token"}"""
        val error = assertThrows(IOException::class.java) {
            AssrtApi.parseSearchResponse(json, text)
        }
        assertEquals("invalid token", error.message)
    }

    @Test
    fun `parseDetailResponse maps filelist and falls back to sub url`() {
        val text = """
            {"status":0,"sub":{"subs":[
              {"id":123,"native_name":"哪吒.srt","lang":"简体中文",
               "filelist":[
                 {"f":"nezha.chs.srt","url":"https://example.com/a.srt"},
                 {"f":"nezha.cht.ass","url":"https://example.com/b.ass"},
                 {"f":"cover.jpg","url":"https://example.com/c.jpg"}
               ]},
              {"id":456,"filename":"solo.srt","url":"https://example.com/solo.srt","lang":"简体中文"}
            ]}}
        """.trimIndent()
        val files = AssrtApi.parseDetailResponse(json, text)
        // jpg 过滤掉；无 filelist 的 solo 回落到自身 url。
        assertEquals(3, files.size)
        assertEquals("nezha.chs.srt", files[0].name)
        assertEquals("https://example.com/a.srt", files[0].url)
        assertEquals("nezha.cht.ass", files[1].name)
        assertEquals("solo.srt", files[2].name)
        assertEquals("https://example.com/solo.srt", files[2].url)
    }

    @Test
    fun `parseDetailResponse throws when no downloadable subtitle`() {
        val text = """{"status":0,"sub":{"subs":[{"id":1,"native_name":"x","filelist":[{"f":"a.jpg","url":"https://example.com/a.jpg"}]}]}}"""
        assertThrows(IOException::class.java) {
            AssrtApi.parseDetailResponse(json, text)
        }
    }

    @Test
    fun `parseSearchResponse tolerates missing sub`() {
        val text = """{"status":0}"""
        assertTrue(AssrtApi.parseSearchResponse(json, text).isEmpty())
    }
}
