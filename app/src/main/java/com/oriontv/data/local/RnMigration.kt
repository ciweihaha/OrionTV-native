package com.oriontv.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.oriontv.core.storage.StorageKeys
import com.oriontv.core.util.AppLog
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.LenientDecode
import com.oriontv.data.api.PlayerSettings
import com.oriontv.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RN 首启迁移（D-P1-6）。
 *
 * 包名核对（2026-11-14）：RN 版（OrionTV-build，Expo 工程，app.json
 * `android.package`）与原生版（`applicationId`）均为 `com.oriontv`，
 * 同包名升级时老用户数据仍在应用沙盒里，必须迁移，否则收藏 / 播放记录 /
 * 搜索历史 / 设置在首次启动后表现为“全空”。
 *
 * RN AsyncStorage 在 Android 上的落盘（@react-native-async-storage/async-storage
 * 3.x，OrionTV-build 用 ^3.1.1）：
 * - 新库：Room 数据库文件 `AsyncStorage`，表 `Storage`（key TEXT / value TEXT）；
 * - 旧库：1.x 的 SQLite 文件 `RKStorage`，表 `catalystLocalStorage`
 *   （库内升级时由 createFromFile + 迁移搬进新库；老用户若从未在新版
 *   启动过，数据仍可能留在这里，故两处都读，新库优先）。
 * 只读打开，缺文件/缺表即当无旧数据；绝不写、不删旧库（降级回 RN 还能用）。
 *
 * 不迁的：登录凭据（RN 放 expo-secure-store，不在 AsyncStorage 里，
 * 用户升级后重新登录一次即可）；M3U/EPG 缓存（RN 与原生格式不同，
 * 缓存 15 秒可重拉，不算用户数据）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RnMigrationEntryPoint {
    fun rnMigration(): RnMigration
}

/** RN JSON blob 的纯解析（可 JVM 单测，不碰 Android API）。 */
object RnMigrationParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * RN 播放记录 blob：ApiPlayRecord + 可选的片头/片尾标记。
     * 时间单位与原生一致：play_time/total_time 是秒，introEndTime/outroStartTime
     * 是毫秒（RN utils/introSkip.ts：标记值是毫秒，默认片头秒数才 ×1000）。
     */
    @Serializable
    data class RnPlayRecord(
        val title: String = "",
        val source_name: String = "",
        val cover: String = "",
        val index: Int = 0,
        val total_episodes: Int = 0,
        val play_time: Long = 0,
        val total_time: Long = 0,
        val save_time: Long = 0,
        val year: String? = null,
        val introEndTime: Long? = null,
        val outroStartTime: Long? = null,
    )

    /** RN mytv_settings blob（AppSettings）：缺字段为 null 表示“不导入该项”。 */
    @Serializable
    data class RnAppSettings(
        val apiBaseUrl: String? = null,
        val m3uUrl: String? = null,
        val remoteInputEnabled: Boolean? = null,
        val autoSkipIntro: Boolean? = null,
        val introSkipSeconds: Int? = null,
        val nextEpisodeOverlayEnabled: Boolean? = null,
        val incognitoMode: Boolean? = null,
        val backgroundPlayback: Boolean? = null,
    )

    /** 收藏：key → Favorite；坏条目只丢该条（LenientDecode）。 */
    fun parseFavorites(raw: String): Map<String, Favorite> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return LenientDecode.decodeMap(json, obj, Favorite.serializer())
    }

    /** 播放记录：key → RnPlayRecord；坏条目只丢该条。 */
    fun parsePlayRecords(raw: String): Map<String, RnPlayRecord> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return LenientDecode.decodeMap(json, obj, RnPlayRecord.serializer())
    }

    /** 播放器个性化设置：key → PlayerSettings；坏条目只丢该条。 */
    fun parsePlayerSettings(raw: String): Map<String, PlayerSettings> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return LenientDecode.decodeMap(json, obj, PlayerSettings.serializer())
    }

    /** 设置：整包损坏返回 null（调用方跳过，不阻断其他集合）。 */
    fun parseSettings(raw: String): RnAppSettings? =
        runCatching { json.decodeFromString<RnAppSettings>(raw) }.getOrNull()

    /** 搜索历史：JSON 字符串数组；非字符串元素丢弃，整包损坏返回空表。 */
    fun parseSearchHistory(raw: String): List<String> {
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** 上次直播频道：必须是 JSON 对象才原样透传，否则返回 null（跳过）。 */
    fun validateLiveLastChannel(raw: String): String? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return raw.takeIf { el is JsonObject }
    }

    /** 收藏直播频道：必须是 JSON 数组才原样透传，否则返回 null（跳过）。 */
    fun validateLiveFavChannels(raw: String): String? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return raw.takeIf { el is JsonArray }
    }
}

/** RN AsyncStorage 旧库的只读读取（薄层，Android SQLite）。 */
@Singleton
class RnStorageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Outcome(val values: Map<String, String>, val readFailed: Boolean)

    private sealed interface DbRead {
        data class Ok(val values: Map<String, String>) : DbRead
        data object Missing : DbRead
        data object Failed : DbRead
    }

    /** 读指定键：先旧库、后新库（同键新库覆盖旧库）；任一库读失败会置 readFailed。 */
    fun readKeys(keys: Set<String>): Outcome {
        if (keys.isEmpty()) return Outcome(emptyMap(), false)
        val reads = listOf(
            readFromDb("RKStorage", "catalystLocalStorage", keys),
            readFromDb("AsyncStorage", "Storage", keys),
        )
        val values = LinkedHashMap<String, String>()
        reads.forEach { if (it is DbRead.Ok) values.putAll(it.values) }
        return Outcome(values, reads.any { it is DbRead.Failed })
    }

    private fun readFromDb(dbName: String, table: String, keys: Set<String>): DbRead {
        val file = context.getDatabasePath(dbName)
        if (!file.exists()) return DbRead.Missing
        return try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val out = LinkedHashMap<String, String>()
                val placeholders = keys.joinToString(",") { "?" }
                val cursor = db.rawQuery(
                    "SELECT `key`, `value` FROM `$table` WHERE `key` IN ($placeholders)",
                    keys.toTypedArray(),
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val k = it.getString(0) ?: continue
                        val v = it.getString(1) ?: continue
                        out[k] = v
                    }
                }
                DbRead.Ok(out)
            }
        } catch (e: Exception) {
            AppLog.w("RnMigration", "读 RN 旧库 $dbName/$table 失败：${e.message}")
            DbRead.Failed
        }
    }
}

/** RN 首启迁移执行器：只跑一次；导入时只补原生侧缺失的条目，不覆盖已有数据。 */
@Singleton
class RnMigration @Inject constructor(
    private val reader: RnStorageReader,
    private val db: AppDatabase,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    companion object {
        val WANTED_KEYS = setOf(
            StorageKeys.FAVORITES,
            StorageKeys.PLAY_RECORDS,
            StorageKeys.PLAYER_SETTINGS,
            StorageKeys.SEARCH_HISTORY,
            StorageKeys.SETTINGS,
            StorageKeys.LIVE_LAST_CHANNEL,
            StorageKeys.LIVE_FAV_CHANNELS,
        )
    }

    /** 应用启动时调一次（OrionTvApp.onCreate），后台跑，不阻塞首屏。 */
    fun start() {
        // D6-P2-11：协程体异常兜底——runIfNeeded 内已全程 try/catch，这里再加
        // CoroutineExceptionHandler 作第二道防线；未捕获异常不再经默认处理器崩应用。
        val handler = CoroutineExceptionHandler { _, e ->
            AppLog.w("RnMigration", "RN 首启迁移协程异常（已兜底，不崩应用）：${e.message}")
        }
        applicationScope.launch(handler) { runIfNeeded() }
    }

    suspend fun runIfNeeded() {
        // D6-P2-11：全程包 try/catch——旧实现只包 importAll，`rnMigrationDone.first()`
        // 或最后的 setRnMigrationDone 在启动瞬间 IO 失败时会从 launch 子协程里
        // 未捕获抛出、经默认处理器崩应用；现在一律记日志、不打标记，下次启动重试。
        try {
            runIfNeededInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("RnMigration", "RN 迁移启动失败，下次启动重试：${e.message}")
        }
    }

    private suspend fun runIfNeededInternal() {
        if (settingsDataStore.rnMigrationDone.first()) return
        val outcome = reader.readKeys(WANTED_KEYS)
        if (outcome.values.isEmpty()) {
            if (outcome.readFailed) {
                // 旧库在但读失败：不打标记，下次启动重试（数据还在旧库里，不丢）。
                AppLog.w("RnMigration", "RN 旧库读取失败，标记暂不置位，下次启动重试")
                return
            }
            // 无旧库、无旧数据：全新安装，打标记，不再重复检查。
            settingsDataStore.setRnMigrationDone(true)
            return
        }
        try {
            importAll(outcome.values)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 导入中途失败：不打标记，下次启动重试；已导入的条目因“只补缺失”
            // 不再重复写，未导入的下次补上（D6-P2-11）。
            AppLog.w("RnMigration", "RN 迁移失败，下次启动重试：${e.message}")
            return
        }
        settingsDataStore.setRnMigrationDone(true)
        AppLog.i("RnMigration", "RN 首启迁移完成")
    }

    private suspend fun importAll(values: Map<String, String>) {
        values[StorageKeys.FAVORITES]?.let { importFavorites(it) }
        values[StorageKeys.PLAYER_SETTINGS]?.let { importPlayerSettings(it) }
        values[StorageKeys.PLAY_RECORDS]?.let { importPlayRecords(it) }
        values[StorageKeys.SEARCH_HISTORY]?.let { importSearchHistory(it) }
        values[StorageKeys.SETTINGS]?.let { importSettings(it) }
        values[StorageKeys.LIVE_LAST_CHANNEL]?.let { importLiveLastChannel(it) }
        values[StorageKeys.LIVE_FAV_CHANNELS]?.let { importLiveFavChannels(it) }
    }

    private suspend fun importFavorites(raw: String) {
        // D6-P2-11：不再以“原生集合非空”整集合跳过——导入中途失败时已导入一半，
        // 下次启动会因非空直接跳过、剩余条目永久不再补；改为逐键补缺失的条目。
        val parsed = RnMigrationParser.parseFavorites(raw)
        val existing = db.favoriteDao().getAll().map { it.key }.toSet()
        val now = System.currentTimeMillis()
        var count = 0
        for ((index, key) in keysToImport(parsed.keys, existing).withIndex()) {
            val fav = parsed.getValue(key)
            db.favoriteDao().insert(
                FavoriteEntity(
                    key = key,
                    cover = fav.cover,
                    title = fav.title,
                    sourceName = fav.source_name,
                    totalEpisodes = fav.total_episodes,
                    searchTitle = fav.search_title,
                    year = fav.year,
                    // D7-P2-5：缺 save_time 的行按导入顺序递减 now - index，
                    // 不再同取 now——否则超 200 条裁剪时平局淘汰是任意子集。
                    saveTime = fallbackSaveTime(fav.save_time, now, index),
                ),
            )
            count++
        }
        // 第 6 轮 D6-P2-15：迁移导入后也裁剪到 200 条（与本地写侧一致）。
        db.favoriteDao().trimToLatest200()
        AppLog.i("RnMigration", "导入收藏 $count 条")
    }

    private suspend fun importPlayerSettings(raw: String) {
        // 逐条合并：原生已有该条时不覆盖。
        val parsed = RnMigrationParser.parsePlayerSettings(raw)
        val now = System.currentTimeMillis()
        var count = 0
        for ((key, settings) in parsed) {
            if (key.isBlank()) continue
            if (db.playerSettingDao().getByKey(key) != null) continue
            if (settings.introEndTime == null && settings.outroStartTime == null &&
                settings.playbackRate == null
            ) continue
            db.playerSettingDao().insert(
                PlayerSettingEntity(
                    key = key,
                    introEndTimeMs = settings.introEndTime,
                    outroStartTimeMs = settings.outroStartTime,
                    playbackRate = settings.playbackRate,
                    saveTime = now,
                ),
            )
            count++
        }
        AppLog.i("RnMigration", "导入播放器设置 $count 条")
    }

    private suspend fun importPlayRecords(raw: String) {
        // D6-P2-11：同 importFavorites，逐键补缺失——中途失败后重试能补上剩余条目。
        val parsed = RnMigrationParser.parsePlayRecords(raw)
        val existing = db.playRecordDao().getAll().map { it.key }.toSet()
        val now = System.currentTimeMillis()
        var count = 0
        for ((index, key) in keysToImport(parsed.keys, existing).withIndex()) {
            val record = parsed.getValue(key)
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
                    // D7-P2-5：同 importFavorites，缺时间的行按导入顺序递减。
                    saveTime = fallbackSaveTime(record.save_time, now, index),
                    year = record.year,
                ),
            )
            count++
            // 记录 blob 里可能嵌着旧版合并进来的片头/片尾标记（RN 读时合并、
            // 写时本应剥离，旧存量可能残留）：专用的 mytv_player_settings
            // 已先导入，这里只补没有的那几条。
            if ((record.introEndTime != null || record.outroStartTime != null) &&
                db.playerSettingDao().getByKey(key) == null
            ) {
                db.playerSettingDao().insert(
                    PlayerSettingEntity(
                        key = key,
                        introEndTimeMs = record.introEndTime,
                        outroStartTimeMs = record.outroStartTime,
                        playbackRate = null,
                        saveTime = now,
                    ),
                )
            }
        }
        db.playRecordDao().trimToLatest200()
        AppLog.i("RnMigration", "导入播放记录 $count 条")
    }

    private suspend fun importSearchHistory(raw: String) {
        // D6-P2-11：同 importFavorites，逐词补缺失——中途失败后重试能补上剩余词；
        // 仍按原顺序写回（saveTime 由原下标定，读侧顺序不变）。
        val parsed = RnMigrationParser.parseSearchHistory(raw).take(20)
        val existing = db.searchHistoryDao().getLatest20().map { it.keyword }.toSet()
        val now = System.currentTimeMillis()
        var count = 0
        parsed.forEachIndexed { index, keyword ->
            if (keyword in existing) return@forEachIndexed
            // RN 存的是最新在前的数组；按 saveTime 倒序写回，读侧顺序不变。
            db.searchHistoryDao().insert(
                SearchHistoryEntity(keyword = keyword, saveTime = now - index * 1000L),
            )
            count++
        }
        AppLog.i("RnMigration", "导入搜索历史 $count 条")
    }

    private suspend fun importSettings(raw: String) {
        // D7-P2-3：不再整包门控——首次导入中途某次写失败时，剩余设置在下次
        // 重试时会被“apiBaseUrl 或 m3uUrl 任一非空即整包跳过”挡住、永久丢失；
        // 改为逐项守卫：地址类只在原生侧仍空白时写（不覆盖用户已配的值），
        // 开关类幂等重写同一份 RN 值；失败只影响当项，其余下次重试能补上。
        // （迁移只在 rnMigrationDone==false 时跑，原生侧正常是白板；开关无
        // “空白”概念，重复写同一值无副作用。）
        val parsed = RnMigrationParser.parseSettings(raw) ?: return
        parsed.apiBaseUrl?.let {
            if (shouldImportUrlSetting(settingsDataStore.apiBaseUrl.first(), it)) {
                settingsDataStore.setApiBaseUrl(it)
            }
        }
        parsed.m3uUrl?.let {
            if (shouldImportUrlSetting(settingsDataStore.m3uUrl.first(), it)) {
                settingsDataStore.setM3uUrl(it)
            }
        }
        parsed.remoteInputEnabled?.let { settingsDataStore.setRemoteInputEnabled(it) }
        parsed.autoSkipIntro?.let { settingsDataStore.setAutoSkipIntro(it) }
        parsed.introSkipSeconds?.let { settingsDataStore.setIntroSkipSeconds(it) }
        parsed.nextEpisodeOverlayEnabled?.let { settingsDataStore.setNextEpisodeOverlayEnabled(it) }
        parsed.incognitoMode?.let { settingsDataStore.setIncognitoMode(it) }
        parsed.backgroundPlayback?.let { settingsDataStore.setBackgroundPlayback(it) }
        AppLog.i("RnMigration", "导入设置（apiBaseUrl/m3uUrl/播放开关）")
    }

    private suspend fun importLiveLastChannel(raw: String) {
        if (settingsDataStore.liveLastChannel.first().isNotBlank()) return
        RnMigrationParser.validateLiveLastChannel(raw)?.let {
            settingsDataStore.setLiveLastChannel(it)
        }
    }

    private suspend fun importLiveFavChannels(raw: String) {
        if (settingsDataStore.liveFavChannels.first().isNotBlank()) return
        RnMigrationParser.validateLiveFavChannels(raw)?.let {
            settingsDataStore.setLiveFavChannels(it)
        }
    }
}

/**
 * 第 6 轮 D6-P2-11：待导入的键 = 解析出的键去掉原生侧已有的键、去掉空键
 * （纯函数，可单测）。导入按“逐键补缺失”而非“集合非空即跳过”，
 * 中途失败后下次启动重试才能补上剩余条目。
 */
internal fun keysToImport(parsedKeys: Collection<String>, existingKeys: Set<String>): List<String> =
    parsedKeys.filter { it.isNotBlank() && it !in existingKeys }

/**
 * 第 7 轮 D7-P2-3：importSettings 的逐项门控（纯函数，可单测）——
 * 只有 RN 值非空、且原生侧当前仍空白时才写；原生已有值时不覆盖，
 * RN 值本身为空时不写（与整包门控不同，另一项的已写状态不影响本项）。
 */
internal fun shouldImportUrlSetting(currentValue: String, incoming: String?): Boolean =
    !incoming.isNullOrBlank() && currentValue.isBlank()

/**
 * 第 7 轮 D7-P2-5：迁移导入缺时间的行的 saveTime（纯函数，可单测）——
 * 有合法时间（>0）用原值；缺失/为 0 时按导入顺序递减 `now - index`
 * （与搜索历史 `now - index * 1000` 同款思路，毫秒级递减已足够区分
 * 顺序），不再同取 now——否则裁剪到 200 条时平局淘汰是任意子集。
 */
internal fun fallbackSaveTime(saveTime: Long?, now: Long, index: Int): Long =
    saveTime?.takeIf { it > 0 } ?: (now - index)
