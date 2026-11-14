package com.oriontv.data.repo

import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.api.UnauthorizedException
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthRepository 恢复/退出竞态测试（第 6 轮 D6-P1-1/D6-P2-4/D6-P2-5）：
 * 退出登录必须作废配置重试与在途恢复；冷启动会话过期时登录 POST 只发一次。
 * 用内存假实现 + 可控登录门，不走真网；配置重试延迟由 internal 变量缩短。
 */
class AuthRepositoryRestoreTest {

    private class FakeAuthApi(
        var loginResults: ArrayDeque<MoonTvApi.LoginResult> = ArrayDeque(),
        var loginGate: CompletableDeferred<MoonTvApi.LoginResult>? = null,
        var favoritesBehavior: (suspend () -> Map<String, Favorite>)? = null,
    ) : AuthApi {
        var loginCalls = 0
        var loginStarted = CompletableDeferred<Unit>()
        var logoutCalls = 0
        override var onUnauthorized: (suspend () -> Boolean)? = null

        override suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult {
            loginCalls++
            if (!loginStarted.isCompleted) loginStarted.complete(Unit)
            loginGate?.let { return it.await() }
            return loginResults.removeFirstOrNull() ?: MoonTvApi.LoginResult.Success
        }

        override suspend fun logout(baseUrl: String) {
            logoutCalls++
        }

        override suspend fun getFavorites(baseUrl: String, key: String?): Map<String, Favorite> {
            return favoritesBehavior?.invoke() ?: emptyMap()
        }
    }

    private class FakeAuthSettings(
        var baseUrl: String = "https://example.com",
        var marker: String = "",
        var storageUnknown: Boolean = true,
    ) : AuthSettings {
        var refreshCalls = 0
        override suspend fun currentApiBaseUrl(): String = baseUrl
        override suspend fun currentAuthCookies(): String = marker
        override suspend fun setAuthCookies(value: String) {
            marker = value
        }

        override suspend fun refreshServerConfig(): ServerConfig? {
            refreshCalls++
            // 第 1 次拉取失败（触发 31 秒重试排队），之后成功返回 redis 配置。
            return if (refreshCalls == 1) {
                null
            } else {
                storageUnknown = false
                ServerConfig(StorageType = "redis")
            }
        }

        override suspend fun isServerConfigUnknown(): Boolean = storageUnknown
    }

    private class FakeCredentialStore(var saved: Pair<String, String>? = "user" to "pass") : CredentialStore {
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
    ): AuthRepository = AuthRepository(api, settings, store, jar)

    @Test
    fun `logout cancels scheduled config retry so it cannot log back in`() {
        // D6-P1-1：冷启动配置失败排队重试后，31 秒内退出登录，重试不得复登。
        val api = FakeAuthApi(
            loginResults = ArrayDeque(
                listOf(
                    MoonTvApi.LoginResult.WrongCredentials, // 冷启动初次恢复失败
                    MoonTvApi.LoginResult.Success, // 重试触发时若跑起来会成功复登
                ),
            ),
        )
        val settings = FakeAuthSettings()
        val repo = repository(api, settings)
        repo.configRetryDelayMs = 100L
        runBlocking {
            repo.checkLoginStatus()
            assertFalse(repo.isLoggedIn.value)
            assertTrue(repo.showLoginModal.value)
            assertEquals(1, api.loginCalls)
            repo.logout()
            delay(400)
            // 重试若未被取消，此时 loginCalls 会是 2、且已重新登录。
            assertEquals(1, api.loginCalls)
            assertEquals(1, settings.refreshCalls)
            assertFalse(repo.isLoggedIn.value)
            assertEquals("", settings.marker)
        }
    }

    @Test
    fun `in-flight restore completing after logout does not set logged in`() {
        // D6-P2-4：在途静默恢复在 logout 之后才成功返回，不得把已登出态置回 true。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val repo = repository(api, settings)
        runBlocking {
            val restoreJob = async { repo.restoreSession() }
            api.loginStarted.await()
            repo.logout()
            gate.complete(MoonTvApi.LoginResult.Success)
            assertFalse(restoreJob.await())
            assertFalse(repo.isLoggedIn.value)
            assertEquals("", settings.marker)
        }
    }

    @Test
    fun `expired session with refresh already tried does not log in twice`() {
        // D6-P2-5：verifySession 的 getFavorites 触发 401 续期（登录 #1）后仍 401，
        // checkLoginStatus 不再重跑 restoreSession（登录 #2），直接弹登录框。
        val api = FakeAuthApi()
        api.favoritesBehavior = {
            // 模拟 MoonTvApi.execute 的 401 路径：先调续期回调，再抛过期异常
            //（续期成功但重发仍 401，见 execute 的 refreshAttempted 守卫）。
            api.onUnauthorized?.invoke()
            throw UnauthorizedException("登录已过期，请重新登录")
        }
        val settings = FakeAuthSettings(marker = "1")
        val repo = repository(api, settings)
        runBlocking { repo.checkLoginStatus() }
        assertEquals(1, api.loginCalls)
        assertFalse(repo.isLoggedIn.value)
        assertTrue(repo.showLoginModal.value)
    }

    @Test
    fun `expired session without refresh attempt still restores once`() {
        // D6-P2-5 的另一半：401 时续期回调没触发（如假实现直抛），
        // checkLoginStatus 仍走一次静默重登，不丢恢复机会。
        val api = FakeAuthApi(
            loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.Success)),
        )
        api.favoritesBehavior = { throw UnauthorizedException("登录已过期，请重新登录") }
        val settings = FakeAuthSettings(marker = "1")
        val repo = repository(api, settings)
        runBlocking { repo.checkLoginStatus() }
        assertEquals(1, api.loginCalls)
        assertTrue(repo.isLoggedIn.value)
        assertFalse(repo.showLoginModal.value)
    }

    @Test
    fun `logout does not cancel a fresh login after logout`() {
        // D6-P2-4 的反向：退出后用户重新登录成功，旧恢复的失败分支不得把登录态清掉。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val repo = repository(api, settings)
        runBlocking {
            val restoreJob = async { repo.restoreSession() }
            api.loginStarted.await()
            repo.logout()
            // 退出后用户立即手动重登成功（login() 不经过 performRestore）。
            api.loginGate = null
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.Success))
            val loginResult = repo.login("user", "pass")
            assertTrue(loginResult.isSuccess)
            assertTrue(repo.isLoggedIn.value)
            // 旧恢复此时才以失败收尾（门里换成 WrongCredentials 模拟过期凭据）。
            gate.complete(MoonTvApi.LoginResult.WrongCredentials)
            assertFalse(restoreJob.await())
            assertTrue(repo.isLoggedIn.value)
            assertEquals("1", settings.marker)
        }
    }
}
