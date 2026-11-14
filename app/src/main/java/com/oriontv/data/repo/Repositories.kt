package com.oriontv.data.repo

import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.api.PlayerSettings
import com.oriontv.data.local.AppDatabase
import com.oriontv.data.local.FavoriteEntity
import com.oriontv.data.local.PlayRecordEntity
import com.oriontv.data.local.PlayerSettingEntity
import com.oriontv.data.local.SearchHistoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 写串行化（plan §4.3）：RN 用 promise 链防读-改-写互相覆盖，
 * 原生版用 Mutex + 单线程写通道实现同等语义；
 * 收藏 toggle 连续点两次不能读到同一旧快照。
 *
 * 双存储模式（plan §4.3）：server-config 的 StorageType 为 "redis" 且配了
 * API 地址时走服务端接口，否则走本地 Room。记录键格式 `${source}+${id}`。
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val db: AppDatabase,
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
) {
    private val writeMutex = Mutex()

    /** 全部收藏（最新在前），附带记录键（导航到详情页要 source+id）。 */
    suspend fun getAllWithKeys(): List<Pair<String, Favorite>> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runCatching { moonTvApi.getFavorites(baseUrl) }
                .getOrDefault(emptyMap())
                .entries
                .sortedByDescending { it.value.save_time ?: 0L }
                .map { it.key to it.value }
        }
        return db.favoriteDao().getAll().map { it.key to it.toFavorite() }
    }

    suspend fun getAll(): List<Favorite> = getAllWithKeys().map { it.second }

    suspend fun isFavorite(key: String): Boolean {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runCatching { moonTvApi.getFavorites(baseUrl, key).isNotEmpty() }
                .getOrDefault(false)
        }
        return db.favoriteDao().getByKey(key) != null
    }

    suspend fun toggle(key: String, favorite: Favorite): Boolean = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val exists = runCatching { moonTvApi.getFavorites(baseUrl, key).isNotEmpty() }
                .getOrDefault(false)
            if (exists) {
                moonTvApi.deleteFavorite(baseUrl, key)
                false
            } else {
                moonTvApi.addFavorite(baseUrl, key, favorite)
                true
            }
        } else {
            val dao = db.favoriteDao()
            if (dao.getByKey(key) != null) {
                dao.deleteByKey(key)
                false
            } else {
                dao.insert(
                    FavoriteEntity(
                        key = key,
                        cover = favorite.cover,
                        title = favorite.title,
                        sourceName = favorite.source_name,
                        totalEpisodes = favorite.total_episodes,
                        searchTitle = favorite.search_title,
                        year = favorite.year,
                        saveTime = System.currentTimeMillis(),
                    ),
                )
                true
            }
        }
    }

    private fun FavoriteEntity.toFavorite() = Favorite(
        cover = cover,
        title = title,
        source_name = sourceName,
        total_episodes = totalEpisodes,
        search_title = searchTitle,
        year = year,
        save_time = saveTime,
    )
}

@Singleton
class PlayRecordsRepository @Inject constructor(
    private val db: AppDatabase,
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
) {
    private val writeMutex = Mutex()

    /** 全部播放记录（最新在前），附带记录键。 */
    suspend fun getAllWithKeys(): List<Pair<String, PlayRecord>> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runCatching { moonTvApi.getPlayRecords(baseUrl) }
                .getOrDefault(emptyMap())
                .entries
                .sortedByDescending { it.value.save_time }
                .map { it.key to it.value }
        }
        return db.playRecordDao().getAll().map { it.key to it.toPlayRecord() }
    }

    suspend fun getAll(): List<PlayRecord> = getAllWithKeys().map { it.second }

    suspend fun getByKey(key: String): PlayRecord? {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runCatching { moonTvApi.getPlayRecords(baseUrl)[key] }.getOrNull()
        }
        return db.playRecordDao().getByKey(key)?.toPlayRecord()
    }

    /** 断点续播：进度 10 秒节流保存（由调用方节流，这里只写库）。 */
    suspend fun save(key: String, record: PlayRecord) = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runCatching { moonTvApi.savePlayRecord(baseUrl, key, record) }
            return@withLock
        }
        db.playRecordDao().insert(
            PlayRecordEntity(
                key = key,
                title = record.title,
                sourceName = record.source_name,
                cover = record.cover,
                episodeIndex = record.index,
                totalEpisodes = record.total_episodes,
                playTimeSeconds = record.play_time,
                totalTimeSeconds = record.total_time,
                saveTime = record.save_time,
                year = record.year,
            ),
        )
        db.playRecordDao().trimToLatest200()
    }

    suspend fun delete(key: String) = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runCatching { moonTvApi.deletePlayRecord(baseUrl, key) }
            return@withLock
        }
        db.playRecordDao().deleteByKey(key)
    }

    private fun PlayRecordEntity.toPlayRecord() = PlayRecord(
        title = title,
        source_name = sourceName,
        cover = cover,
        index = episodeIndex,
        total_episodes = totalEpisodes,
        play_time = playTimeSeconds,
        total_time = totalTimeSeconds,
        save_time = saveTime,
        year = year,
    )
}

@Singleton
class SearchHistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
) {
    private val writeMutex = Mutex()

    /** 搜索历史最新 20 条，新词插头部、去重。 */
    suspend fun getLatest20(): List<String> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runCatching { moonTvApi.getSearchHistory(baseUrl) }
                .getOrDefault(emptyList()).take(20)
        }
        return db.searchHistoryDao().getLatest20().map { it.keyword }
    }

    suspend fun add(keyword: String) = writeMutex.withLock {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withLock
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runCatching { moonTvApi.addSearchHistory(baseUrl, trimmed) }
            return@withLock
        }
        db.searchHistoryDao().insert(
            SearchHistoryEntity(keyword = trimmed, saveTime = System.currentTimeMillis()),
        )
    }

    /** 单条删除（搜索历史 chips 的 ×）。 */
    suspend fun delete(keyword: String) = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runCatching { moonTvApi.deleteSearchHistory(baseUrl, keyword) }
            return@withLock
        }
        db.searchHistoryDao().deleteByKeyword(keyword)
    }

    suspend fun clear() = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runCatching { moonTvApi.deleteSearchHistory(baseUrl) }
            return@withLock
        }
        db.searchHistoryDao().clearAll()
    }
}

/**
 * 播放器个性化设置仓库（plan §5.4 mytv_player_settings，永远只存本地）：
 * 片头/片尾标记、倍速按影片持久化；只存自定义过的条目；上限 200 条。
 */
@Singleton
class PlayerSettingsRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val writeMutex = Mutex()

    suspend fun get(key: String): PlayerSettings? =
        db.playerSettingDao().getByKey(key)?.let {
            PlayerSettings(
                introEndTime = it.introEndTimeMs,
                outroStartTime = it.outroStartTimeMs,
                playbackRate = it.playbackRate,
            )
        }

    suspend fun save(key: String, settings: PlayerSettings) = writeMutex.withLock {
        db.playerSettingDao().insert(
            PlayerSettingEntity(
                key = key,
                introEndTimeMs = settings.introEndTime,
                outroStartTimeMs = settings.outroStartTime,
                playbackRate = settings.playbackRate,
                saveTime = System.currentTimeMillis(),
            ),
        )
        db.playerSettingDao().trimToLatest200()
    }

    /** 合并式更新：只改传进来的字段，其余保留。 */
    suspend fun update(key: String, update: (PlayerSettings) -> PlayerSettings) =
        writeMutex.withLock {
            val current = get(key) ?: PlayerSettings()
            val next = update(current)
            db.playerSettingDao().insert(
                PlayerSettingEntity(
                    key = key,
                    introEndTimeMs = next.introEndTime,
                    outroStartTimeMs = next.outroStartTime,
                    playbackRate = next.playbackRate,
                    saveTime = System.currentTimeMillis(),
                ),
            )
            db.playerSettingDao().trimToLatest200()
        }
}
