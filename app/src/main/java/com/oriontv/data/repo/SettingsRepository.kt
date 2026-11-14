package com.oriontv.data.repo

import com.oriontv.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置仓库：双存储模式分支（plan §4.3）由 M1 补齐——按 server-config 的
 * StorageType 决定收藏/记录走本地还是服务端；播放器个性化设置永远只存本地。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) {
    val apiBaseUrl: Flow<String> = settingsDataStore.apiBaseUrl
    val m3uUrl: Flow<String> = settingsDataStore.m3uUrl
    val remoteInputEnabled: Flow<Boolean> = settingsDataStore.remoteInputEnabled
    val autoSkipIntro: Flow<Boolean> = settingsDataStore.autoSkipIntro
    val introSkipSeconds: Flow<Int> = settingsDataStore.introSkipSeconds
    val nextEpisodeOverlayEnabled: Flow<Boolean> = settingsDataStore.nextEpisodeOverlayEnabled
    val incognitoMode: Flow<Boolean> = settingsDataStore.incognitoMode
    val backgroundPlayback: Flow<Boolean> = settingsDataStore.backgroundPlayback

    suspend fun currentApiBaseUrl(): String = apiBaseUrl.first()
    suspend fun currentM3uUrl(): String = m3uUrl.first()

    suspend fun setApiBaseUrl(value: String) = settingsDataStore.setApiBaseUrl(value)
    suspend fun setM3uUrl(value: String) = settingsDataStore.setM3uUrl(value)
    suspend fun setRemoteInputEnabled(value: Boolean) = settingsDataStore.setRemoteInputEnabled(value)
    suspend fun setAutoSkipIntro(value: Boolean) = settingsDataStore.setAutoSkipIntro(value)
    suspend fun setIntroSkipSeconds(value: Int) = settingsDataStore.setIntroSkipSeconds(value)
    suspend fun setNextEpisodeOverlayEnabled(value: Boolean) = settingsDataStore.setNextEpisodeOverlayEnabled(value)
    suspend fun setIncognitoMode(value: Boolean) = settingsDataStore.setIncognitoMode(value)
    suspend fun setBackgroundPlayback(value: Boolean) = settingsDataStore.setBackgroundPlayback(value)
}
