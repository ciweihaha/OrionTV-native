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

    @Test
    fun `same parent domain cookie saved via two subdomains is sent once`() {
        // D6-P2-6：父域 cookie 经 a/b 两个子域各存一份，请求时只发一遍。
        val jar = InMemoryCookieJar()
        jar.saveFromResponse("https://a.example.com/".toHttpUrl(), listOf(cookie("s", "v1")))
        jar.saveFromResponse("https://b.example.com/".toHttpUrl(), listOf(cookie("s", "v2")))
        val loaded = jar.loadForRequest("https://a.example.com/".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("v2", loaded.first().value)
        assertEquals(1, jar.loadForRequest("https://b.example.com/".toHttpUrl()).size)
    }

    @Test
    fun `cookie expiring between save and load is not sent`() {
        // D6-P2-6：保存时未过期、读取时已过期的 cookie 不下发、不残留。
        val jar = InMemoryCookieJar()
        val shortLived = Cookie.Builder().name("s").value("v").domain("example.com")
            .expiresAt(System.currentTimeMillis() + 50).build()
        jar.saveFromResponse("https://example.com/".toHttpUrl(), listOf(shortLived))
        assertEquals(1, jar.loadForRequest("https://example.com/".toHttpUrl()).size)
        Thread.sleep(120)
        assertEquals(0, jar.loadForRequest("https://example.com/".toHttpUrl()).size)
        // 之后再存别的 cookie，过期那条不得复活。
        jar.saveFromResponse("https://example.com/".toHttpUrl(), listOf(cookie("t", "w")))
        val loaded = jar.loadForRequest("https://example.com/".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("w", loaded.first().value)
    }

    @Test
    fun `expired cookies are dropped on save`() {
        // D6-P2-6：保存时已过期的 cookie 不进表。
        val jar = InMemoryCookieJar()
        val expired = Cookie.Builder().name("old").value("x").domain("example.com")
            .expiresAt(System.currentTimeMillis() - 1000).build()
        jar.saveFromResponse("https://example.com/".toHttpUrl(), listOf(expired, cookie("s", "v")))
        val loaded = jar.loadForRequest("https://example.com/".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("v", loaded.first().value)
    }
}
