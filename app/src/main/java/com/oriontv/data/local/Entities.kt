package com.oriontv.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room 实体骨架。键格式 `${source}+${id}`（plan §5.2），
 * `%`→`%25`、`+`→`%2B` 转义只在服务端接口 URL 里做，本地主键用原始 key。
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val cover: String,
    val title: String,
    val sourceName: String,
    val totalEpisodes: Int,
    val searchTitle: String,
    val year: String? = null,
    val saveTime: Long,
)

@Entity(tableName = "play_records")
data class PlayRecordEntity(
    @PrimaryKey val key: String,
    val title: String,
    val sourceName: String,
    val cover: String,
    val episodeIndex: Int,
    val totalEpisodes: Int,
    /** 已播秒数。 */
    val playTimeSeconds: Long,
    /** 总时长秒数。 */
    val totalTimeSeconds: Long,
    val saveTime: Long,
    val year: String? = null,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    val saveTime: Long,
)

/**
 * 播放器个性化设置（plan §5.4 mytv_player_settings 的单条值，本地存储）：
 * 键格式 `${source}+${id}`；只存自定义过的条目；上限 200 条，超限按插入顺序淘汰最旧。
 */
@Entity(tableName = "player_settings")
data class PlayerSettingEntity(
    @PrimaryKey val key: String,
    val introEndTimeMs: Long? = null,
    val outroStartTimeMs: Long? = null,
    val playbackRate: Float? = null,
    val saveTime: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY saveTime DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity)

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface PlayRecordDao {
    @Query("SELECT * FROM play_records ORDER BY saveTime DESC")
    suspend fun getAll(): List<PlayRecordEntity>

    @Query("SELECT * FROM play_records WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): PlayRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PlayRecordEntity)

    @Query("DELETE FROM play_records WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    /** 播放记录上限 200 条，超限按 save_time 淘汰最旧。 */
    @Query("DELETE FROM play_records WHERE `key` NOT IN (SELECT `key` FROM play_records ORDER BY saveTime DESC LIMIT 200)")
    suspend fun trimToLatest200()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY saveTime DESC LIMIT 20")
    suspend fun getLatest20(): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun deleteByKeyword(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    /** 搜索历史写侧上限 20 条：只留最新 20 条（P2-7；之前只读侧 LIMIT 20，表会无限长）。 */
    @Query("DELETE FROM search_history WHERE keyword NOT IN (SELECT keyword FROM search_history ORDER BY saveTime DESC LIMIT 20)")
    suspend fun trimToLatest20()
}

@Dao
interface PlayerSettingDao {
    @Query("SELECT * FROM player_settings WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): PlayerSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PlayerSettingEntity)

    @Query("DELETE FROM player_settings WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    /** 播放器设置上限 200 条，超限按插入顺序淘汰最旧。 */
    @Query("DELETE FROM player_settings WHERE `key` NOT IN (SELECT `key` FROM player_settings ORDER BY saveTime DESC LIMIT 200)")
    suspend fun trimToLatest200()
}
