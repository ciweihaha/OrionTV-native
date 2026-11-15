package com.oriontv.data.repo

import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.api.PlayerSettings
import com.oriontv.data.api.UnauthorizedException
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
import java.util.concurrent.ConcurrentHashMap
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
 * 第 6 轮 D6-P1-2：服务端收藏 toggle 的读-改-写判定（纯逻辑，可单测；
 * FavoritesRepository.toggle 只负责接线）。
 *
 * 读失败必须抛错，不能默认按“不存在”走添加分支——否则用户点“取消收藏”
 * 时 GET 恰好失败、而随后的 POST 成功，取消会静默变成已收藏，还返回 true
 * 让 UI 显示成功。取消原样抛出；401 续期失败的 [UnauthorizedException]
 * 原样抛出（调用方按会话过期处理）；其余异常包成 [IOException]。
 * 写失败（add/delete 返回 false）同样抛 [IOException]（B-P2-8/B-P0-1）。
 */
internal suspend fun toggleServerFavorite(
    existsCheck: suspend () -> Boolean,
    addFavorite: suspend () -> Boolean,
    deleteFavorite: suspend () -> Boolean,
): Boolean {
    val exists = try {
        existsCheck()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnauthorizedException) {
        throw e
    } catch (e: Exception) {
        throw IOException("读取收藏状态失败：${e.message}", e)
    }
    if (exists) {
        if (!deleteFavorite()) {
            throw IOException("取消收藏失败：服务端未确认")
        }
        return false
    }
    if (!addFavorite()) {
        throw IOException("收藏失败：服务端未确认")
    }
    return true
}

/**
 * 第 7 轮 D7-P2-10：收藏态三态——服务端失败且从未查过时是 UNKNOWN（未知），
 * 调用方不得把它画成“未收藏”；本地模式下 Room 即实态，无 UNKNOWN。
 */
enum class FavoriteState { FAVORITE, NOT_FAVORITE, UNKNOWN }

/**
 * 第 7 轮 D7-P2-10：服务端失败时的收藏态判定（纯函数，可单测）——
 * 上次已知态照用；从未查过（lastKnown==null）时返回 UNKNOWN，不再默认
 * false 误显示“未收藏”（lastKnown 只活在进程内存，冷启动 + 离线时表是空的）。
 */
internal fun decideFavoriteState(lastKnown: Boolean?): FavoriteState =
    when (lastKnown) {
        true -> FavoriteState.FAVORITE
        false -> FavoriteState.NOT_FAVORITE
        null -> FavoriteState.UNKNOWN
    }

/**
 * 第 7 轮 D-P2-13：服务端 toggle 写后校验（纯逻辑，可单测）——
 * 写成功后同 key 再 GET 核对最终态；verified==null（校验 GET 本身失败）
 * 不推翻已成功的写，返回预期态（toggle 是翻转操作，校验失败就报失败、
 * 用户一点重试就会把刚写好的状态翻回去，比静默成功更坏）；一致返回
 * 预期态；不一致时先按服务端实态更新 lastKnown，再抛错——调用方提示
 * 失败并重读 isFavorite()，让 UI 显示服务端实态而非旧态。
 * 只做到“发现分叉并展示实态”，消灭不了竞态窗口本身（根治仍需服务端
 * 原子 toggle 接口：存在才删 / 不存在才加）。
 */
internal fun applyToggleVerification(
    expected: Boolean,
    verified: Boolean?,
    updateKnown: (Boolean) -> Unit,
): Boolean {
    if (verified == null) return expected
    updateKnown(verified)
    if (verified != expected) {
        throw IOException("服务端收藏状态与预期不一致（期望=$expected，实际=$verified），已按服务端实态显示")
    }
    return expected
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
    @Volatile
    private var lastGoodFavorites: Map<String, Favorite>? = null
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** B4-P2-19：上次已知的收藏态——服务端网络失败时返回它，不误显示“未收藏”。 */
    private val lastKnownFavorites = ConcurrentHashMap<String, Boolean>()

    /** 全部收藏（最新在前），附带记录键（导航到详情页要 source+id）。 */
    suspend fun getAllWithKeys(): List<Pair<String, Favorite>> {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
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
        // B16-P2-2：本地 Room 分支成功拉取也要复位 _loadFailed——之前只在服务端
        // 分支读写，服务端失败后切本地模式（或本地模式下点“重试”）仍挂虚假失败提示。
        return db.favoriteDao().getAll()
            .also { _loadFailed.value = false }
            .map { it.key to it.toFavorite() }
    }

    suspend fun getAll(): List<Favorite> = getAllWithKeys().map { it.second }

    suspend fun isFavorite(key: String): Boolean {
        // D7-P2-10：isFavorite 保持旧的 Boolean 契约供现有调用方用
        // （UNKNOWN 按 false 计）；需要区分“未知”的调用方用 favoriteState()。
        return favoriteState(key) == FavoriteState.FAVORITE
    }

    /**
     * 第 7 轮 D7-P2-10：三态收藏查询——服务端成功时以服务端为准；失败时
     * 用上次已知态；从未查过时返回 UNKNOWN（未知），调用方应展示未知态
     * （如按钮置灰）而非“未收藏”。本地模式以 Room 实态为准，无 UNKNOWN。
     */
    suspend fun favoriteState(key: String): FavoriteState {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            // B4-P2-19：服务端失败时返回上次已知态，不误显示“未收藏”；
            // 从未查过才返回 UNKNOWN（见 decideFavoriteState）。
            return try {
                val favorite = moonTvApi.getFavorites(baseUrl, key).isNotEmpty().also {
                    lastKnownFavorites[key] = it
                }
                if (favorite) FavoriteState.FAVORITE else FavoriteState.NOT_FAVORITE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                decideFavoriteState(lastKnownFavorites[key])
            }
        }
        return if (db.favoriteDao().getByKey(key) != null) {
            FavoriteState.FAVORITE
        } else {
            FavoriteState.NOT_FAVORITE
        }
    }

    /**
     * 删收藏前的严格存在检查（B8-P1-2）：与 [isFavorite] 不同，服务端失败时
     * 直接抛错、不返回上次已知态/默认 false——否则网络失败时调用方会把
     * “查不到”当成“已删过”而静默 return，删除无事发生、无任何提示。
     * 展示侧（详情页初始化）仍用宽容版 [isFavorite]，不误显示“未收藏”。
     */
    suspend fun isFavoriteStrict(key: String): Boolean {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            return moonTvApi.getFavorites(baseUrl, key).isNotEmpty().also {
                lastKnownFavorites[key] = it
            }
        }
        return db.favoriteDao().getByKey(key) != null
    }

    suspend fun toggle(key: String, favorite: Favorite): Boolean = writeMutex.withLock {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            // D-P2-13（第 7 轮客户端部分已做）：服务端 toggle 仍是“先 GET 判存在、
            // 再 POST/DELETE”的非原子操作；writeMutex 只串行化本进程内调用，
            // 双端或跨进程并发点两次仍可能互相覆盖。根治需服务端提供原子
            // toggle 接口（存在才删 / 不存在才加），客户端侧无法凭空造出。
            // 客户端只做写后校验（见下）：发现分叉时以服务端实态为准并抛错，
            // 调用方提示失败后重读 isFavorite() 展示实态。
            val baseUrl = serverStorage.baseUrl
            // D6-P1-2：读失败直接抛错（见 toggleServerFavorite），不再默认
            // 按“不存在”走添加分支——否则取消收藏会在 GET 失败时静默变已收藏。
            val nowFavorite = toggleServerFavorite(
                existsCheck = { moonTvApi.getFavorites(baseUrl, key).isNotEmpty() },
                addFavorite = { moonTvApi.addFavorite(baseUrl, key, favorite) },
                deleteFavorite = { moonTvApi.deleteFavorite(baseUrl, key) },
            )
            lastKnownFavorites[key] = nowFavorite
            // D-P2-13：写后校验——同 key 再 GET 一次核对最终态（每次服务端
            // toggle 多一次 GET 往返）；校验 GET 本身失败不推翻已成功的写。
            val verified: Boolean? = try {
                moonTvApi.getFavorites(baseUrl, key).isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FavoritesRepository", "收藏写后校验拉取失败，沿用写结果：${e.message}")
                null
            }
            applyToggleVerification(nowFavorite, verified) { verifiedState ->
                lastKnownFavorites[key] = verifiedState
                // D9-P2-1：校验发现服务端实态与预期不一致时，先按实态同步
                // lastGood——否则抛错后一次读失败回落旧表，已删的收藏会复活。
                lastGoodFavorites = patchLastGoodEntry(lastGoodFavorites, key, favorite, verifiedState)
            }
            // D9-P2-1：服务端写成功后同步 lastGood 收藏缓存——校验 GET
            // 本身失败（verified==null）时上面不跑，仍要按写结果 patch；
            // 否则随后一次读失败回落旧表时，刚删的收藏“幽灵复活”、刚加
            // 的收藏缺失。
            lastGoodFavorites = patchLastGoodEntry(lastGoodFavorites, key, favorite, nowFavorite)
            nowFavorite
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
                // 第 6 轮 D6-P2-15：本地收藏写侧裁剪到 200 条（与其余三表一致）。
                dao.trimToLatest200()
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

/**
 * 单条断点查询的缓存合并（纯函数，可单测，B14-P2-7）：服务端忽略 ?key=
 * 回整表（条数大于 1 或含非请求键）时整体替换——plus 合并会把已删记录
 * 幽灵复活（缓存里删掉的键又被整表带回来）；只有真正单条回表
 * （仅含请求键）才合并，不把全表缓存冲成一条（D7-P2-7）。
 * B19-P2-1：空表也是整表——服务端 GET 恒回整表、永不回单条（MoonTvApi
 * 的 D8-P2-5，已按 moontv-my 源码核实 ?key= 被忽略），空表就是“整表为
 * 空”的确定信息，整体替换、清空旧缓存；否则已删断点幽灵复活。
 */
internal fun mergePlayRecordLookup(
    cached: Map<String, PlayRecord>?,
    returned: Map<String, PlayRecord>,
    key: String,
): Map<String, PlayRecord> =
    if (returned.isEmpty() || returned.size > 1 || returned.keys.any { it != key }) {
        returned
    } else {
        cached?.plus(returned) ?: returned
    }

/**
 * 第 9 轮 D9-P2-1：服务端写成功后同步 lastGood* 缓存（纯函数，可单测）——
 * 写成功后不更新，读失败回落旧表时已删项会“幽灵复活”（B14-P2-7 修过整表
 * 回表的同款，这里是写路径的同款缺口）。写成功即按写结果 patch 对应 key
 * （加进新值/删掉旧值）；失效缓存（置 null）会丢离线兜底，patch 更优。
 * 缓存为 null（从未成功拉过）时加项新建单条表、删项保持 null。
 */
internal fun <V> patchLastGoodEntry(
    cached: Map<String, V>?,
    key: String,
    value: V?,
    present: Boolean,
): Map<String, V>? {
    if (!present) return cached?.minus(key)
    if (value == null) return cached
    return (cached ?: emptyMap()) + (key to value)
}

/**
 * 第 9 轮 D9-P2-1：搜索历史的 lastGood 补丁（纯函数，可单测）——新词插
 * 头部、去重；删词去掉该词；与 getLatest20 的“最新在前”口径一致。
 */
internal fun patchLastGoodHistory(
    cached: List<String>?,
    keyword: String,
    present: Boolean,
): List<String>? =
    if (present) {
        listOf(keyword) + (cached.orEmpty() - keyword)
    } else {
        cached?.minus(keyword)
    }

@Singleton
class PlayRecordsRepository @Inject constructor(
    private val db: AppDatabase,
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
) {
    private val writeMutex = Mutex()

    /** B-P2-20：上次成功拉到的服务端播放记录（失败时保留展示用）；失败标记供 UI 提示。 */
    @Volatile
    private var lastGoodRecords: Map<String, PlayRecord>? = null
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** 全部播放记录（最新在前），附带记录键。 */
    suspend fun getAllWithKeys(): List<Pair<String, PlayRecord>> {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
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
        // B16-P2-2：同上，本地 Room 分支成功拉取复位 _loadFailed（首页“最近播放”
        // 的残留失败提示同款清掉）。
        return db.playRecordDao().getAll()
            .also { _loadFailed.value = false }
            .map { it.key to it.toPlayRecord() }
    }

    suspend fun getAll(): List<PlayRecord> = getAllWithKeys().map { it.second }

    suspend fun getByKey(key: String): PlayRecord? {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            return try {
                // D7-P2-7：单条查询（?key=，与 getFavorites 同款），不再为查
                // 一条记录全表拉取；服务端忽略参数回整表时按 key 取值仍正确。
                // 注意：单条回表不得整体覆盖 lastGoodRecords（会把全表缓存
                // 冲成一条），只做合并；失败时仍用旧缓存里的断点兜底。
                // B14-P2-7：整表回表（条数大于 1 或含非请求键）时整体替换——
                // plus 合并会把已删记录幽灵复活；只有真正单条回表才合并。
                // B19-P2-1：空表也按整表处理（服务端恒回整表），整体替换清空旧缓存。
                moonTvApi.getPlayRecords(baseUrl, key).also {
                    lastGoodRecords = mergePlayRecordLookup(lastGoodRecords, it, key)
                    // B12-P2-6：单条查询成功不碰 _loadFailed——它本是“全表拉取
                    // 失败”标记，只由 getAllWithKeys() 维护；单条成功会清掉全表
                    // 失败标记，首页 stale 提示被误清（失败分支同理，见下）。
                }[key]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B-P2-20：拉取失败时用上次成功结果里的断点，不丢断点续播。
                // B12-P2-6：单条失败也不置 _loadFailed——全表标记只由
                // getAllWithKeys() 维护，单条失败不代表全表失败。
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
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val ok = runOrDefault(false) { moonTvApi.savePlayRecord(baseUrl, key, record) }
            // D9-P2-1：服务端写成功后同步 lastGood——否则读失败回落旧表时，
            // 刚保存的断点缺失（续播回到旧进度）。
            if (ok) lastGoodRecords = patchLastGoodEntry(lastGoodRecords, key, record, true)
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
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val ok = runOrDefault(false) { moonTvApi.deletePlayRecord(baseUrl, key) }
            // D9-P2-1：删成功后同步 lastGood——否则读失败回落旧表时已删
            // 记录“幽灵复活”（断点续播回到已删的片）。
            if (ok) lastGoodRecords = patchLastGoodEntry(lastGoodRecords, key, null, false)
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
    @Volatile
    private var lastGoodHistory: List<String>? = null

    /** 搜索历史最新 20 条，新词插头部、去重。 */
    suspend fun getLatest20(): List<String> {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val history = try {
                moonTvApi.getSearchHistory(baseUrl).also { lastGoodHistory = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // B-P2-20：拉取失败≠用户没历史，返回上次成功结果，不清空。
                // B8-P2-11：失败要记一条可见日志——旧实现静默回落，服务端
                // 改 shape/历史丢了都无迹可查。
                Log.w("SearchHistoryRepository", "搜索历史拉取失败，沿用上次成功结果：${e.message}")
                lastGoodHistory ?: emptyList()
            }
            return history.take(20)
        }
        return db.searchHistoryDao().getLatest20().map { it.keyword }
    }

    /**
     * B4-P2-18：服务端写操作（add/delete/clear）重试一次；最终失败返回
     * false 并记 Log.w，不再静默吞掉（否则删掉的词下次刷新又“幽灵复现”）。
     */
    private suspend fun serverWrite(tag: String, block: suspend () -> Unit): Boolean {
        repeat(2) { attempt ->
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt == 1) {
                    Log.w("SearchHistoryRepository", "服务端写搜索历史失败：$tag")
                    return false
                }
            }
        }
        return false
    }

    /** 返回是否写成功；服务端失败重试一次后仍失败返回 false（调用方不假装已写）。 */
    suspend fun add(keyword: String): Boolean = writeMutex.withLock {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withLock true
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val ok = serverWrite("add:$trimmed") {
                moonTvApi.addSearchHistory(baseUrl, trimmed)
            }
            // D9-P2-1：写成功后同步 lastGood——否则读失败回落旧表时新词缺失。
            if (ok) lastGoodHistory = patchLastGoodHistory(lastGoodHistory, trimmed, true)
            return@withLock ok
        }
        db.searchHistoryDao().insert(
            SearchHistoryEntity(keyword = trimmed, saveTime = System.currentTimeMillis()),
        )
        // 写侧裁剪到 20 条（P2-7；服务端分支由服务端管）。
        db.searchHistoryDao().trimToLatest20()
        true
    }

    /** 单条删除（搜索历史 chips 的 ×）；返回是否删成功。 */
    suspend fun delete(keyword: String): Boolean = writeMutex.withLock {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val ok = serverWrite("delete:$keyword") {
                moonTvApi.deleteSearchHistory(baseUrl, keyword)
            }
            // D9-P2-1：删成功后同步 lastGood——否则读失败回落旧表时已删的词复活。
            if (ok) lastGoodHistory = patchLastGoodHistory(lastGoodHistory, keyword, false)
            return@withLock ok
        }
        db.searchHistoryDao().deleteByKeyword(keyword)
        true
    }

    /** 返回是否清成功。 */
    suspend fun clear(): Boolean = writeMutex.withLock {
        val serverStorage = settingsRepository.currentServerStorageSnapshot()
        if (serverStorage.useServer) {
            val baseUrl = serverStorage.baseUrl
            val ok = serverWrite("clear") {
                moonTvApi.deleteSearchHistory(baseUrl)
            }
            // D9-P2-1：清成功后同步 lastGood——否则读失败回落旧表时已清的历史全复活。
            if (ok) lastGoodHistory = emptyList()
            return@withLock ok
        }
        db.searchHistoryDao().clearAll()
        true
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
