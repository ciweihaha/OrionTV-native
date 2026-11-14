package com.oriontv.di

import android.content.Context
import androidx.room.Room
import com.oriontv.data.api.AggApi
import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.local.AppDatabase
import com.oriontv.data.local.MIGRATION_1_2
import com.oriontv.feature.live.EpgRepository
import com.oriontv.feature.live.M3uRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/** Hilt 单例模块：网络、序列化、数据库。 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 进程内 CookieJar：登录会话 cookie 存在这里（RN 的原生 jar 同款语义）。
     * 冷启动后会话失效时由 AuthRepository 用已保存凭据静默重登恢复。
     * 返回具体类型 [InMemoryCookieJar]（而非接口），以便 AuthRepository 退出登录时调 clear()。
     */
    @Provides
    @Singleton
    fun provideCookieJar(): InMemoryCookieJar = InMemoryCookieJar()

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: InMemoryCookieJar): OkHttpClient =
        ApiClient.newBuilder().cookieJar(cookieJar).build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "oriontv.db")
            // v1 → v2 用真实迁移（只建 player_settings 表），不再破坏性重建：
            // 旧版本用户升级后收藏/记录/搜索历史都在。
            .addMigrations(MIGRATION_1_2)
            .build()

    /**
     * 已知坑（M1 才会暴露）：AggApi / MoonTvApi / M3uRepository / EpgRepository
     * 的 @Inject 构造函数带默认参数 `ioDispatcher = Dispatchers.IO`，Hilt 不会用
     * 默认值注入——这里显式 @Provides 构造，不留编译不过的注入。
     */
    @Provides
    @Singleton
    fun provideAggApi(client: OkHttpClient, json: Json): AggApi =
        AggApi(client, json, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideMoonTvApi(client: OkHttpClient, json: Json): MoonTvApi =
        MoonTvApi(client, json, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideM3uRepository(
        client: OkHttpClient,
        cacheStore: com.oriontv.feature.live.LiveCacheStore,
    ): M3uRepository =
        M3uRepository(client, cacheStore, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideEpgRepository(
        client: OkHttpClient,
        cacheStore: com.oriontv.feature.live.LiveCacheStore,
    ): EpgRepository =
        EpgRepository(client, cacheStore, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDetailRepository(
        moonTvApi: MoonTvApi,
        settingsRepository: com.oriontv.data.repo.SettingsRepository,
    ): com.oriontv.data.repo.DetailRepository =
        com.oriontv.data.repo.DetailRepository(moonTvApi, settingsRepository, Dispatchers.IO)

    /**
     * UpdateDownloader 的 @Inject 构造函数带默认参数 `ioDispatcher = Dispatchers.IO`，
     * Hilt 不会用默认值注入——这里显式 @Provides 构造（已知坑）。
     */
    @Provides
    @Singleton
    fun provideUpdateDownloader(
        client: OkHttpClient,
    ): com.oriontv.feature.update.UpdateDownloader =
        com.oriontv.feature.update.UpdateDownloader(client, Dispatchers.IO)
}

/** 进程内 CookieJar：按 host 存 cookie，过期自动丢弃。 */
class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        // 新值优先：新 cookie 在前，distinctBy 按 name+path+domain 去重时留新丢旧
        //（之前旧在前，服务端轮换 cookie 后这里一直留旧值，P1-6）。
        // B4-P2-6：去重键加 path/domain——同名不同 path 的 cookie 是两个
        // cookie，不能互相覆盖；读-改-写加 @Synchronized 防并发丢 cookie。
        val kept = (cookies + store[url.host].orEmpty())
            .filter { it.expiresAt > now }
            .distinctBy { Triple(it.name, it.path, it.domain) }
        store[url.host] = kept.toMutableList()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // 按 Cookie 自身的 path/domain 过滤（matches 含子域规则），不只按 host 取；
        // 全表扫描：服务端以父域下发的 cookie，在子域请求时也能带上。
        return store.values.flatten()
            .filter { it.expiresAt > now && it.matches(url) }
    }

    /** 退出登录时清掉全部会话 cookie（只清标记位等于没退，P0-4）。 */
    @Synchronized
    fun clear() {
        store.clear()
    }
}
