package com.oriontv.data.repo

import com.oriontv.core.util.AppLog
import com.oriontv.data.api.AuthApi
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.UnauthorizedException
import com.oriontv.data.local.CredentialStore
import com.oriontv.di.InMemoryCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 登录会话仓库（ui-design.md §5.8，照搬 RN authStore）：
 * 登录弹窗 + 自动续期 + 退出登录；凭据放 EncryptedSharedPreferences，
 * 登录标记位 authCookies 只存 "1"/""；真 cookie 在 OkHttp CookieJar 里。
 * 401 续期：用已保存凭据重登（并发去重，只跑一次）；失败才弹登录框。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val moonTvApi: AuthApi,
    private val settingsRepository: AuthSettings,
    private val credentialsStore: CredentialStore,
    private val cookieJar: InMemoryCookieJar,
) {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val restoreMutex = Mutex()
    /** 第 3 轮收尾 B-P2-17：在途恢复由各方共享（见 restoreSession）。 */
    private var restoreDeferred: Deferred<Boolean>? = null
    /** 恢复/配置重试用的后台作用域（与调用方生命周期解耦，共享恢复不随等待方取消）。 */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 第 3 轮收尾 B-P1-8：冷启动配置拉取失败后的一次延迟重试任务（只排一个）。 */
    private var configRetryJob: Job? = null
    /**
     * 第 6 轮 D6-P1-1/D6-P2-4：退出登录世代号——logout() 推进一次；
     * performRestore 成功落盘前复核，世代变了说明期间已退出（或退出后重登），
     * 不再碰任何登录态，防止在途恢复/延迟配置重试把已登出态悄悄置回 true。
     */
    private val logoutEpoch = AtomicInteger(0)
    /**
     * 第 7 轮 D7-P1-1：会话生命期内的“已明确退出”标志（内存布尔即可）。
     * 世代号只能拦“任务开始后才退出”，拦不住“退出后才启动的任务”——
     * 退出后才采样的世代恒等于退出后的世代，守卫恒通过。logout() 置 true；
     * 显式 login() 成功时清掉；performRestore 入口、scheduleConfigRetry
     * 排队/触发、401 回调三处先查标志，为 true 直接不跑恢复。
     * 进程重启后标志归 false，但标记位/凭据已随 logout 清掉，冷启动
     * 本来就不会恢复，无需持久化。
     */
    @Volatile
    private var loggedOut = false
    /**
     * 第 6 轮 D6-P2-5：401 续期回调触发次数——verifySession 藉此判断本次 401
     * 是否已试过静默重登，试过且失败就不再由 checkLoginStatus 重跑第二遍。
     */
    private val unauthorizedRefreshCount = AtomicInteger(0)
    /**
     * 第 6 轮 D6-P1-1：配置重试延迟（毫秒），默认 [CONFIG_RETRY_DELAY_MS]；
     * internal 可写只为单测能缩短等待，生产代码不动它。
     */
    internal var configRetryDelayMs: Long = CONFIG_RETRY_DELAY_MS
    /**
     * B10-P1-1：手动登录成功代次——login() 成功时推进一次；401 续期回调
     * 在恢复失败后凭它判断恢复期间是否有人手动登进去了（是则不弹登录框，
     * 否则把已过期会话的登录框弹出来；与 D7-P2-1“旧恢复不得清并发手动
     * 登录”同款竞态，只不过这里守的是弹窗不是登录态）。
     */
    private val manualLoginEpoch = AtomicInteger(0)
    /**
     * 第 8 轮 D8-P2-1：用户手动关闭过登录框的记忆（进程内，与 loggedOut 同款
     * 内存标志，无需持久化：进程重启后登录态本身走冷启动流程）。
     * 401 回调的自动弹框只在用户未明确拒绝过时才弹——否则过期会话下每次
     * 服务端调用 401 都会把刚关掉的框再弹出来。用户主动进登录入口
     * （showLogin）、登录成功、退出登录时清掉：重新明确要登录后，自动弹框恢复。
     */
    @Volatile
    private var loginDismissedByUser = false
    /**
     * 第 8 轮 D8-P2-6：登录态落盘与退出收尾的互斥——performRestore 的
     * “复核 + 置位”、login() 的“成功落盘”、logout() 的 finally 收尾，
     * 三段临界区二选一串行。只靠世代复核仍留窗口：logout 的 finally 可在
     * 复核通过后、置位前跑完，置位就把已退出的会话悄悄复活；互斥只包
     * 内存写 + 标记/凭据落盘，不跨网络调用（远程登出、登录 POST 都在锁外）。
     */
    private val loginStateMutex = Mutex()

    init {
        // MoonTvApi 的 401 续期回调：静默重登。
        // D7-P1-1 路径 3：logout() 不摘回调，退出后任意一次服务端调用 401
        // 都会经这里复登——先查已退出标志，为 true 直接回 false，不跑恢复。
        moonTvApi.onUnauthorized = {
            if (loggedOut) {
                false
            } else {
                unauthorizedRefreshCount.incrementAndGet()
                // B10-P1-1：续期失败即会话已过期，弹登录框——旧实现只由
                // execute() 抛 UnauthorizedException，各调用方只落错误文案，
                // 进程内再无登录入口（登录弹窗只在冷启动弹一次）。
                // 恢复期间手动登录成功会推进 manualLoginEpoch，那是新会话，
                // 不得把刚登进去的会话又弹回登录框；期间退出登录由 loggedOut 拦住。
                val epochBefore = manualLoginEpoch.get()
                val restored = restoreSession()
                // D8-P2-1：用户本会话已手动关过登录框时不再自动弹（见
                // shouldAutoShowLoginModal）；只在用户主动进登录入口时再弹。
                // B12-P2-9：检查-后-写竞态——上面复核与下面写态之间，手动登录
                // 可能插进来（回调跑在 IO 线程，窗口极窄但真实存在），把刚登
                // 进去的会话又写回未登录、弹回登录框；写态前再复核一次代次
                // 与已退出标志，变了就不再碰登录态与弹窗。注意：这里不能再
                // 拿 _isLoggedIn.value 当门控——401 时它本就是 true（正要
                // 登出的过期会话），门控只能靠代次与 loggedOut。
                if (shouldAutoShowLoginModal(restored, loggedOut, manualLoginEpoch.get() == epochBefore, loginDismissedByUser)) {
                    if (manualLoginEpoch.get() == epochBefore && !loggedOut) {
                        _isLoggedIn.value = false
                        _showLoginModal.value = true
                    }
                }
                restored
            }
        }
    }

    /** 登录：成功写标记位 + 存凭据（供下次自动续期），失败返回原因。 */
    suspend fun login(username: String, password: String): Result<Unit> {
        // B12-P2-1：DataStore 读失败即崩溃——这行原来在 try 块之外，上层
        // AuthViewModel.login() 又是裸 launch 无 try/catch；同文件 logout() /
        // checkLoginStatus() / performRestore() 三处同类读都包了 runOrDefault，
        // 唯独这里漏了。读失败按“未配置服务器地址”返回，不崩溃。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("还没有配置服务器地址"))
        // D8-P2-6：记下登录开始时的退出世代——登录在途时若用户点了退出，
        // 成功分支不再把会话写回去（见下面的互斥 + 世代复核）。
        val epochAtStart = logoutEpoch.get()
        _loginError.value = null
        return try {
            when (val result = moonTvApi.login(baseUrl, username, password)) {
                MoonTvApi.LoginResult.Success -> {
                    // D8-P2-6：成功落盘与退出登录的收尾互斥 + 世代复核——
                    // 登录 POST 在途时用户点了退出，logout() 的 finally 已把
                    // 会话清干净，旧实现会在此把 _isLoggedIn 悄悄置回 true
                    // （还把刚清掉的凭据/标记重存一遍）；世代变了即不申请，
                    // 返回失败（不设 _loginError：是用户自己的退出取消了
                    // 本次登录，没什么要提示的；登录框已由退出关掉）。
                    // 互斥只串行化“落盘/收尾”两段临界区，不跨网络调用，
                    // 不会把登录 POST 堵在退出后面。
                    val applied = loginStateMutex.withLock {
                        // 只凭世代号：退出后重登时 loggedOut 仍为 true（正是本次
                        // 登录要清掉的旧态），不能拿它当“期间有人退出”的证据；
                        // 入口处采样的世代是分界线——登录在途中的退出一定推进它。
                        if (logoutEpoch.get() != epochAtStart) {
                            false
                        } else {
                            // D-P2-17：凭据保存失败（Keystore 异常）不再误报“登录失败”——
                            // 服务端会话已建、Cookie 已在 Jar 里，本次登录成功；下次冷启动
                            // 静默重登会失败、再提示登录，日志里留一条可见记录。
                            // B13-P2-3：取消异常不混同普通失败——runCatching 会吞掉
                            // CancellationException，登录被取消后仍走到下面置
                            // _isLoggedIn = true，把“已取消的登录”标成已登录；CE 原样
                            // 重抛，其他失败只记日志。
                            try {
                                credentialsStore.save(username, password)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.w("AuthRepository", "登录成功但凭据保存失败，下次冷启动需重新登录：${e.message}")
                            }
                            // B6-P2-5：setAuthCookies 抛错不再报成“登录失败”——服务端会话
                            // 已建、Cookie 已在 Jar 里，本次登录成功；标记丢了只影响下次
                            // 冷启动静默恢复（会再提示登录），与凭据保存失败同款处理。
                            // B13-P2-3：同上，取消异常原样重抛，不把已取消的登录标成已登录。
                            try {
                                settingsRepository.setAuthCookies("1")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.w("AuthRepository", "登录成功但登录标记保存失败，下次冷启动需重新登录：${e.message}")
                            }
                            // D7-P1-1：显式登录成功清掉已退出标志——退出后重登不受影响，
                            // 在途旧恢复的世代复核仍以 logoutEpoch 为准，不被本标志误拦。
                            loggedOut = false
                            // B10-P1-1：手动登录成功推进代次——401 续期回调凭它判断
                            // 恢复期间是否有人手动登进去了（是则不弹登录框）。
                            manualLoginEpoch.incrementAndGet()
                            // D8-P2-1：登录成功后，之前的手动关闭记忆失效——
                            // 新会话过期时自动弹框恢复。
                            loginDismissedByUser = false
                            _isLoggedIn.value = true
                            _showLoginModal.value = false
                            true
                        }
                    }
                    if (applied) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("登录已被取消（登录期间已退出登录）"))
                    }
                }
                // B4-P1-2：只有 401（或 2xx 但 ok=false）才报“账号或密码错误”。
                MoonTvApi.LoginResult.WrongCredentials -> {
                    _loginError.value = "账号或密码错误"
                    Result.failure(IllegalStateException("账号或密码错误"))
                }
                // 5xx/超时/网络错：MoonTvApi 内已重试一次，仍失败则报网络类错误，不误导改密码。
                is MoonTvApi.LoginResult.Transient -> {
                    val message = "登录失败：${result.cause.message ?: "网络错误"}，请检查网络后重试"
                    _loginError.value = message
                    Result.failure(IllegalStateException(message, result.cause))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "登录失败：${e.message ?: "网络错误"}"
            _loginError.value = message
            Result.failure(IllegalStateException(message, e))
        }
    }

    /**
     * 退出登录：远程登出失败也不留会话残留（finally 无条件清）。
     * 只清标记位等于没退：已保存凭据还在，重启后静默重登会直接登回去；
     * CookieJar 里的会话 cookie 也还在（P0-4）。三处都要清。
     */
    suspend fun logout() {
        // D6-P1-1/D6-P2-4：退出即作废一切在途/待跑的恢复——先取消配置重试任务、
        // 推进退出世代号（performRestore 成功落盘前复核），再走远程登出；
        // 否则 31 秒内退出会被冷启动配置重试悄悄复登，或被在途恢复置回 true。
        // D7-P1-1：先置已退出标志——退出后才启动的恢复（立即恢复 / 31 秒延迟
        // 重试 / 401 回调）采样到的世代已是退出后的，世代守卫拦不住，只能靠标志。
        // D8-P2-1：退出登录即开启新会话周期，手动关闭记忆随之失效。
        loggedOut = true
        loginDismissedByUser = false
        configRetryJob?.cancel()
        configRetryJob = null
        logoutEpoch.incrementAndGet()
        // B5-P2-3：DataStore 读失败按无地址处理，不让冷启动/登出崩溃。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        try {
            if (baseUrl.isNotBlank()) moonTvApi.logout(baseUrl)
        } finally {
            // D8-P2-6：收尾清态与 performRestore/login 的落盘互斥（见
            // loginStateMutex）——在途恢复/登录的置位若落在收尾之后，会把
            // 已退出的会话悄悄复活；互斥后两段临界区二选一，复活无窗口。
            // 远程登出调用本身在锁外，不堵网络。
            // B13-P2-3：取消异常不混同普通失败——同 login() 的两处，CE 原样重抛、
            // 不当成普通失败吞掉；但登出收尾（清凭据/清 Jar/清标记/落未登录态）
            // 本就应在取消时也跑完，故先记下 CE、跑完收尾再重抛，不让第一步
            // 的取消截断后面的清理。
            // D9-P2-3：B13-P2-3 只管了锁内的 CE——等锁时被取消（logout 协程
            // 在切页时被取消、锁正被在途登录/恢复持有），withLock 直接抛 CE，
            // 块内清理一行都不跑，退出“假成功”（凭据/Jar/标记都在、_isLoggedIn
            // 仍为 true，下次冷启动见标记又去静默重登）。收尾包进
            // withContext(NonCancellable)：等锁不再可取消，清理一定跑完；
            // 跑完后再把取消信号还给调用方（ensureActive，B13-P2-3 同款
            // “记下 CE、跑完收尾再重抛”在锁外同样适用）。
            withContext(NonCancellable) {
                loginStateMutex.withLock {
                    var cancelled: CancellationException? = null
                    // B-P2-18：clear() 已是 suspend，runCatching 的非 suspend 块里调不了，改 try/catch。
                    try { credentialsStore.clear() } catch (e: CancellationException) { cancelled = e } catch (_: Exception) { }
                    runCatching { cookieJar.clear() }
                    // B5-P2-3：标记清除失败也不拦登出收尾（_isLoggedIn 必须落 false）。
                    try { settingsRepository.setAuthCookies("") } catch (e: CancellationException) { cancelled = e } catch (_: Exception) { }
                    _isLoggedIn.value = false
                    cancelled?.let { throw it }
                }
            }
            coroutineContext.ensureActive()
        }
    }

    /**
     * 冷启动检查：无地址 → 未登录；有登录标记 → 先轻量校验会话（B-P2-16），
     * 有效才算已登录，过期走静默重登；
     * 无标记 → 有保存凭据就静默重登（localstorage 无密码服务传空密码也算一次尝试），
     * 重登失败才弹登录框。
     *
     * 注：第 5 轮 D-P2-7（先读标记、server-config 放后台刷新）已被 B-P1-8 的设计取代：
     * 同步拉一次配置（30 秒失败缓存）+ 失败时 31 秒后延迟重试，不再双通道刷新。
     */
    suspend fun checkLoginStatus() {
        // D6-P2-4：函数入口记一次退出世代，期间退出登录后不再碰登录态/弹窗。
        val epochAtStart = logoutEpoch.get()
        // B5-P2-3：DataStore 读失败按未配置/未拉取处理，冷启动不崩溃；
        // 取消原样抛出（runOrDefault 内处理）。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        if (baseUrl.isBlank()) {
            _isLoggedIn.value = false
            _showLoginModal.value = false
            return
        }
        val config = runOrDefault(null) { settingsRepository.refreshServerConfig() }
        // B-P1-8：冷启动拉取失败且 StorageType 仍未知时，31 秒后（越过 30 秒
        // 失败缓存）再试一次，不把进程钉死在本地模式。
        if (config == null && runOrDefault(false) { settingsRepository.isServerConfigUnknown() }) {
            scheduleConfigRetry()
        }
        val marker = runOrDefault("") { settingsRepository.currentAuthCookies() }
        if (marker.isNotBlank()) {
            val check = verifySession(baseUrl)
            // D6-P2-4：校验在途时用户点了退出登录：校验结果已过期，不再据此置态。
            if (logoutEpoch.get() != epochAtStart) return
            when (check) {
                SessionCheck.VALID -> {
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    return
                }
                SessionCheck.UNREACHABLE -> {
                    // 服务端暂时不可达：无法判定会话，沿用标记位已登录，
                    // 不因一次网络抖动弹登录框。
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    return
                }
                SessionCheck.EXPIRED_REFRESH_TRIED -> {
                    // D6-P2-5：401 回调里已试过一次静默重登且失败，不再重跑
                    // 第二遍登录 POST，直接弹登录框。
                    _isLoggedIn.value = false
                    _showLoginModal.value = true
                    return
                }
                SessionCheck.EXPIRED -> {
                    // 掉到下面的静默重登。
                }
            }
        }
        val restored = restoreSession()
        // D6-P2-4：恢复在途时用户点了退出登录：不再按恢复结果改登录态/弹窗。
        if (logoutEpoch.get() != epochAtStart) return
        if (!restored) {
            _isLoggedIn.value = false
            _showLoginModal.value = true
        }
    }

    /** B-P1-8：冷启动配置失败后的一次延迟重试（31 秒，越过 30 秒失败缓存）。 */
    private fun scheduleConfigRetry() {
        // D7-P1-1 路径 2：退出后才排队——排队时世代已是退出后的，世代守卫
        // 恒通过；已退出时根本不排队。
        if (loggedOut) return
        if (configRetryJob?.isActive == true) return
        // D6-P1-1：记下排队时的退出世代；重试触发时世代变了（期间已退出登录，
        // 甚至退出后重登），不再重跑恢复——logout() 也会直接取消本任务，这里是
        // 第二道防线（任务排队后、触发前世代变化的竞态）。
        val epochAtSchedule = logoutEpoch.get()
        configRetryJob = restoreScope.launch {
            delay(configRetryDelayMs)
            // D7-P1-1：触发时再查一次已退出标志——logout() 取消任务与世代
            // 复核之间仍有窗口，标志是最后一道门。
            if (loggedOut) return@launch
            if (logoutEpoch.get() != epochAtSchedule) return@launch
            // B4-P2-17：31 秒后配置重拉成功时重跑登录恢复——否则冷启动时
            // 配置未到、restoreSession 已失败，登录弹窗一直滞留；
            // 重跑成功时 performRestore 会把弹窗关掉，失败则弹窗继续留着。
            val refreshed = runCatching { settingsRepository.refreshServerConfig() }
                // B17-P2-1：取消异常原样重抛，不当成配置重拉失败回落空。
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (refreshed != null && !loggedOut && logoutEpoch.get() == epochAtSchedule) {
                restoreSession()
            }
        }
    }

    /**
     * B-P2-16：冷启动轻量会话校验（复用已有的 getFavorites，不新增服务端接口）。
     * 401 续期回调里已试过一次静默重登，仍 401 即会话过期；网络错则算不可达。
     * D6-P2-5：回调是否在本次校验里触发过，用计数区分——触发过说明重登已试且
     * 失败，调用方不再重跑第二遍（返回 [SessionCheck.EXPIRED_REFRESH_TRIED]）。
     */
    private suspend fun verifySession(baseUrl: String): SessionCheck {
        val refreshCountBefore = unauthorizedRefreshCount.get()
        return try {
            moonTvApi.getFavorites(baseUrl)
            SessionCheck.VALID
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnauthorizedException) {
            if (unauthorizedRefreshCount.get() > refreshCountBefore) {
                SessionCheck.EXPIRED_REFRESH_TRIED
            } else {
                SessionCheck.EXPIRED
            }
        } catch (_: Exception) {
            SessionCheck.UNREACHABLE
        }
    }

    /** 会话校验结果：有效 / 已过期 / 已过期且续期已试过 / 服务端不可达（无法判定）。 */
    private enum class SessionCheck { VALID, EXPIRED, EXPIRED_REFRESH_TRIED, UNREACHABLE }

    /**
     * 用已保存凭据静默重登；无凭据时对无密码服务试一次空登录。
     * 第 3 轮收尾 B-P2-17：并发 401 不再各回各的旧值——在途恢复由 Deferred 共享，
     * 等待方挂起等恢复完成后再取结果；重入（401 回调发生在恢复自身的 login 里）
     * 直接回当前值，不自等自己死锁。
     */
    suspend fun restoreSession(): Boolean {
        val deferred = restoreMutex.withLock {
            val active = restoreDeferred?.takeIf { it.isActive }
            if (active != null) {
                if (active === coroutineContext[Job]) return _isLoggedIn.value
                return@withLock active
            }
            restoreScope.async { performRestore() }.also { restoreDeferred = it }
        }
        return deferred.await()
    }

    private suspend fun performRestore(): Boolean {
        // D6-P2-4：记下恢复开始时的退出世代；成功落盘前复核，世代变了说明
        // 期间已退出登录（或退出后重登），一律不碰登录态，直接返回未恢复。
        // D7-P1-1 路径 1：退出后才启动的恢复——采到的世代已是退出后的，
        // 世代复核恒通过；入口先查已退出标志，为 true 直接返回，连登录
        // POST 都不发（无密码服务上空登录本会成功）。
        if (loggedOut) return false
        val epochAtStart = logoutEpoch.get()
        // B5-P2-3：同 checkLoginStatus，DataStore 读失败按未恢复处理。
        val baseUrl = runOrDefault("") { settingsRepository.currentApiBaseUrl() }
        if (baseUrl.isBlank()) return false
        val saved = try { credentialsStore.load() } catch (_: Exception) { null }
        val ok = try {
            // B4-P1-2：静默恢复只认 Success；Transient/WrongCredentials 都算未恢复。
            moonTvApi.login(baseUrl, saved?.first, saved?.second) is MoonTvApi.LoginResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (logoutEpoch.get() != epochAtStart || loggedOut) {
            // D7-P1-2：登录响应到达时 OkHttp 已把新 Set-Cookie 写回 Jar——
            // 世代不匹配只拦住了状态写，Jar 里仍留着活的会话 cookie；
            // 仍是已退出态时清掉本次登录种下的 cookie（见 scrubStaleRestoreSession）。
            // D9-P2-2：这处 scrub 原来在 loginStateMutex 外（D8-P2-6 只把复核+
            // 置位、登录落盘、退出收尾三段放进锁，这条调用漏了）——scrub 的唯一
            // 守卫是 _isLoggedIn，而 login() 在互斥块内先写凭据/标记、最后才
            // 置 _isLoggedIn=true；锁外检查时手动登录可能刚写完凭据/标记、尚
            // 未置位，scrub 会清掉新会话的 cookie 与标记。放进锁内、复核后
            // 再 scrub（与 :494 第二处调用点同款），窗口关闭。
            loginStateMutex.withLock {
                if (logoutEpoch.get() != epochAtStart || loggedOut) {
                    scrubStaleRestoreSession()
                }
            }
            return false
        }
        return if (ok) {
            // B5-P2-3：标记写失败不推翻已建好的会话（本次登录成功），只记日志；
            // 下次冷启动静默重登会失败、再提示登录。
            try {
                settingsRepository.setAuthCookies("1")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("AuthRepository", "静默恢复成功但登录标记保存失败，下次冷启动需重新登录：${e.message}")
            }
            // B8-P1-1：写标记后、置位前再复核一次世代——登出若插在上面
            // 的检查与写态之间，旧实现会把已退出的会话悄悄复活（置
            // _isLoggedIn=true、关登录弹窗）；世代变了即不碰登录态。
            // D8-P2-6：复核与置位放进 loginStateMutex，与 logout() 的收尾
            // 互斥——只复核不加锁仍留一个窗口（logout 的 finally 在复核通过
            // 后、置位前跑完，置位就把已退出的会话复活）；加锁后两段临界区
            // 二选一，复活无窗口。锁内只做复核 + 置位 + 必要的 scrub，
            // 不跨网络调用。
            loginStateMutex.withLock {
                if (logoutEpoch.get() != epochAtStart || loggedOut) {
                    // D7-P1-2：同上——标记位刚被本次恢复写回 "1"，仍是已退出态
                    // 时一并复位，否则下次冷启动见标记又去静默重登。
                    scrubStaleRestoreSession()
                    false
                } else {
                    _isLoggedIn.value = true
                    _showLoginModal.value = false
                    true
                }
            }
        } else {
            // D7-P2-1：失败分支只在仍是未登录态时才写 false——并发手动登录
            // 成功后（_isLoggedIn 已为 true），本次旧恢复的失败不得把它清掉。
            if (!_isLoggedIn.value) {
                _isLoggedIn.value = false
            }
            false
        }
    }

    /**
     * D7-P1-2：过期世代的恢复不得留下会话残留——登录响应到达时 OkHttp 已把
     * 新 Set-Cookie 写进 Jar（logout() 的 finally 清 Jar 在响应到达之前），
     * 成功分支还可能已把登录标记写回 "1"；仍是已退出态时清 Jar 并把标记
     * 复位，退出保持干净。
     * 以 [_isLoggedIn] 为条件：退出后手动重登的新会话 cookie/标记不得误删
     * （手动登录不推进世代，世代复核拦不住这一种，只能靠登录态判断）。
     * 不取消在途的 restoreDeferred：取消会让 restoreSession() 的等待方
     * （checkLoginStatus / execute 的 401 分支）收到 CancellationException
     * 而非 false，改的是恢复的等待契约；清 Jar 已足以堵住污染。
     */
    private suspend fun scrubStaleRestoreSession() {
        if (_isLoggedIn.value) return
        runCatching { cookieJar.clear() }
        try {
            settingsRepository.setAuthCookies("")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 标记复位失败也不拦收尾：下次冷启动凭据已清，恢复仍会失败、弹登录框。
        }
    }

    /**
     * B5-P2-3：DataStore 读写失败回落默认值，协程取消原样抛出
     *（同 Repositories.runOrDefault，那个是 private、这里各写一份）。
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

    companion object {
        /** B-P1-8：冷启动配置延迟重试间隔（越过 SettingsRepository 的 30 秒失败缓存）。 */
        const val CONFIG_RETRY_DELAY_MS = 31_000L
    }

    fun showLogin() {
        // D8-P2-1：用户主动进登录入口（设置页换地址、登录入口）——重新明确
        // 要登录，之前的手动关闭记忆失效，自动弹框恢复。
        loginDismissedByUser = false
        _loginError.value = null
        _showLoginModal.value = true
    }

    fun hideLogin() {
        // D8-P2-1：用户手动关闭登录框——记下来，本会话内 401 回调不再
        // 自动弹框（只在用户主动进登录入口时再弹）。
        loginDismissedByUser = true
        _showLoginModal.value = false
    }
}

/**
 * 第 8 轮 D8-P2-1：401 续期失败后是否自动弹登录框（纯逻辑，可单测）——
 * 恢复成功 / 已退出 / 恢复期间有人手动登入 / 用户本会话已手动关过框，
 * 四种情况都不自动弹；只在“恢复失败且无人接管、用户也没明确拒绝”时弹。
 */
internal fun shouldAutoShowLoginModal(
    restored: Boolean,
    loggedOut: Boolean,
    epochUnchanged: Boolean,
    dismissedByUser: Boolean,
): Boolean = !restored && !loggedOut && epochUnchanged && !dismissedByUser
