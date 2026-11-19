package com.oriontv.data.repo

import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthRepository 内置 MoonTV 账号测试（第 75 轮）：
 * 冷启动与静默恢复前，地址/凭据缺失时种入内置账号（打开即已登录）；
 * 用户改过的地址与用户自己的凭据一律不动；凭据只种给内置服务器。
 * 用内存假实现 + 可控登录，不走真网、不读 BuildConfig。
 */
class AuthRepositoryBuiltinTest {

    private class FakeAuthApi(
        var loginResult: MoonTvApi.LoginResult = MoonTvApi.LoginResult.Success,
    ) : AuthApi {
        var loginCalls = 0
        var lastLoginUsername: String? = "<unset>"
        var lastLoginPassword: String? = "<unset>"
        var lastLoginBaseUrl: String? = null
        override var onUnauthorized: (suspend () -> Boolean)? = null

        override suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult {
            loginCalls++
            lastLoginBaseUrl = baseUrl
            lastLoginUsername = username
            lastLoginPassword = password
            return loginResult
        }

        override suspend fun logout(baseUrl: String) {}

        override suspend fun getFavorites(baseUrl: String, key: String?): Map<String, Favorite> =
            emptyMap()
    }

    private class FakeAuthSettings(
        var baseUrl: String = "",
        var marker: String = "",
    ) : AuthSettings {
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = marker
        override suspend fun setAuthCookies(value: String) {
            marker = value
        }

        override suspend fun setApiBaseUrl(value: String) {
            baseUrl = value
        }

        override suspend fun refreshServerConfig(): ServerConfig? =
            ServerConfig(StorageType = "redis")

        override suspend fun isServerConfigUnknown(): Boolean = false
    }

    private class FakeCredentialStore(var saved: Pair<String, String>? = null) : CredentialStore {
        override suspend fun save(username: String, password: String) {
            saved = username to password
        }

        override suspend fun load(): Pair<String, String>? = saved

        override suspend fun clear() {
            saved = null
        }
    }

    private fun repository(
        api: FakeAuthApi = FakeAuthApi(),
        settings: FakeAuthSettings = FakeAuthSettings(),
        store: FakeCredentialStore = FakeCredentialStore(),
        jar: InMemoryCookieJar = InMemoryCookieJar(),
        builtin: BuiltinAccount = BuiltinAccount("admin", "builtin-pw", BuiltinAccount.BASE_URL),
    ): AuthRepository = AuthRepository(api, settings, store, jar, builtin)

    @Test
    fun `builtin seeds url and credentials then logs in on cold start`() = runBlocking {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = "", marker = "")
        val store = FakeCredentialStore(saved = null)
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals(BuiltinAccount.BASE_URL, settings.baseUrl)
        assertEquals("admin" to "builtin-pw", store.saved)
        assertEquals(1, api.loginCalls)
        assertEquals(BuiltinAccount.BASE_URL, api.lastLoginBaseUrl)
        assertEquals("admin", api.lastLoginUsername)
        assertEquals("builtin-pw", api.lastLoginPassword)
        assertTrue(repo.isLoggedIn.value)
        assertFalse(repo.showLoginModal.value)
        assertEquals("1", settings.marker)
    }

    @Test
    fun `builtin does not overwrite an existing api url and does not seed credentials for it`() = runBlocking {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = "https://user.example.com", marker = "")
        val store = FakeCredentialStore(saved = null)
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals("https://user.example.com", settings.baseUrl)
        assertNull(store.saved)
        assertEquals(1, api.loginCalls)
        assertNull(api.lastLoginUsername)
        assertNull(api.lastLoginPassword)
    }

    @Test
    fun `builtin does not overwrite saved user credentials`() = runBlocking {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = BuiltinAccount.BASE_URL, marker = "")
        val store = FakeCredentialStore(saved = "user" to "pass")
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals("user" to "pass", store.saved)
        assertEquals(1, api.loginCalls)
        assertEquals("user", api.lastLoginUsername)
        assertEquals("pass", api.lastLoginPassword)
        assertTrue(repo.isLoggedIn.value)
    }

    @Test
    fun `builtin seeds credentials when url already builtin and none saved`() = runBlocking {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = BuiltinAccount.BASE_URL, marker = "")
        val store = FakeCredentialStore(saved = null)
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals("admin" to "builtin-pw", store.saved)
        assertEquals("admin", api.lastLoginUsername)
        assertEquals("builtin-pw", api.lastLoginPassword)
        assertTrue(repo.isLoggedIn.value)
    }

    @Test
    fun `builtin disabled when password blank leaves everything untouched`() = runBlocking {
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = "", marker = "")
        val store = FakeCredentialStore(saved = null)
        val repo = repository(
            api = api,
            settings = settings,
            store = store,
            builtin = BuiltinAccount.unconfigured(),
        )

        repo.checkLoginStatus()

        assertEquals("", settings.baseUrl)
        assertNull(store.saved)
        assertEquals(0, api.loginCalls)
        assertFalse(repo.isLoggedIn.value)
        assertFalse(repo.showLoginModal.value)
    }

    @Test
    fun `builtin overwrites stale builtin password on cold start`() = runBlocking {
        // 第 76 轮 v2.0.15：服务端密码改过后，旧版存下的旧密码一直静默重登失败、
        // 弹登录框（用户报的“登录自动退出”）——保存的用户名就是内置用户名
        // （admin）时，密码与当前内置密码不一致即覆盖成新的。
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = BuiltinAccount.BASE_URL, marker = "")
        val store = FakeCredentialStore(saved = "admin" to "old-pw")
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals("admin" to "builtin-pw", store.saved)
        assertEquals(1, api.loginCalls)
        assertEquals("admin", api.lastLoginUsername)
        assertEquals("builtin-pw", api.lastLoginPassword)
        assertTrue(repo.isLoggedIn.value)
        assertFalse(repo.showLoginModal.value)
    }

    @Test
    fun `builtin does not overwrite saved credentials of a different user`() = runBlocking {
        // 用户名不是内置用户名（用户自己的账号）时，即使密码不同也不动。
        val api = FakeAuthApi()
        val settings = FakeAuthSettings(baseUrl = BuiltinAccount.BASE_URL, marker = "")
        val store = FakeCredentialStore(saved = "someone" to "their-pw")
        val repo = repository(api = api, settings = settings, store = store)

        repo.checkLoginStatus()

        assertEquals("someone" to "their-pw", store.saved)
        assertEquals("someone", api.lastLoginUsername)
        assertEquals("their-pw", api.lastLoginPassword)
    }

    @Test
    fun `shouldSeedBuiltinCredentials decision table`() {
        val builtin = BuiltinAccount("admin", "builtin-pw", BuiltinAccount.BASE_URL)
        assertTrue(shouldSeedBuiltinCredentials(null, builtin))
        assertTrue(shouldSeedBuiltinCredentials("admin" to "old-pw", builtin))
        assertFalse(shouldSeedBuiltinCredentials("admin" to "builtin-pw", builtin))
        assertFalse(shouldSeedBuiltinCredentials("user" to "pass", builtin))
        assertFalse(shouldSeedBuiltinCredentials("user" to "builtin-pw", builtin))
    }

    @Test
    fun `builtin account reports configured only with password and url`() {
        assertTrue(BuiltinAccount("admin", "pw", BuiltinAccount.BASE_URL).isConfigured)
        assertFalse(BuiltinAccount("admin", "", BuiltinAccount.BASE_URL).isConfigured)
        assertFalse(BuiltinAccount("admin", "pw", "").isConfigured)
        assertFalse(BuiltinAccount.unconfigured().isConfigured)
        assertEquals("admin", BuiltinAccount.USERNAME)
        assertEquals("https://movie.ciweihaha.win", BuiltinAccount.BASE_URL)
    }
}
