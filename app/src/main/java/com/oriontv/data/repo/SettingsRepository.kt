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

    /** 是否配了服务器地址（首页空态门控用）。 */
    suspend fun isServerConfigured(): Boolean = currentApiBaseUrl().isNotBlank()

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
        ensureServerConfig()
        return currentStorageType() == "redis" && currentApiBaseUrl().isNotBlank()
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
    suspend fun setApiBaseUrl(value: String) = settingsDataStore.setApiBaseUrl(value)
    suspend fun setM3uUrl(value: String) = settingsDataStore.setM3uUrl(value)

    /**
     * B4-P1-3：把 StorageType 清为空（未知态）——换源地址时先清，旧服务
     * 的 StorageType 不残留到新服务上；拉取失败即保持未知，下次数据访问
     * 自动重拉（ensureServerConfig），成功前按本地模式兜底。
     * B6-P1-2：“已拉取过”标记一起清，否则新地址沿用旧标记、不再拉取。
     */
    suspend fun clearStorageType() {
        settingsDataStore.setStorageType("")
        settingsDataStore.setServerConfigFetched(false)
    }
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
        failedAtMs = 0L
    }

    fun isCached(url: String): Boolean =
        url.isNotBlank() && url == failedUrl && nowMs() - failedAtMs < 30_000L
}
