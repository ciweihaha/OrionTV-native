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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 服务端调用失败时回落默认值，但协程取消必须原样抛出（不吞 CancellationException，P2）。
 * 各仓库的 runCatching{...}.getOrDefault(...) 都走这里。
 * 第 3 轮收尾 B-P2-20：列表拉取失败不再回落空数据——三个仓库各自保留上次成功
 * 结果（lastGood*）并置 loadFailed 标记，调用方保留旧展示 + 提示。
 */
private suspend fun <T> runOrDefault(default: T, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        default
    }
}

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

    /** B-P2-20：上次成功拉到的服务端收藏（失败时保留展示用）；失败标记供 UI 提示。 */
    private var lastGoodFavorites: Map<String, Favorite>? = null
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** 全部收藏（最新在前），附带记录键（导航到详情页要 source+id）。 */
    suspend fun getAllWithKeys(): List<Pair<String, Favorite>> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val favorites = try {
                moonTvApi.getFavorites(baseUrl).also {
                    lastGoodFavorites = it
                    _loadFailed.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B-P2-20：拉取失败≠用户没收藏，返回上次成功结果，不清空。
                _loadFailed.value = true
                lastGoodFavorites ?: emptyMap()
            }
            return favorites.entries
                .sortedByDescending { it.value.save_time ?: 0L }
                .map { it.key to it.value }
        }
        return db.favoriteDao().getAll().map { it.key to it.toFavorite() }
    }

    suspend fun getAll(): List<Favorite> = getAllWithKeys().map { it.second }

    suspend fun isFavorite(key: String): Boolean {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return runOrDefault(false) { moonTvApi.getFavorites(baseUrl, key).isNotEmpty() }
        }
        return db.favoriteDao().getByKey(key) != null
    }

    suspend fun toggle(key: String, favorite: Favorite): Boolean = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val exists = runOrDefault(false) { moonTvApi.getFavorites(baseUrl, key).isNotEmpty() }
            if (exists) {
                // B-P2-8/B-P0-1：服务端写失败（网络抛错或解码失败返回 false）
                // 不再当成功：抛 IOException 由 DetailViewModel 接住提示，收藏态不变。
                if (!moonTvApi.deleteFavorite(baseUrl, key)) {
                    throw IOException("取消收藏失败：服务端未确认")
                }
                false
            } else {
                if (!moonTvApi.addFavorite(baseUrl, key, favorite)) {
                    throw IOException("收藏失败：服务端未确认")
                }
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

    /** B-P2-20：上次成功拉到的服务端播放记录（失败时保留展示用）；失败标记供 UI 提示。 */
    private var lastGoodRecords: Map<String, PlayRecord>? = null
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** 全部播放记录（最新在前），附带记录键。 */
    suspend fun getAllWithKeys(): List<Pair<String, PlayRecord>> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val records = try {
                moonTvApi.getPlayRecords(baseUrl).also {
                    lastGoodRecords = it
                    _loadFailed.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B-P2-20：拉取失败≠用户没记录，返回上次成功结果，不清空。
                _loadFailed.value = true
                lastGoodRecords ?: emptyMap()
            }
            return records.entries
                .sortedByDescending { it.value.save_time }
                .map { it.key to it.value }
        }
        return db.playRecordDao().getAll().map { it.key to it.toPlayRecord() }
    }

    suspend fun getAll(): List<PlayRecord> = getAllWithKeys().map { it.second }

    suspend fun getByKey(key: String): PlayRecord? {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            return try {
                moonTvApi.getPlayRecords(baseUrl).also {
                    lastGoodRecords = it
                    _loadFailed.value = false
                }[key]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B-P2-20：拉取失败时用上次成功结果里的断点，不丢断点续播。
                _loadFailed.value = true
                lastGoodRecords?.get(key)
            }
        }
        return db.playRecordDao().getByKey(key)?.toPlayRecord()
    }

    /**
     * 断点续播：进度 10 秒节流保存（由调用方节流，这里只写库）。
     * 第 3 轮收尾 B-P2-19：返回是否写成功；服务端写失败记日志，不再静默丢弃
     *（调用方 PlayViewModel 周期性保存，下次保存即重试）。
     */
    suspend fun save(key: String, record: PlayRecord): Boolean = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val ok = runOrDefault(false) { moonTvApi.savePlayRecord(baseUrl, key, record) }
            if (!ok) Log.w("PlayRecordsRepository", "服务端保存播放记录失败 key=$key")
            return@withLock ok
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
        true
    }

    /** B-P2-19：返回是否删成功；服务端失败记日志，不再静默丢弃。 */
    suspend fun delete(key: String): Boolean = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val ok = runOrDefault(false) { moonTvApi.deletePlayRecord(baseUrl, key) }
            if (!ok) Log.w("PlayRecordsRepository", "服务端删除播放记录失败 key=$key")
            return@withLock ok
        }
        db.playRecordDao().deleteByKey(key)
        true
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

    /** B-P2-20：上次成功拉到的服务端搜索历史（失败时保留展示用）。 */
    private var lastGoodHistory: List<String>? = null

    /** 搜索历史最新 20 条，新词插头部、去重。 */
    suspend fun getLatest20(): List<String> {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            val history = try {
                moonTvApi.getSearchHistory(baseUrl).also { lastGoodHistory = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B-P2-20：拉取失败≠用户没历史，返回上次成功结果，不清空。
                lastGoodHistory ?: emptyList()
            }
            return history.take(20)
        }
        return db.searchHistoryDao().getLatest20().map { it.keyword }
    }

    suspend fun add(keyword: String) = writeMutex.withLock {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withLock
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runOrDefault(emptyList()) { moonTvApi.addSearchHistory(baseUrl, trimmed) }
            return@withLock
        }
        db.searchHistoryDao().insert(
            SearchHistoryEntity(keyword = trimmed, saveTime = System.currentTimeMillis()),
        )
        // 写侧裁剪到 20 条（P2-7；服务端分支由服务端管）。
        db.searchHistoryDao().trimToLatest20()
    }

    /** 单条删除（搜索历史 chips 的 ×）。 */
    suspend fun delete(keyword: String) = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runOrDefault(false) { moonTvApi.deleteSearchHistory(baseUrl, keyword) }
            return@withLock
        }
        db.searchHistoryDao().deleteByKeyword(keyword)
    }

    suspend fun clear() = writeMutex.withLock {
        if (settingsRepository.useServerStorage()) {
            val baseUrl = settingsRepository.currentApiBaseUrl()
            runOrDefault(false) { moonTvApi.deleteSearchHistory(baseUrl) }
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
