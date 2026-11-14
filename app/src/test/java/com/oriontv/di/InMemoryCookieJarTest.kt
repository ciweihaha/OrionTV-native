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
    fun `same name different path cookies are both kept`() {
        // D-P2-6：去重按 name+domain+path，同名不同 path 的 cookie 不再被误杀。
        val jar = InMemoryCookieJar()
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(cookie("s", "v1", path = "/api"), cookie("s", "v2", path = "/other")),
        )
        assertEquals("v1", jar.loadForRequest("https://example.com/api/x".toHttpUrl()).first().value)
        assertEquals("v2", jar.loadForRequest("https://example.com/other/x".toHttpUrl()).first().value)
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

    @Test
    fun `same name different paths coexist`() {
        val jar = InMemoryCookieJar()
        val url = "https://example.com/api/play".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("session", "root", path = "/")))
        jar.saveFromResponse(url, listOf(cookie("session", "api", path = "/api")))
        // B4-P2-6：同名不同 path 是两个 cookie，去重键须含 path，不能互相覆盖。
        val loaded = jar.loadForRequest(url)
        assertEquals(2, loaded.size)
        assertEquals(setOf("root", "api"), loaded.map { it.value }.toSet())
        // /other 只带 path=/ 的那条。
        assertEquals(1, jar.loadForRequest("https://example.com/other".toHttpUrl()).size)
    }
}
