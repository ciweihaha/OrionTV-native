package com.oriontv.data.repo

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
 * 设置仓库：双存储模式分支（plan §4.3）——按 server-config 的 StorageType
 * 决定收藏/记录/搜索历史走本地还是服务端；播放器个性化设置永远只存本地。
 * server-config 拉取带 30 秒失败缓存（RN authStore 的教训：服务器不可达时
 * 别每次都串行重试 3 次）与地址变化丢弃（旧请求结果不覆盖新地址）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val moonTvApi: MoonTvApi,
) {
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

    suspend fun currentApiBaseUrl(): String = apiBaseUrl.first()
    suspend fun currentM3uUrl(): String = m3uUrl.first()
    suspend fun currentLiveLastChannelJson(): String = settingsDataStore.liveLastChannel.first()
    suspend fun currentLiveFavChannelsJson(): String = settingsDataStore.liveFavChannels.first()
    suspend fun setLiveLastChannelJson(value: String) = settingsDataStore.setLiveLastChannel(value)
    suspend fun setLiveFavChannelsJson(value: String) = settingsDataStore.setLiveFavChannels(value)
    suspend fun currentStorageType(): String = storageType.first()
    suspend fun currentAuthCookies(): String = authCookies.first()

    /** 是否配了服务器地址（首页空态门控用）。 */
    suspend fun isServerConfigured(): Boolean = currentApiBaseUrl().isNotBlank()

    /** server-config 是否从未成功拉取过（"" = 未知，不是“本地模式”）。 */
    suspend fun isServerConfigUnknown(): Boolean = currentStorageType().isBlank()

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
    @Volatile
    private var lastConfigFailureAtMs: Long = 0L
    @Volatile
    private var lastConfigFailureUrl: String = ""

    /** B4-P2-7：refreshServerConfig 的互斥锁——并发换地址时两次拉取不再交错写 StorageType。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取 server-config 并缓存 StorageType。失败时 30 秒内不再重试；
     * 拉取期间地址变了则丢弃旧结果。
     */
    suspend fun refreshServerConfig(): ServerConfig? = refreshMutex.withLock {
        val baseUrl = currentApiBaseUrl()
        if (baseUrl.isBlank()) return null
        if (baseUrl == lastConfigFailureUrl &&
            System.currentTimeMillis() - lastConfigFailureAtMs < 30_000L
        ) {
            return null
        }
        return try {
            val config = moonTvApi.getServerConfig(baseUrl)
            if (currentApiBaseUrl() != baseUrl) return null // 地址已变，丢弃旧结果
            lastConfigFailureAtMs = 0L
            config.StorageType?.let { settingsDataStore.setStorageType(it) }
            config
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            lastConfigFailureAtMs = System.currentTimeMillis()
            lastConfigFailureUrl = baseUrl
            null
        }
    }

    suspend fun setAuthCookies(value: String) = settingsDataStore.setAuthCookies(value)
    suspend fun setApiBaseUrl(value: String) = settingsDataStore.setApiBaseUrl(value)
    suspend fun setM3uUrl(value: String) = settingsDataStore.setM3uUrl(value)

    /**
     * B4-P1-3：把 StorageType 清为空（未知态）——换源地址时先清，旧服务
     * 的 StorageType 不残留到新服务上；拉取失败即保持未知，下次数据访问
     * 自动重拉（ensureServerConfig），成功前按本地模式兜底。
     */
    suspend fun clearStorageType() = settingsDataStore.setStorageType("")
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
