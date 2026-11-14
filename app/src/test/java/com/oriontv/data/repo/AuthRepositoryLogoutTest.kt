package com.oriontv.data.repo

import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * AuthRepository.logout 测试（D-P1-4，第 4 轮跟进提交删掉 MockK 占位后零覆盖）：
 * 不依赖 MockK，用内存假实现 + 真 InMemoryCookieJar，断言 logout 三清
 * （凭据 + Cookie + 标记位），远程登出抛错时仍清。
 */
class AuthRepositoryLogoutTest {

    private class FakeAuthApi(var logoutError: Throwable? = null) : AuthApi {
        var logoutCalls = 0
        override var onUnauthorized: (suspend () -> Boolean)? = null
        override suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult =
            MoonTvApi.LoginResult.Success
        override suspend fun logout(baseUrl: String) {
            logoutCalls++
            logoutError?.let { throw it }
        }
        override suspend fun getFavorites(baseUrl: String, key: String?): Map<String, Favorite> = emptyMap()
    }

    private class FakeAuthSettings(var baseUrl: String = "https://example.com") : AuthSettings {
        var marker: String = "1"
        var refreshCalls = 0
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = marker
        override suspend fun setAuthCookies(value: String) {
            marker = value
        }
        override suspend fun refreshServerConfig(): ServerConfig? {
            refreshCalls++
            return null
        }
        override suspend fun isServerConfigUnknown(): Boolean = false
    }

    private class FakeCredentialStore(var saved: Pair<String, String>? = "user" to "pass") : CredentialStore {
        var clearCalls = 0
        override suspend fun save(username: String, password: String) {
            saved = username to password
        }
        override suspend fun load(): Pair<String, String>? = saved
        override suspend fun clear() {
            clearCalls++
            saved = null
        }
    }

    private fun repository(
        api: FakeAuthApi = FakeAuthApi(),
        settings: FakeAuthSettings = FakeAuthSettings(),
        store: FakeCredentialStore = FakeCredentialStore(),
        jar: InMemoryCookieJar = InMemoryCookieJar(),
    ): AuthRepository =
        AuthRepository(api, settings, store, jar)

    private fun seedCookie(jar: InMemoryCookieJar) {
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().name("session").value("v").domain("example.com").build()),
        )
        assertEquals(1, jar.loadForRequest(url).size)
    }

    @Test
    fun `logout clears credentials cookies and marker`() {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings()
        val store = FakeCredentialStore()
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(api, settings, store, jar)
        runBlocking { repo.logout() }
        assertEquals(1, api.logoutCalls)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.saved)
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertEquals("", settings.marker)
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `logout still clears all three when remote logout throws`() {
        // 只清标记位等于没退：远程登出失败时三清也必须执行（finally）。
        val api = FakeAuthApi(logoutError = IOException("server down"))
        val settings = FakeAuthSettings()
        val store = FakeCredentialStore()
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(api, settings, store, jar)
        runBlocking { repo.logout() }
        assertEquals(1, api.logoutCalls)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.saved)
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertEquals("", settings.marker)
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `logout with blank base url still clears local state`() {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = "")
        val store = FakeCredentialStore()
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(api, settings, store, jar)
        runBlocking { repo.logout() }
        assertEquals(0, api.logoutCalls)
        assertEquals(1, store.clearCalls)
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertEquals("", settings.marker)
    }
}
