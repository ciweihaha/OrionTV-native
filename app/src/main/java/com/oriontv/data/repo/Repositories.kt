package com.oriontv.data.repo

import com.oriontv.data.api.Favorite
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.local.AppDatabase
import com.oriontv.data.local.FavoriteEntity
import com.oriontv.data.local.PlayRecordEntity
import com.oriontv.data.local.SearchHistoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 写串行化（plan §4.3）：RN 用 promise 链防读-改-写互相覆盖，
 * 原生版用 Mutex + 单线程写通道实现同等语义；
 * 收藏 toggle 连续点两次不能读到同一旧快照。
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val writeMutex = Mutex()

    suspend fun getAll(): List<Favorite> = db.favoriteDao().getAll().map {
        Favorite(
            cover = it.cover,
            title = it.title,
            source_name = it.sourceName,
            total_episodes = it.totalEpisodes,
            search_title = it.searchTitle,
            year = it.year,
            save_time = it.saveTime,
        )
    }

    suspend fun isFavorite(key: String): Boolean = db.favoriteDao().getByKey(key) != null

    suspend fun toggle(key: String, favorite: Favorite): Boolean = writeMutex.withLock {
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

@Singleton
class PlayRecordsRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val writeMutex = Mutex()

    suspend fun getAll(): List<PlayRecord> = db.playRecordDao().getAll().map {
        PlayRecord(
            title = it.title,
            source_name = it.sourceName,
            cover = it.cover,
            index = it.episodeIndex,
            total_episodes = it.totalEpisodes,
            play_time = it.playTimeSeconds,
            total_time = it.totalTimeSeconds,
            save_time = it.saveTime,
            year = it.year,
        )
    }

    /** 断点续播：进度 10 秒节流保存（由调用方节流，这里只写库）。 */
    suspend fun save(key: String, record: PlayRecord) = writeMutex.withLock {
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
        db.playRecordDao().deleteByKey(key)
    }
}

@Singleton
class SearchHistoryRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val writeMutex = Mutex()

    /** 搜索历史最新 20 条，新词插头部、去重。 */
    suspend fun getLatest20(): List<String> =
        db.searchHistoryDao().getLatest20().map { it.keyword }

    suspend fun add(keyword: String) = writeMutex.withLock {
        db.searchHistoryDao().insert(
            SearchHistoryEntity(keyword = keyword, saveTime = System.currentTimeMillis()),
        )
    }

    suspend fun clear() = writeMutex.withLock {
        db.searchHistoryDao().clearAll()
    }
}
