package com.oriontv.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `search result accepts numeric id and year and class field`() {
        val parsed = json.decodeFromString<SearchResult>(
            """{"id": 12345, "title": "庆余年", "poster": "", "episodes": [],
               "source": "a", "source_name": "源A", "class": "古装", "year": 2024}""",
        )
        assertEquals("12345", parsed.id)
        assertEquals("2024", parsed.year)
        assertEquals("古装", parsed.class_)
    }

    @Test
    fun `search result accepts string id and missing optionals`() {
        val parsed = json.decodeFromString<SearchResult>(
            """{"title": "庆余年", "poster": "", "source": "a", "source_name": "源A"}""",
        )
        assertNull(parsed.id)
        assertEquals(emptyList<String>(), parsed.episodes)
    }

    @Test
    fun `agg item accepts numeric score and year`() {
        val parsed = json.decodeFromString<AggVideoItem>(
            """{"vod_id": 7, "vod_name": "片", "vod_pic": "",
               "vod_year": 2024, "vod_douban_score": 8.5}""",
        )
        assertEquals("7", parsed.vod_id)
        assertEquals("2024", parsed.vod_year)
        assertEquals("8.5", parsed.vod_douban_score)
    }

    @Test
    fun `agg item defaults missing display fields`() {
        val parsed = json.decodeFromString<AggVideoItem>("""{"vod_id": "1"}""")
        assertEquals("", parsed.vod_name)
        assertEquals("", parsed.vod_pic)
    }

    @Test
    fun `search result defaults missing display fields`() {
        val parsed = json.decodeFromString<SearchResult>(
            """{"source": "a", "source_name": "源A"}""",
        )
        assertEquals("", parsed.title)
        assertEquals("", parsed.poster)
    }

    @Test
    fun `favorite defaults missing fields`() {
        val parsed = json.decodeFromString<Favorite>("""{}""")
        assertEquals("", parsed.cover)
        assertEquals("", parsed.title)
        assertEquals(0, parsed.total_episodes)
    }
}
