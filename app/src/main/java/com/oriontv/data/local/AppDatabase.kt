package com.oriontv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/** Room 数据库骨架。版本 1；后续阶段加表/改字段时按 Room 迁移走。 */
@Database(
    entities = [FavoriteEntity::class, PlayRecordEntity::class, SearchHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playRecordDao(): PlayRecordDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
