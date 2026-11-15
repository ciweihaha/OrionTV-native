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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

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
        var setMarkerError: Throwable? = null
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = marker
        override suspend fun setAuthCookies(value: String) {
            setMarkerError?.let { throw it }
            marker = value
        }
        override suspend fun refreshServerConfig(): ServerConfig? {
            refreshCalls++
            return null
        }
        override suspend fun isServerConfigUnknown(): Boolean = false
    }

    private class FakeCredentialStore(
        var saved: Pair<String, String>? = "user" to "pass",
        var saveError: Throwable? = null,
        var clearError: Throwable? = null,
    ) : CredentialStore {
        var clearCalls = 0
        override suspend fun save(username: String, password: String) {
            saveError?.let { throw it }
            saved = username to password
        }
        override suspend fun load(): Pair<String, String>? = saved
        override suspend fun clear() {
            clearCalls++
            clearError?.let { throw it }
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
        // 注意：logout() 只保证清理执行，不吞远程异常——调用方仍会看到这次抛错。
        val api = FakeAuthApi(logoutError = IOException("server down"))
        val settings = FakeAuthSettings()
        val store = FakeCredentialStore()
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(api, settings, store, jar)
        assertThrows(IOException::class.java) {
            runBlocking { repo.logout() }
        }
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

    @Test
    fun `login cancelled while saving credentials is not marked logged in`() {
        // B13-P2-3：凭据保存时取消，旧 runCatching 会吞掉 CE、登录仍标成已登录。
        val store = FakeCredentialStore(saveError = CancellationException("cancelled"))
        val repo = repository(store = store)
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.login("user", "pass") }
        }
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `login cancelled while saving marker is not marked logged in`() {
        // B13-P2-3：同上，登录标记保存时取消也不标成已登录。
        val settings = FakeAuthSettings().apply {
            setMarkerError = CancellationException("cancelled")
        }
        val repo = repository(settings = settings)
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.login("user", "pass") }
        }
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `logout cancelled during credential clear still finishes cleanup and rethrows`() {
        // B13-P2-3：登出收尾跑完本就是期望行为——clear() 抛 CE 时记下、跑完
        // 剩余清理（清 Jar/清标记/落未登录态）再重抛，不截断清理。
        val settings = FakeAuthSettings()
        val store = FakeCredentialStore(clearError = CancellationException("cancelled"))
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(settings = settings, store = store, jar = jar)
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.logout() }
        }
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertEquals("", settings.marker)
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `logout cancelled during marker clear still lands logged out and rethrows`() {
        // B13-P2-3：同上，标记清除时取消也不拦 _isLoggedIn 落 false。
        val settings = FakeAuthSettings().apply {
            setMarkerError = CancellationException("cancelled")
        }
        val store = FakeCredentialStore()
        val jar = InMemoryCookieJar()
        seedCookie(jar)
        val repo = repository(settings = settings, store = store, jar = jar)
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.logout() }
        }
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertFalse(repo.isLoggedIn.value)
    }
}
