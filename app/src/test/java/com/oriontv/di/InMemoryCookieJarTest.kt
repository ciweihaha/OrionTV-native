package com.oriontv.di

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** InMemoryCookieJar 测试（第 4 轮 P0-4/P1-6）。 */
class InMemoryCookieJarTest {

    private fun cookie(name: String, value: String, domain: String = "example.com", path: String = "/"): Cookie =
        Cookie.Builder().name(name).value(value).domain(domain).path(path).build()

    @Test
    fun `new cookie wins over old with same name`() {
        val jar = InMemoryCookieJar()
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("session", "old")))
        jar.saveFromResponse(url, listOf(cookie("session", "new")))
        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.first().value)
    }

    @Test
    fun `load filters by path`() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(
            "https://example.com/api/play".toHttpUrl(),
            listOf(cookie("s", "v", path = "/api")),
        )
        assertEquals(1, jar.loadForRequest("https://example.com/api/play".toHttpUrl()).size)
        assertEquals(0, jar.loadForRequest("https://example.com/other".toHttpUrl()).size)
    }

    @Test
    fun `parent domain cookie is sent to subdomains`() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(
            "https://sub.example.com/".toHttpUrl(),
            listOf(cookie("s", "v")),
        )
        assertEquals(1, jar.loadForRequest("https://other.example.com/".toHttpUrl()).size)
    }

    @Test
    fun `clear removes all cookies`() {
        val jar = InMemoryCookieJar()
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("session", "v")))
        assertEquals(1, jar.loadForRequest(url).size)
        jar.clear()
        assertEquals(0, jar.loadForRequest(url).size)
    }
}
