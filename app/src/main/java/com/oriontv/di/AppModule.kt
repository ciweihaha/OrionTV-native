package com.oriontv.di

import android.content.Context
import androidx.room.Room
import com.oriontv.data.api.AggApi
import com.oriontv.data.api.ApiClient
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.local.AppDatabase
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
     */
    @Provides
    @Singleton
    fun provideCookieJar(): CookieJar = InMemoryCookieJar()

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: CookieJar): OkHttpClient =
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
            .fallbackToDestructiveMigration()
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
}

/** 进程内 CookieJar：按 host 存 cookie，过期自动丢弃。 */
class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val kept = (store[url.host].orEmpty() + cookies)
            .filter { it.expiresAt > now }
            .distinctBy { it.name }
        store[url.host] = kept.toMutableList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}
