package com.oriontv.data.repo

import com.oriontv.core.util.AppLog
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.ServerConfig
import com.oriontv.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * AuthRepository 用到的设置口（D-P1-4）：地址、登录标记位、server-config 刷新。
 * 单测可用内存假实现，不用真 DataStore。
 */
interface AuthSettings {
    suspend fun currentApiBaseUrl(): String
    suspend fun currentAuthCookies(): String
    suspend fun setAuthCookies(value: String)
    suspend fun refreshServerConfig(): ServerConfig?
    /** server-config 是否从未成功拉取过（B-P1-8，冷启动重试门控用）。 */
    suspend fun isServerConfigUnknown(): Boolean
}

/**
 * 设置仓库：双存储模式分支（plan §4.3）——按 server-config 的 StorageType
 * 决定收藏/记录/搜索历史走本地还是服务端；播放器个性化设置永远只存本地。
 * server-config 拉取带 30 秒失败缓存（RN authStore 的教训：服务器不可达时
 * 别每次都串行重试 3 次）与地址变化丢弃（旧请求结果不覆盖新地址）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val moonTvApi: MoonTvApi,
) : AuthSettings {
    val apiBaseUrl: Flow<String> = settingsDataStore.apiBaseUrl
    val m3uUrl: Flow<String> = settingsDataStore.m3uUrl
    val remoteInputEnabled: Flow<Boolean> = settingsDataStore.remoteInputEnabled
    val autoSkipIntro: Flow<Boolean> = settingsDataStore.autoSkipIntro
    val introSkipSeconds: Flow<Int> = settingsDataStore.introSkipSeconds
    val nextEpisodeOverlayEnabled: Flow<Boolean> = settingsDataStore.nextEpisodeOverlayEnabled
    val incognitoMode: Flow<Boolean> = settingsDataStore.incognitoMode
    val backgroundPlayback: Flow<Boolean> = settingsDataStore.backgroundPlayback
    val speedMemory: Flow<Boolean> = settingsDataStore.speedMemory
    val authCookies: Flow<String> = settingsDataStore.authCookies
    /** server-config 的 StorageType 缓存："" 未拉取、"localstorage" | "redis"。 */
    val storageType: Flow<String> = settingsDataStore.storageType

    override suspend fun currentApiBaseUrl(): String = apiBaseUrl.first()
    suspend fun currentM3uUrl(): String = m3uUrl.first()
    suspend fun currentLiveLastChannelJson(): String = settingsDataStore.liveLastChannel.first()
    suspend fun currentLiveFavChannelsJson(): String = settingsDataStore.liveFavChannels.first()
    suspend fun setLiveLastChannelJson(value: String) = settingsDataStore.setLiveLastChannel(value)
    suspend fun setLiveFavChannelsJson(value: String) = settingsDataStore.setLiveFavChannels(value)
    suspend fun currentStorageType(): String = storageType.first()
    override suspend fun currentAuthCookies(): String = authCookies.first()

    /**
     * server-config 是否从未成功拉取过。
     * B6-P1-2：按独立的“已拉取过”标记判，不按 StorageType 是否为空——
     * 服务端不返回 StorageType 时旧实现每次数据访问都重拉一次。
     * （已装机用户首次升级后标记为 false，会多拉一次，随即置位。）
     */
    override suspend fun isServerConfigUnknown(): Boolean =
        !settingsDataStore.serverConfigFetched.first()

    /**
     * 第 3 轮收尾 B-P1-8：StorageType 未知时先拉一次 server-config（失败有 30 秒
     * 缓存，不会每次调用都发请求）；已知后直接返回，不重复拉取。
     * 冷启动拉取失败不再把进程钉死在本地模式：下次数据访问前会重拉。
     */
    suspend fun ensureServerConfig(): ServerConfig? {
        if (!isServerConfigUnknown()) return null
        return refreshServerConfig()
    }

    /** 是否走服务端存储（redis 且配了地址）；否则走本地 Room。 */
    suspend fun useServerStorage(): Boolean {
        // B13-P2-4：一次快照同时取两键再判定——之前两次独立 .first() 分别读
        // storageType 与 apiBaseUrl，读侧仍有撕裂窗口。
        // B16-P2-3：判定本体走原子快照（见 currentServerStorageSnapshot）。
        return currentServerStorageSnapshot().useServer
    }

    /**
     * B16-P2-3：服务端存储判定 + 地址的一次原子快照——调用方之前先调
     * useServerStorage() 再调 currentApiBaseUrl()，两次独立 DataStore 读之间
     * 用户在设置页保存新地址可插进来，判定与地址错配；现一次快照读出配对，
     * 判定与地址必属同一时刻。ensureServerConfig() 先行（与旧 useServerStorage
     * 同款），快照读在其后。
     * 第 9 轮 D9-P2-5：DataStore 读失败（ensureServerConfig 里的
     * isServerConfigUnknown/refreshServerConfig 读地址、下面的一次快照读）
     * 原来全程无 try/catch，CE 之外的读异常直接上抛；调用方（收藏/记录/
     * 历史的各数据访问，全在 try 之外调这里）无兜底；登录侧 B12-P2-1
     * 同款口径：读失败按本地模式回落（useServer=false），CE 原样重抛。
     * （包在快照函数上：useServerStorage() 与各仓库都走这里，一处包全覆盖。）
     */
    suspend fun currentServerStorageSnapshot(): ServerStorageSnapshot =
        runOrDefault(ServerStorageSnapshot(useServer = false, baseUrl = "")) {
            ensureServerConfig()
            val (storageType, apiBaseUrl) = settingsDataStore.currentStorageTypeAndApiBaseUrl()
            resolveServerStorage(storageType, apiBaseUrl)
        }

    // D-P2-16：失败缓存的读写加 @Volatile，跨线程可见（之前最坏多跑一次拉取）。
    // 第 7 轮 D7-P2-9：失败缓存只记网络失败（见 ServerConfigFailureCache）——
    // DataStore 写失败没有记录入口，归因错误不可能再发生。
    private val configFailureCache = ServerConfigFailureCache()

    /** B4-P2-7：refreshServerConfig 的互斥锁——并发换地址时两次拉取不再交错写 StorageType。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取 server-config 并缓存 StorageType。失败时 30 秒内不再重试；
     * 拉取期间地址变了则丢弃旧结果。
     */
    override suspend fun refreshServerConfig(): ServerConfig? = refreshMutex.withLock {
        val baseUrl = currentApiBaseUrl()
        if (baseUrl.isBlank()) return null
        // B6-P2-7：锁内复核已知态——并发的两路都通过 ensureServerConfig 的
        // 未知检查后，先拿到锁的那路已拉取成功，后进锁的不再重复发请求。
        if (!isServerConfigUnknown()) return null
        if (configFailureCache.isCached(baseUrl)) {
            return null
        }
        // D7-P2-9：try 只包网络拉取——旧实现把 DataStore 写失败也落进同一个
        // catch、记成一次网络失败，30 秒内同地址不再拉取，调用方看到的 null
        // 与“服务端不可达”无法区分；现在写失败只记日志、不记缓存。
        val config = try {
            moonTvApi.getServerConfig(baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            configFailureCache.recordNetworkFailure(baseUrl)
            return null
        }
        // 地址已变，丢弃旧结果；DataStore 读失败按丢弃处理（不是网络失败，不记缓存）。
        val currentBaseUrl = try {
            currentApiBaseUrl()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (currentBaseUrl != baseUrl) return null
        configFailureCache.clear()
        try {
            config.StorageType?.let { settingsDataStore.setStorageType(it) }
            // B6-P1-2：拉取成功即记“已拉取过”，无论服务端是否返回 StorageType。
            settingsDataStore.setServerConfigFetched(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // D7-P2-9：网络拉取已成功，写失败不是网络失败——不记 30 秒失败
            // 缓存，只记日志、返回 null；fetched 标记未置位（或写到一半），
            // 下次访问会重拉重试持久化，不会被当成 30 秒网络不可达。
            AppLog.w("SettingsRepository", "server-config 拉取成功但本地保存失败，下次访问重拉：${e.message}")
            return null
        }
        config
    }

    /**
     * B6-P2-10：该地址的拉取失败是否还在 30 秒缓存内——在缓存内时
     * refreshServerConfig() 返回的 null 不是“本次拉取失败”，调用方
     * 不应把它报成配置失败。
     */
    suspend fun isConfigFailureCachedFor(url: String): Boolean =
        configFailureCache.isCached(url)

    override suspend fun setAuthCookies(value: String) = settingsDataStore.setAuthCookies(value)

    /**
     * 第 9 轮 D9-P2-5：DataStore 读写失败回落默认值，协程取消原样抛出
     * （同 Repositories.runOrDefault / AuthRepository.runOrDefault，
     * 那个是 private、这里各写一份；登录侧 B12-P2-1 同款口径）。
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
    suspend fun setApiBaseUrl(value: String) = settingsDataStore.setApiBaseUrl(value)
    suspend fun setM3uUrl(value: String) = settingsDataStore.setM3uUrl(value)

    /**
     * B12-P2-3：源地址三键一次原子写（见 SettingsDataStore.setSourceAddresses）——
     * 新地址与 StorageType 清空同一次 edit 落盘，不留“新地址配旧 StorageType”
     * 的中间窗口；写失败时四键全不落盘，调用方回滚旧地址即可。
     */
    suspend fun setSourceAddresses(apiUrl: String, m3uUrl: String, clearStorage: Boolean) =
        settingsDataStore.setSourceAddresses(apiUrl, m3uUrl, clearStorage)

    suspend fun setRemoteInputEnabled(value: Boolean) = settingsDataStore.setRemoteInputEnabled(value)
    suspend fun setAutoSkipIntro(value: Boolean) = settingsDataStore.setAutoSkipIntro(value)
    suspend fun setIntroSkipSeconds(value: Int) = settingsDataStore.setIntroSkipSeconds(value)
    suspend fun setNextEpisodeOverlayEnabled(value: Boolean) = settingsDataStore.setNextEpisodeOverlayEnabled(value)
    suspend fun setIncognitoMode(value: Boolean) = settingsDataStore.setIncognitoMode(value)
    suspend fun setBackgroundPlayback(value: Boolean) = settingsDataStore.setBackgroundPlayback(value)
    suspend fun setSpeedMemory(value: Boolean) = settingsDataStore.setSpeedMemory(value)
    suspend fun currentSpeedMemory(): Boolean = speedMemory.first()
    suspend fun currentIncognitoMode(): Boolean = incognitoMode.first()
    suspend fun currentBackgroundPlayback(): Boolean = backgroundPlayback.first()
    suspend fun currentUpdateLastCheckTime(): Long = settingsDataStore.updateLastCheckTime.first()
    suspend fun currentUpdateSkipVersion(): String = settingsDataStore.updateSkipVersion.first()
    suspend fun setUpdateLastCheckTime(value: Long) = settingsDataStore.setUpdateLastCheckTime(value)
    suspend fun setUpdateSkipVersion(value: String) = settingsDataStore.setUpdateSkipVersion(value)
}

/** B16-P2-3：服务端存储快照（判定 + 地址配对，一次快照读出）。 */
data class ServerStorageSnapshot(val useServer: Boolean, val baseUrl: String)

/**
 * B16-P2-3：快照判定（纯函数，可单测）——redis 且配了地址才走服务端，
 * 否则本地 Room；地址原样带回，调用方不再另读一次。
 */
internal fun resolveServerStorage(storageType: String, apiBaseUrl: String): ServerStorageSnapshot =
    ServerStorageSnapshot(storageType == "redis" && apiBaseUrl.isNotBlank(), apiBaseUrl)

/**
 * 第 7 轮 D7-P2-9：server-config 拉取失败的 30 秒缓存（纯逻辑，可单测；
 * 时间由调用方注入，便于测试）——只有网络拉取失败才记缓存；DataStore
 * 写失败没有记录入口（refreshServerConfig 的写失败分支只记日志、不调
 * 这里），归因错误不可能再发生。成功拉取后调 [clear]，下次失败重新计时。
 * 读写加 @Volatile，跨线程可见（D-P2-16 的约定保留）。
 */
internal class ServerConfigFailureCache(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var failedAtMs: Long = 0L

    @Volatile
    private var failedUrl: String = ""

    fun recordNetworkFailure(url: String) {
        failedAtMs = nowMs()
        failedUrl = url
    }

    fun clear() {
        // D7-P2-9：只清时间不清 URL 会留 bug——failedAtMs 归 0 后
        // nowMs() - 0 < 30 秒恒成立，旧 URL 仍命中缓存；两者一起复位。
        failedAtMs = 0L
        failedUrl = ""
    }

    fun isCached(url: String): Boolean =
        url.isNotBlank() && url == failedUrl && nowMs() - failedAtMs < 30_000L
}
