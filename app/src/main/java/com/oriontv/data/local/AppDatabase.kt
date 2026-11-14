package com.oriontv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库。版本 2（M1 新增 player_settings 表；fallbackToDestructiveMigration，
 * 全新安装无旧数据，无迁移可做）。
 */
@Database(
    entities = [
        FavoriteEntity::class,
        PlayRecordEntity::class,
        SearchHistoryEntity::class,
        PlayerSettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playRecordDao(): PlayRecordDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun playerSettingDao(): PlayerSettingDao
}
