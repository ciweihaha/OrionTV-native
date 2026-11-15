package com.oriontv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oriontv.core.storage.StorageKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// C4-P2-6：偏好文件损坏时用空偏好重建（默认值兜底），不再抛 CorruptionException
// 导致 onStart 的 .first() 崩溃、启动即崩溃循环。
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = StorageKeys.SETTINGS,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 设置类存储（DataStore Preferences）。
 * 注意：键名与 plan §5.4 的 AsyncStorage 键表保持一致；
 * RN 旧数据的首启迁移（读 RKStorage 库）见 RnMigration（D-P1-6），
 * 这里只提供新存储的读写 + 迁移完成标记。
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
        /** 倍速记忆：关掉后开播一律 1×，也不保存新倍速（阶段 4）。 */
        val SPEED_MEMORY = booleanPreferencesKey("speedMemory")
        val UPDATE_LAST_CHECK_TIME = longPreferencesKey(StorageKeys.UPDATE_LAST_CHECK_TIME)
        val UPDATE_SKIP_VERSION = stringPreferencesKey(StorageKeys.UPDATE_SKIP_VERSION)
        /** 仅登录标记位，"1" 或 ""，不是 token（plan §5.4）。 */
        val AUTH_COOKIES = stringPreferencesKey(StorageKeys.AUTH_COOKIES)
        /** server-config 的 StorageType 缓存："localstorage" | "redis"。 */
        val STORAGE_TYPE = stringPreferencesKey("storageType")
        /**
         * B6-P1-2：server-config 是否成功拉取过（独立标记）——服务端不返回
         * StorageType 时 StorageType 仍是 ""，旧实现拿 "" 当“未知”，每次
         * 数据访问都重拉；拉取成功即置 true（无论 StorageType 是否返回）。
         */
        val SERVER_CONFIG_FETCHED = booleanPreferencesKey("serverConfigFetched")
        /** 上次直播频道 JSON（plan §5.4：mytv_live_last_channel，{url, name}）。 */
        val LIVE_LAST_CHANNEL = stringPreferencesKey(StorageKeys.LIVE_LAST_CHANNEL)

        /** 收藏直播频道 JSON（plan §5.4：mytv_live_fav_channels，List<LiveFavChannel>）。 */
        val LIVE_FAV_CHANNELS = stringPreferencesKey(StorageKeys.LIVE_FAV_CHANNELS)

        /** RN 首启迁移已跑过（D-P1-6）：跑完置 true，失败不置位、下次启动重试。 */
        val RN_MIGRATION_DONE = booleanPreferencesKey("rnMigrationDone")
    }

    val apiBaseUrl: Flow<String> = context.settingsDataStore.data.map { it[Keys.API_BASE_URL].orEmpty() }
    val m3uUrl: Flow<String> = context.settingsDataStore.data.map { it[Keys.M3U_URL].orEmpty() }
    val remoteInputEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.REMOTE_INPUT_ENABLED] ?: false }
    val autoSkipIntro: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_SKIP_INTRO] ?: true }
    val introSkipSeconds: Flow<Int> = context.settingsDataStore.data.map { it[Keys.INTRO_SKIP_SECONDS] ?: 90 }
    val nextEpisodeOverlayEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.NEXT_EPISODE_OVERLAY_ENABLED] ?: true }
    val incognitoMode: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.INCOGNITO_MODE] ?: false }
    val backgroundPlayback: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.BACKGROUND_PLAYBACK] ?: false }
    val speedMemory: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SPEED_MEMORY] ?: true }
    val updateLastCheckTime: Flow<Long> = context.settingsDataStore.data.map { it[Keys.UPDATE_LAST_CHECK_TIME] ?: 0L }
    val updateSkipVersion: Flow<String> = context.settingsDataStore.data.map { it[Keys.UPDATE_SKIP_VERSION].orEmpty() }
    val authCookies: Flow<String> = context.settingsDataStore.data.map { it[Keys.AUTH_COOKIES].orEmpty() }
    val storageType: Flow<String> = context.settingsDataStore.data.map { it[Keys.STORAGE_TYPE].orEmpty() }
    val serverConfigFetched: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SERVER_CONFIG_FETCHED] ?: false }
    val liveLastChannel: Flow<String> = context.settingsDataStore.data.map { it[Keys.LIVE_LAST_CHANNEL].orEmpty() }
    val liveFavChannels: Flow<String> = context.settingsDataStore.data.map { it[Keys.LIVE_FAV_CHANNELS].orEmpty() }
    val rnMigrationDone: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.RN_MIGRATION_DONE] ?: false }

    /**
     * B13-P2-4：一次快照同时取 StorageType 与服务器地址——调用方（见
     * SettingsRepository.useServerStorage）判定用；两个单键 Flow 各 .first()
     * 有撕裂窗口（B12-P2-3 只修了写侧原子），快照内两键来自同一次 Preferences 发出。
     */
    suspend fun currentStorageTypeAndApiBaseUrl(): Pair<String, String> {
        val prefs = context.settingsDataStore.data.first()
        return prefs[Keys.STORAGE_TYPE].orEmpty() to prefs[Keys.API_BASE_URL].orEmpty()
    }

    suspend fun setAuthCookies(value: String) {
        context.settingsDataStore.edit { it[Keys.AUTH_COOKIES] = value }
    }

    suspend fun setStorageType(value: String) {
        context.settingsDataStore.edit { it[Keys.STORAGE_TYPE] = value }
    }

    suspend fun setServerConfigFetched(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SERVER_CONFIG_FETCHED] = value }
    }

    suspend fun setApiBaseUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.API_BASE_URL] = value }
    }

    suspend fun setM3uUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.M3U_URL] = value }
    }

    /**
     * B12-P2-3：源地址三键一次原子写——新 apiBaseUrl / m3uUrl 与 StorageType
     * 清空（type="" + serverConfigFetched=false）在同一个 edit {} 里落盘；
     * 旧实现是三次独立写，中间窗口里新地址配旧 StorageType、clearStorageType
     * 自身又是两次独立写，第二写失败还会留下 type="" + fetched=true 的撕裂态。
     * 四键同在 settings 这一个 DataStore 文件里，一次 edit 即原子（写失败时
     * 四键全不落盘，不会只写一半）。clearStorage=false 时只写两个地址。
     */
    suspend fun setSourceAddresses(apiUrl: String, m3uUrl: String, clearStorage: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.API_BASE_URL] = apiUrl
            it[Keys.M3U_URL] = m3uUrl
            if (clearStorage) {
                it[Keys.STORAGE_TYPE] = ""
                it[Keys.SERVER_CONFIG_FETCHED] = false
            }
        }
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

    suspend fun setSpeedMemory(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SPEED_MEMORY] = value }
    }

    suspend fun setUpdateLastCheckTime(value: Long) {
        context.settingsDataStore.edit { it[Keys.UPDATE_LAST_CHECK_TIME] = value }
    }

    suspend fun setUpdateSkipVersion(value: String) {
        context.settingsDataStore.edit { it[Keys.UPDATE_SKIP_VERSION] = value }
    }

    suspend fun setLiveLastChannel(value: String) {
        context.settingsDataStore.edit { it[Keys.LIVE_LAST_CHANNEL] = value }
    }

    suspend fun setLiveFavChannels(value: String) {
        context.settingsDataStore.edit { it[Keys.LIVE_FAV_CHANNELS] = value }
    }

    suspend fun setRnMigrationDone(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.RN_MIGRATION_DONE] = value }
    }
}
