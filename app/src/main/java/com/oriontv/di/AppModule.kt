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
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/** 应用级协程作用域（D-P2-7/D-P1-6）：后台刷新 server-config、RN 首启迁移等不阻塞 UI 的一次性任务；随进程存活，不取消。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
)
annotation class ApplicationScope

/** Hilt 单例模块：网络、序列化、数据库。 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * D-P1-4：AuthRepository 只依赖三个小接口（AuthApi / AuthSettings /
     * CredentialStore），这里绑到具体实现；单测用内存假实现替换。
     */
    @Provides
    @Singleton
    fun provideAuthApi(api: MoonTvApi): com.oriontv.data.api.AuthApi = api

    @Provides
    @Singleton
    fun provideAuthSettings(repo: com.oriontv.data.repo.SettingsRepository): com.oriontv.data.repo.AuthSettings = repo

    @Provides
    @Singleton
    fun provideCredentialStore(store: com.oriontv.data.local.CredentialsStore): com.oriontv.data.local.CredentialStore = store

    /**
     * 第 75 轮：内置 MoonTV 账号——密码由构建时注入（BuildConfig.MOONTV_BUILTIN_PASSWORD，
     * 见 app/build.gradle.kts：-PmoontvPassword / MOONTV_PASSWORD 环境变量 /
     * local.properties 的 moontv.password；都没有时为空串，内置登录关闭）。
     * 用户名与地址是常量（见 BuiltinAccount）。密码只在这里读一次进内存，
     * 不写进任何日志与文件。
     */
    @Provides
    @Singleton
    fun provideBuiltinAccount(): com.oriontv.data.repo.BuiltinAccount =
        com.oriontv.data.repo.BuiltinAccount.fromBuildConfig(
            com.oriontv.BuildConfig.MOONTV_BUILTIN_PASSWORD,
        )

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
     * 已知坑：AggApi / MoonTvApi / M3uRepository / EpgRepository
     * 的 @Inject 构造函数带默认参数 `ioDispatcher = Dispatchers.IO`，Hilt 不会用
     * 默认值注入——这里显式 @Provides 构造，不留编译不过的注入。
     */
    @Provides
    @Singleton
    fun provideAggApi(client: OkHttpClient, json: Json): AggApi =
        AggApi(client, json, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideAssrtApi(client: OkHttpClient, json: Json): com.oriontv.data.api.AssrtApi =
        com.oriontv.data.api.AssrtApi(client, json, Dispatchers.IO)

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
        aggApi: com.oriontv.data.api.AggApi,
    ): com.oriontv.data.repo.DetailRepository =
        com.oriontv.data.repo.DetailRepository(moonTvApi, settingsRepository, aggApi, Dispatchers.IO)

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

    // D-P2-6：读-改-写复合操作加锁（ConcurrentHashMap 只保证单步原子，
    // 两路并发响应会丢一批 cookie）；去重按 name+domain+path，
    // 同名不同 path 的 cookie 不再被误杀。
    // 第 6 轮 D6-P2-6：去重放全表做——父域 cookie 经两个子域各存一份时，
    // 旧实现只与本 host 桶内去重，loadForRequest 全表扫描会把同一 cookie
    // 发两遍；过期条目在读写两端都清理，不再永久残留。
    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        // 先清全表过期条目，再把与新 cookie 同键（name+path+domain）的旧条目
        // 从所有桶里删掉——父域 cookie 换子域保存时，旧桶里的副本不再残留。
        val newKeys = cookies.map { Triple(it.name, it.path, it.domain) }.toSet()
        store.values.forEach { bucket ->
            bucket.removeAll { it.expiresAt <= now || Triple(it.name, it.path, it.domain) in newKeys }
        }
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
        // D6-P2-6：读时顺手清掉全表过期条目（过期 cookie 不再永久残留）。
        store.values.forEach { bucket -> bucket.removeAll { it.expiresAt <= now } }
        // 按 Cookie 自身的 path/domain 过滤（matches 含子域规则），不只按 host 取；
        // 全表扫描：服务端以父域下发的 cookie，在子域请求时也能带上。
        // 同一 cookie 只发一遍：全表去重（name+path+domain），新值优先。
        return store.values.flatten()
            .filter { it.expiresAt > now && it.matches(url) }
            .distinctBy { Triple(it.name, it.path, it.domain) }
    }

    /** 退出登录时清掉全部会话 cookie（只清标记位等于没退，P0-4）。 */
    @Synchronized
    fun clear() {
        store.clear()
    }
}
