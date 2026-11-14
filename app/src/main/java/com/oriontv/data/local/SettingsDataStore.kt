package com.oriontv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oriontv.core.storage.StorageKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = StorageKeys.SETTINGS,
)

/**
 * 设置类存储（DataStore Preferences）。
 * 注意：键名与 plan §5.4 的 AsyncStorage 键表保持一致；
 * RN 旧数据的首启迁移（读 RKStorage 库）在 M1 补齐，这里只提供新存储的读写。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val API_BASE_URL = stringPreferencesKey("apiBaseUrl")
        val M3U_URL = stringPreferencesKey("m3uUrl")
        val REMOTE_INPUT_ENABLED = booleanPreferencesKey("remoteInputEnabled")
        val AUTO_SKIP_INTRO = booleanPreferencesKey("autoSkipIntro")
        val INTRO_SKIP_SECONDS = intPreferencesKey("introSkipSeconds")
        val NEXT_EPISODE_OVERLAY_ENABLED = booleanPreferencesKey("nextEpisodeOverlayEnabled")
        val INCOGNITO_MODE = booleanPreferencesKey("incognitoMode")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("backgroundPlayback")
        val UPDATE_LAST_CHECK_TIME = longPreferencesKey(StorageKeys.UPDATE_LAST_CHECK_TIME)
        val UPDATE_SKIP_VERSION = stringPreferencesKey(StorageKeys.UPDATE_SKIP_VERSION)
    }

    val apiBaseUrl: Flow<String> = context.settingsDataStore.data.map { it[Keys.API_BASE_URL].orEmpty() }
    val m3uUrl: Flow<String> = context.settingsDataStore.data.map { it[Keys.M3U_URL].orEmpty() }
    val remoteInputEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.REMOTE_INPUT_ENABLED] ?: false }
    val autoSkipIntro: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_SKIP_INTRO] ?: true }
    val introSkipSeconds: Flow<Int> = context.settingsDataStore.data.map { it[Keys.INTRO_SKIP_SECONDS] ?: 90 }
    val nextEpisodeOverlayEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.NEXT_EPISODE_OVERLAY_ENABLED] ?: true }
    val incognitoMode: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.INCOGNITO_MODE] ?: false }
    val backgroundPlayback: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.BACKGROUND_PLAYBACK] ?: false }
    val updateLastCheckTime: Flow<Long> = context.settingsDataStore.data.map { it[Keys.UPDATE_LAST_CHECK_TIME] ?: 0L }
    val updateSkipVersion: Flow<String> = context.settingsDataStore.data.map { it[Keys.UPDATE_SKIP_VERSION].orEmpty() }

    suspend fun setApiBaseUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.API_BASE_URL] = value }
    }

    suspend fun setM3uUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.M3U_URL] = value }
    }

    suspend fun setRemoteInputEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.REMOTE_INPUT_ENABLED] = value }
    }

    suspend fun setAutoSkipIntro(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_SKIP_INTRO] = value }
    }

    suspend fun setIntroSkipSeconds(value: Int) {
        context.settingsDataStore.edit { it[Keys.INTRO_SKIP_SECONDS] = value }
    }

    suspend fun setNextEpisodeOverlayEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.NEXT_EPISODE_OVERLAY_ENABLED] = value }
    }

    suspend fun setIncognitoMode(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCOGNITO_MODE] = value }
    }

    suspend fun setBackgroundPlayback(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKGROUND_PLAYBACK] = value }
    }

    suspend fun setUpdateLastCheckTime(value: Long) {
        context.settingsDataStore.edit { it[Keys.UPDATE_LAST_CHECK_TIME] = value }
    }

    suspend fun setUpdateSkipVersion(value: String) {
        context.settingsDataStore.edit { it[Keys.UPDATE_SKIP_VERSION] = value }
    }
}
