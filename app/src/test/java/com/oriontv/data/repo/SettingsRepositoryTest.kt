package com.oriontv.data.repo

import com.oriontv.data.api.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** SettingsRepository 纯函数测试（第 6 轮 D6-P2-3）。 */
class SettingsRepositoryTest {

    @Test
    fun `null storage type resolves to localstorage`() {
        assertEquals("localstorage", SettingsRepository.resolveStorageType(ServerConfig(StorageType = null)))
    }

    @Test
    fun `blank storage type resolves to localstorage`() {
        assertEquals("localstorage", SettingsRepository.resolveStorageType(ServerConfig(StorageType = "")))
        assertEquals("localstorage", SettingsRepository.resolveStorageType(ServerConfig(StorageType = "   ")))
    }

    @Test
    fun `redis storage type is kept`() {
        assertEquals("redis", SettingsRepository.resolveStorageType(ServerConfig(StorageType = "redis")))
    }

    @Test
    fun `localstorage storage type is kept`() {
        assertEquals("localstorage", SettingsRepository.resolveStorageType(ServerConfig(StorageType = "localstorage")))
    }
}
