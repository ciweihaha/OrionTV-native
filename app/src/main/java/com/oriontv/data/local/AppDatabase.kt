package com.oriontv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：仅新建 player_settings 表（M1 新增），不丢任何旧数据（P1-10）。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `player_settings` (" +
                "`key` TEXT NOT NULL, " +
                "`introEndTimeMs` INTEGER, " +
                "`outroStartTimeMs` INTEGER, " +
                "`playbackRate` REAL, " +
                "`saveTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

/**
 * Room 数据库。版本 2（M1 新增 player_settings 表；v1 → v2 用 [MIGRATION_1_2]
 * 真实迁移，旧版本用户升级后收藏/记录/搜索历史都在，不再破坏性重建）。
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
