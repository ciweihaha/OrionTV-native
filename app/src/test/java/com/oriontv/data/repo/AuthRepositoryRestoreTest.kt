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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

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
        /** 登录返回前调用（第 7 轮 D7-P1-2：模拟 OkHttp 在登录响应到达时向 Jar 写 cookie）。 */
        var onLogin: (() -> Unit)? = null,
    ) : AuthApi {
        var loginCalls = 0
        var loginStarted = CompletableDeferred<Unit>()
        var logoutCalls = 0
        override var onUnauthorized: (suspend () -> Boolean)? = null

        override suspend fun login(baseUrl: String, username: String?, password: String?): MoonTvApi.LoginResult {
            loginCalls++
            // B10-P2-3：先把 loginGate 读进局部变量再 complete(loginStarted)——
            // 旧实现先 complete、再读可变字段，测试线程会在这窗口把 gate 置
            // null，旧恢复就误走 loginResults 的 Success 路径、全量跑时飘红。
            val gate = loginGate
            if (!loginStarted.isCompleted) loginStarted.complete(Unit)
            val result = gate?.let { it.await() }
                ?: (loginResults.removeFirstOrNull() ?: MoonTvApi.LoginResult.Success)
            onLogin?.invoke()
            return result
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
        override suspend fun setApiBaseUrl(value: String) {
            baseUrl = value
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
        /** B58-P2-1：置非 null 时 load() 抛该异常（模拟取消）。 */
        var loadError: Throwable? = null

        override suspend fun save(username: String, password: String) {
            saved = username to password
        }

        override suspend fun load(): Pair<String, String>? {
            loadError?.let { throw it }
            return saved
        }

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
    fun `logout before restore starts prevents silent re-login`() {
        // D7-P1-1 路径 1/2：退出发生在任务开始前——冷启动恢复与 31 秒延迟重试
        // 采样到的世代已是退出后的，世代守卫恒通过；已退出标志必须拦住，连
        // 登录 POST 都不发（无密码服务上空登录本会成功）。
        val api = FakeAuthApi()
        val settings = FakeAuthSettings()
        val repo = repository(api, settings)
        repo.configRetryDelayMs = 100L
        runBlocking {
            repo.logout()
            repo.checkLoginStatus()
            assertEquals(0, api.loginCalls)
            assertEquals(1, settings.refreshCalls)
            assertFalse(repo.isLoggedIn.value)
            assertTrue(repo.showLoginModal.value)
            // 延迟重试若被排队，100ms 后 loginCalls 会变成 1。
            delay(400)
            assertEquals(0, api.loginCalls)
            assertFalse(repo.isLoggedIn.value)
        }
    }

    @Test
    fun `401 callback after logout does not restore session`() {
        // D7-P1-1 路径 3：退出后任意一次服务端调用 401 都会调 onUnauthorized；
        // 已退出标志必须让回调直接回 false，不跑恢复。
        val api = FakeAuthApi()
        val repo = repository(api)
        runBlocking {
            repo.logout()
            val restored = api.onUnauthorized?.invoke()
            assertFalse(restored!!)
            assertEquals(0, api.loginCalls)
            assertFalse(repo.isLoggedIn.value)
        }
    }

    @Test
    fun `explicit login after logout clears logged-out flag`() {
        // D7-P1-1 的反向：退出后用户手动重登成功，标志清掉，后续恢复不再被拦。
        val api = FakeAuthApi()
        val repo = repository(api)
        runBlocking {
            repo.logout()
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            assertTrue(repo.restoreSession())
            // login() 一次 + restoreSession() 的静默登录一次。
            assertEquals(2, api.loginCalls)
            assertTrue(repo.isLoggedIn.value)
        }
    }

    @Test
    fun `in-flight restore completing after logout leaves no session cookie`() {
        // D7-P1-2：在途恢复的登录响应落在 logout 清 Jar 之后——OkHttp 在响应
        // 到达时把会话 cookie 写回 Jar；世代不匹配返回时必须清掉它，否则
        // 退出后进程内仍持有效会话 cookie。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val jar = InMemoryCookieJar()
        val repo = repository(api, settings, jar = jar)
        val url = "https://example.com/".toHttpUrl()
        api.onLogin = {
            jar.saveFromResponse(
                url,
                listOf(
                    okhttp3.Cookie.Builder().name("session").value("v").domain("example.com")
                        .expiresAt(System.currentTimeMillis() + 3_600_000).build(),
                ),
            )
        }
        runBlocking {
            val restoreJob = async { repo.restoreSession() }
            api.loginStarted.await()
            repo.logout()
            assertTrue(jar.loadForRequest(url).isEmpty())
            gate.complete(MoonTvApi.LoginResult.Success)
            assertFalse(restoreJob.await())
            assertFalse(repo.isLoggedIn.value)
            // 登录响应种下的 cookie 已被 scrub 掉。
            assertTrue(jar.loadForRequest(url).isEmpty())
            assertEquals("", settings.marker)
        }
    }

    @Test
    fun `scrub after logout does not delete a fresh manual login session`() {
        // D7-P1-2 的守卫条件：退出后手动重登的新会话 cookie 不得被旧恢复的
        // scrub 误删——以 _isLoggedIn 为条件，手动登录成功后不再清 Jar。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val jar = InMemoryCookieJar()
        val repo = repository(api, settings, jar = jar)
        val url = "https://example.com/".toHttpUrl()
        api.onLogin = {
            jar.saveFromResponse(
                url,
                listOf(
                    okhttp3.Cookie.Builder().name("session").value("v").domain("example.com")
                        .expiresAt(System.currentTimeMillis() + 3_600_000).build(),
                ),
            )
        }
        runBlocking {
            val restoreJob = async { repo.restoreSession() }
            api.loginStarted.await()
            repo.logout()
            api.loginGate = null
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.Success))
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            assertEquals(1, jar.loadForRequest(url).size)
            gate.complete(MoonTvApi.LoginResult.WrongCredentials)
            assertFalse(restoreJob.await())
            assertTrue(repo.isLoggedIn.value)
            assertEquals(1, jar.loadForRequest(url).size)
            assertEquals("1", settings.marker)
        }
    }

    @Test
    fun `failed restore does not clear a concurrent manual login`() {
        // D7-P2-1：后台恢复启动后用户手动登录成功，旧恢复因旧凭据失败走到
        // else 分支时不得把刚登进去的会话标成未登录。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val repo = repository(api, settings)
        runBlocking {
            val restoreJob = async { repo.restoreSession() }
            api.loginStarted.await()
            api.loginGate = null
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.Success))
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            gate.complete(MoonTvApi.LoginResult.WrongCredentials)
            assertFalse(restoreJob.await())
            assertTrue(repo.isLoggedIn.value)
            assertEquals("1", settings.marker)
        }
    }

    @Test
    fun `401 refresh failure shows login modal`() {
        // B10-P1-1：会话使用中过期、静默续期失败时弹登录框——旧实现只抛
        // UnauthorizedException，各调用方只落错误文案，进程内再无登录入口。
        val api = FakeAuthApi()
        val repo = repository(api)
        runBlocking {
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            assertFalse(repo.showLoginModal.value)
            // 续期用的已保存凭据已过期：静默重登失败。
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.WrongCredentials))
            val restored = api.onUnauthorized?.invoke()
            assertFalse(restored!!)
            assertEquals(2, api.loginCalls)
            assertFalse(repo.isLoggedIn.value)
            assertTrue(repo.showLoginModal.value)
        }
    }

    @Test
    fun `failed 401 refresh does not reopen modal after concurrent manual login`() {
        // B10-P1-1 的代次守卫：恢复在途时用户手动登录成功，那是新会话，
        // 旧恢复失败后不得把刚登进去的会话又弹回登录框（与 D7-P2-1 同款竞态）。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val repo = repository(api, settings)
        runBlocking {
            val refreshJob = async { api.onUnauthorized?.invoke() }
            api.loginStarted.await()
            api.loginGate = null
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.Success))
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            gate.complete(MoonTvApi.LoginResult.WrongCredentials)
            assertFalse(refreshJob.await()!!)
            assertTrue(repo.isLoggedIn.value)
            assertFalse(repo.showLoginModal.value)
            assertEquals("1", settings.marker)
        }
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

    @Test
    fun `shouldAutoShowLoginModal only when restore failed and not dismissed`() {
        // D8-P2-1 纯逻辑：恢复成功 / 已退出 / 恢复期间有人手动登入 /
        // 用户本会话已手动关过框，四种情况都不自动弹。
        assertTrue(shouldAutoShowLoginModal(restored = false, loggedOut = false, epochUnchanged = true, dismissedByUser = false))
        assertFalse(shouldAutoShowLoginModal(restored = true, loggedOut = false, epochUnchanged = true, dismissedByUser = false))
        assertFalse(shouldAutoShowLoginModal(restored = false, loggedOut = true, epochUnchanged = true, dismissedByUser = false))
        assertFalse(shouldAutoShowLoginModal(restored = false, loggedOut = false, epochUnchanged = false, dismissedByUser = false))
        assertFalse(shouldAutoShowLoginModal(restored = false, loggedOut = false, epochUnchanged = true, dismissedByUser = true))
    }

    @Test
    fun `dismissed login modal is not reopened by later 401`() {
        // D8-P2-1：用户手动关掉登录框后，过期会话下再有服务端调用 401，
        // 恢复再次失败也不得把框再弹出来；用户主动进登录入口后，节流重置。
        val api = FakeAuthApi(
            loginResults = ArrayDeque(
                listOf(
                    MoonTvApi.LoginResult.WrongCredentials,
                    MoonTvApi.LoginResult.WrongCredentials,
                    MoonTvApi.LoginResult.WrongCredentials,
                    MoonTvApi.LoginResult.WrongCredentials,
                ),
            ),
        )
        val repo = repository(api)
        runBlocking {
            // 第一次 401：恢复失败，弹框。
            assertFalse(api.onUnauthorized?.invoke()!!)
            assertTrue(repo.showLoginModal.value)
            // 用户手动关掉。
            repo.hideLogin()
            assertFalse(repo.showLoginModal.value)
            // 第二次 401：恢复又失败，不得再弹。
            assertFalse(api.onUnauthorized?.invoke()!!)
            assertFalse(repo.showLoginModal.value)
            // 用户主动进登录入口（showLogin）再关掉：节流重置后又被关掉，
            // 第三次 401 仍不得弹。
            repo.showLogin()
            assertTrue(repo.showLoginModal.value)
            repo.hideLogin()
            assertFalse(api.onUnauthorized?.invoke()!!)
            assertFalse(repo.showLoginModal.value)
        }
    }

    @Test
    fun `login success clears dismiss memory so later 401 shows modal again`() {
        // D8-P2-1 的另一半：登录成功后，之前的手动关闭记忆失效——新会话
        // 过期时自动弹框恢复。
        val api = FakeAuthApi(
            // 注意消耗顺序：第一次 401 的恢复耗 1 个；随后的手动登录须走
            // 队列空时的默认 Success，所以这里只放 1 个。
            loginResults = ArrayDeque(
                listOf(
                    MoonTvApi.LoginResult.WrongCredentials,
                ),
            ),
        )
        val repo = repository(api)
        runBlocking {
            assertFalse(api.onUnauthorized?.invoke()!!)
            assertTrue(repo.showLoginModal.value)
            repo.hideLogin()
            assertFalse(repo.showLoginModal.value)
            // 手动登录成功（loginResults 已空，Fake 默认回 Success）。
            assertTrue(repo.login("user", "pass").isSuccess)
            assertTrue(repo.isLoggedIn.value)
            // 新会话下再 401、恢复失败：应重新弹框。
            api.loginResults = ArrayDeque(listOf(MoonTvApi.LoginResult.WrongCredentials))
            assertFalse(api.onUnauthorized?.invoke()!!)
            assertTrue(repo.showLoginModal.value)
        }
    }

    @Test
    fun `login completing after logout does not revive session`() {
        // D8-P2-6：登录 POST 在途时用户点了退出，登录成功返回时不得把
        // 已退出的会话置回 true，也不得把刚清掉的凭据/标记重存一遍。
        val gate = CompletableDeferred<MoonTvApi.LoginResult>()
        val api = FakeAuthApi(loginGate = gate)
        val settings = FakeAuthSettings(marker = "")
        val store = FakeCredentialStore()
        val repo = repository(api, settings, store)
        runBlocking {
            val loginJob = async { repo.login("user", "pass") }
            api.loginStarted.await()
            repo.logout()
            assertFalse(repo.isLoggedIn.value)
            gate.complete(MoonTvApi.LoginResult.Success)
            val result = loginJob.await()
            assertTrue(result.isFailure)
            assertFalse(repo.isLoggedIn.value)
            assertFalse(repo.showLoginModal.value)
            // 凭据与标记保持退出后的干净状态，未被登录重存。
            assertEquals(null, store.saved)
            assertEquals("", settings.marker)
        }
    }

    @Test
    fun `cancelled credential load aborts restore without login attempt`() {
        // B58-P2-1：load() 抛 CancellationException 时 performRestore 不得
        // 把它吞掉、再用空凭据发一次登录 POST；取消原样抛出，与同文件其余
        // 6 处同类 catch 对齐。
        val api = FakeAuthApi()
        val store = FakeCredentialStore()
        store.loadError = CancellationException("cancelled")
        val repo = repository(api = api, store = store)
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.restoreSession() }
        }
        assertEquals(0, api.loginCalls)
        assertFalse(repo.isLoggedIn.value)
    }
}
