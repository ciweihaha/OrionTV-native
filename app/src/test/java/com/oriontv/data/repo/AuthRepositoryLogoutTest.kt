package com.oriontv.data.repo

import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.local.CredentialsStore
import com.oriontv.di.InMemoryCookieJar
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/** 退出登录测试（第 4 轮 P0-4）：标记位 + 已保存凭据 + CookieJar 三处都要清。 */
class AuthRepositoryLogoutTest {

    private class Mocks(
        val moonTvApi: MoonTvApi = mockk(),
        val settings: SettingsRepository = mockk(),
        val creds: CredentialsStore = mockk(),
        val jar: InMemoryCookieJar = mockk(),
    ) {
        init {
            coEvery { settings.currentApiBaseUrl() } returns "https://example.com"
            coEvery { settings.setAuthCookies(any()) } just Runs
            every { creds.clear() } just Runs
            every { jar.clear() } just Runs
        }

        fun repo(): AuthRepository = AuthRepository(moonTvApi, settings, creds, jar)
    }

    @Test
    fun `logout clears marker credentials and cookies`() = runBlocking {
        val mocks = Mocks()
        coEvery { mocks.moonTvApi.logout(any()) } just Runs
        val auth = mocks.repo()
        auth.logout()
        coVerify { mocks.settings.setAuthCookies("") }
        verify { mocks.creds.clear() }
        verify { mocks.jar.clear() }
        assertFalse(auth.isLoggedIn.value)
    }

    @Test
    fun `logout still clears everything when remote logout fails`() = runBlocking {
        val mocks = Mocks()
        coEvery { mocks.moonTvApi.logout(any()) } throws IOException("boom")
        val auth = mocks.repo()
        auth.logout()
        coVerify { mocks.settings.setAuthCookies("") }
        verify { mocks.creds.clear() }
        verify { mocks.jar.clear() }
        assertFalse(auth.isLoggedIn.value)
    }
}
