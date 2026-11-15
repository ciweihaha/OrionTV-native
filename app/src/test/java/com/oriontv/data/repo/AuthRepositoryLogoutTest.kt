package com.oriontv.data.repo

import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * AuthRepository.logout 测试（D-P1-4，第 4 轮跟进提交删掉 MockK 占位后零覆盖）：
 * 不依赖 MockK，用内存假实现 + 真 InMemoryCookieJar，断言 logout 三清
 * （凭据 + Cookie + 标记位），远程登出抛错时仍清。
 */
class AuthRepositoryLogoutTest {

    private class FakeAuthApi(
        var logoutError: Throwable? = null,
        /** 第 9 轮 D9-P2-2/D9-P2-3：第一次 login 调用停在这里（模拟在途恢复的登录 POST）。 */
        var loginGate: CompletableDeferred<Unit>? = null,
    ) : AuthApi {
        var logoutCalls = 0
        var loginCalls = 0
        override var onUnauthorized: (suspend () -> Boolean)? = null
        override suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult {
            loginCalls++
            // 只停第一次调用（恢复的 POST）；并发手动登录的 POST 直接成功。
            if (loginCalls == 1) loginGate?.await()
            return MoonTvApi.LoginResult.Success
        }
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
        /**
         * 第 9 轮 D9-P2-2/D9-P2-3：下一次 setAuthCookies("") 停在这里（模拟
         * scrubStaleRestoreSession 正在清标记）；一次性，停过即摘掉，后续
         * 的标记复位不再停。setAuthCookies("1") 从不停。
         */
        var parkMarkerResetGate: CompletableDeferred<Unit>? = null
        var markerResetParked = AtomicBoolean(false)
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = marker
        override suspend fun setAuthCookies(value: String) {
            setMarkerError?.let { throw it }
            if (value.isEmpty()) {
                val gate = parkMarkerResetGate
                if (gate != null) {
                    parkMarkerResetGate = null
                    markerResetParked.set(true)
                    gate.await()
                    markerResetParked.set(false)
                }
            }
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

    private suspend fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - start <= timeoutMs) { "等待条件超时" }
            delay(50)
        }
    }

    @Test
    fun `stale restore scrub inside lock does not clear concurrent manual login`() {
        // D9-P2-2：前置 scrub 原来在 loginStateMutex 外——scrub 凭 _isLoggedIn
        // 守卫，而 login() 在锁内先写凭据/标记、最后才置 _isLoggedIn=true；
        // 锁外检查时手动登录可能刚写完凭据/标记、尚未置位，scrub 会清掉新会话。
        // 编排：恢复的登录 POST 先停住 → 测试方退出（世代+1）→ 恢复的世代
        // 复核失败、进 scrub 并停在标记复位上（此时已持锁）→ 手动登录的 POST
        // 成功、等锁 → 放行 scrub → 手动登录拿锁写完。无修复时手动登录不等
        // 锁、直接写完标记 "1"，随后 scrub 的 setAuthCookies("") 才跑到，
        // 把刚登进去的标记清回 ""。
        runBlocking {
            val loginGate = CompletableDeferred<Unit>()
            val resetGate = CompletableDeferred<Unit>()
            val api = FakeAuthApi(loginGate = loginGate)
            val settings = FakeAuthSettings()
            val store = FakeCredentialStore()
            val jar = InMemoryCookieJar()
            val repo = repository(api, settings, store, jar)

            val restoreJob = async { repo.restoreSession() }
            waitUntil { api.loginCalls == 1 }
            repo.logout()
            settings.parkMarkerResetGate = resetGate
            loginGate.complete(Unit)
            waitUntil { settings.markerResetParked.get() }
            val manualLogin = async { repo.login("user", "pass") }
            delay(300) // 手动登录的 POST 已成功，现等在 loginStateMutex 上（锁被停住的 scrub 持有）
            resetGate.complete(Unit)
            assertTrue(manualLogin.await().isSuccess)
            assertFalse(restoreJob.await())
            assertEquals("1", settings.marker)
            assertEquals("user" to "pass", store.saved)
            assertTrue(repo.isLoggedIn.value)
        }
    }

    @Test
    fun `logout cancelled while waiting for lock still clears credentials and jar`() {
        // D9-P2-3：logout() 的 withLock 本身被取消时，B13-P2-3 只管了锁内
        // 的 CE，等锁时取消会让块内清理一行都不跑，退出“假成功”。
        // 编排与上一个测试同款：恢复的 scrub 停在标记复位上、持有
        // loginStateMutex；第二次 logout 等锁时被取消；放行 scrub 后，
        // 收尾仍要跑完（凭据/Jar/标记清掉、_isLoggedIn 落 false），再把
        // 取消信号还给调用方。无修复时等锁的 withLock 直接抛 CE，清理全跳过。
        runBlocking {
            val loginGate = CompletableDeferred<Unit>()
            val resetGate = CompletableDeferred<Unit>()
            val api = FakeAuthApi(loginGate = loginGate)
            val settings = FakeAuthSettings()
            val store = FakeCredentialStore()
            val jar = InMemoryCookieJar()
            val repo = repository(api, settings, store, jar)

            val restoreJob = async { repo.restoreSession() }
            waitUntil { api.loginCalls == 1 }
            repo.logout()
            settings.parkMarkerResetGate = resetGate
            loginGate.complete(Unit)
            waitUntil { settings.markerResetParked.get() }
            // scrub 已清过 Jar、标记复位正停住；再种一份“新会话残留”，
            // 只有第二次 logout 的收尾能清掉它（scrub 不碰凭据、Jar 清理已跑过）。
            store.saved = "user" to "pass"
            seedCookie(jar)
            settings.marker = "1"
            val logoutJob = async { repo.logout() }
            delay(300) // 第二次 logout 已走到 finally 的等锁处
            logoutJob.cancel()
            resetGate.complete(Unit)
            val thrown = runCatching { logoutJob.await() }.exceptionOrNull()
            assertTrue(thrown is CancellationException)
            assertFalse(restoreJob.await())
            assertNull(store.saved)
            assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
            assertEquals("", settings.marker)
            assertFalse(repo.isLoggedIn.value)
        }
    }
}
