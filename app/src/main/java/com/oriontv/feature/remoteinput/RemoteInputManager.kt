package com.oriontv.feature.remoteinput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 远程输入服务状态（供设置页显示：运行中绿字/异常红字）。 */
data class RemoteInputState(
    val isRunning: Boolean = false,
    val serverUrl: String? = null,
    val pairingCode: String? = null,
    val error: String? = null,
    /** 上一条收到的远程文本（内容相同也靠时间戳感知变化）。 */
    val lastMessage: String? = null,
    val lastMessageAt: Long? = null,
    /** 当前配对码剩余有效秒数（P2-8：轮换任务每 5 秒刷新一次，供界面倒计时；null = 未签发）。 */
    val pairingCodeRemainingSeconds: Int? = null,
)

/**
 * 远程输入管理器（RN remoteControlStore.ts 对照）：
 * 随设置开关启停 12346 服务；启动成功后轮换配对码；启动中并发调用共用
 * 同一次启动；启动期间被关掉则完成后立刻停掉；用户最新意图（开/关）为准。
 * 服务异常时 state.error 置红字原因，不静默失败。
 */
@Singleton
class RemoteInputManager @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    /**
     * B6-P2-1：启停共享状态的同步——stopLocked() 只能在持 startMutex 时进
     * （stop() 的排队协程、start() 的停服分支），release() 是单线程退出
     * 路径；再加 synchronized 兜底，防住 start() 锁外路径与 release()
     * 并发重入（两次 server.stop()/轮换任务取消交错）。
     */
    private val stopLock = Any()
    private val router = RemoteInputRouter(
        onMessage = { message ->
            scope.launch {
                _state.update {
                    it.copy(lastMessage = message, lastMessageAt = System.currentTimeMillis())
                }
                // B9-P2-5：缓冲改为 1，tryEmit 失败（连续多条且缓冲已满）时不补发——
                // state.lastMessage 永远是最新一条，订阅者以 state 为准（见
                // resolveRemoteMessageToSearch），丢的只是中间态，不是最新意图。
                // 旧 P2-13 的“0 缓冲直接丢弃防误触发”由消费端 5 分钟新鲜度过滤接替。
                _messages.tryEmit(message)
            }
        },
        onHandshake = {
            // B8-P2-5：握手事件改 tryEmit——旧实现 scope.launch + 可挂起 emit，
            // 无人订阅时第 5 次握手起协程永久挂起泄漏（_handshakes 缓冲只有 4）。
            _handshakes.tryEmit(Unit)
        },
    )

    private val _state = MutableStateFlow(RemoteInputState())
    val state: StateFlow<RemoteInputState> = _state.asStateFlow()

    /** 远程文本流（搜索页订阅：收到即填入搜索框并搜索）。 */
    // B9-P2-5：缓冲改为 1——0 缓冲 + tryEmit 时，订阅者正在处理上一条的极小
    // 窗口内连续第二条会被丢弃；1 条缓冲接住它。无订阅者时滞留的旧消息由消费端
    // 按 lastMessageAt 做 5 分钟新鲜度过滤（resolveRemoteMessageToSearch），
    // 不再靠“0 缓冲直接丢弃”防误触发（旧 P2-13 注释已过时）。
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 配对成功事件流（扫码弹窗订阅：配对成功后自动关闭）。 */
    private val _handshakes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val handshakes: SharedFlow<Unit> = _handshakes.asSharedFlow()

    // B6-P2-1：启停共享状态跨线程读写（start() 是 suspend、stop() 调
    // scope.launch、轮换任务读 desiredRunning），plain var 有可见性问题，
    // 改 @Volatile；临界区本身仍由 startMutex/stopLock 守。
    @Volatile
    private var server: RemoteInputServer? = null
    @Volatile
    private var starting = false
    @Volatile
    private var stopRequestedDuringStart = false
    @Volatile
    private var desiredRunning = false
    /** 配对码定期轮换任务（P1-10：每 5 分钟换一次码并刷新 state 里的显示）。 */
    // B8-P2-6：同文件其余共享状态都已是 @Volatile，唯独 rotationJob 漏了——
    // start/stop 在不同线程读写，漏写可见性，后台线程可能看到过期引用。
    @Volatile
    private var rotationJob: Job? = null

    /** 启动服务；已在跑时直接返回；并发启动共用同一次。 */
    suspend fun start() {
        desiredRunning = true
        startMutex.withLock {
            if (_state.value.isRunning) return
            if (starting) return
            starting = true
        }
        // B-P1-9：取消不重启（finally 里的重起只针对正常失败路径）。
        var cancelled = false
        // B6-P2-1：本轮启动的 server（局部变量）——发布到共享字段前先在
        // startMutex 内与停服标志一起复核，见下面的发布点。
        var startedServer: RemoteInputServer? = null
        // B7-P1-1：兜底变量——取消恰好落在 newServer.start() 期间时
        // startedServer 尚未赋值，但端口可能已绑上，CE catch 里凭它停服。
        var pendingServer: RemoteInputServer? = null
        try {
            // B4-P2-8：网络枚举 + 端口绑定是阻塞操作，切 IO 线程做，不占主线程。
            val ip = withContext(Dispatchers.IO) { RemoteInputServer.localIpAddress() }
            if (ip == null) {
                throw IllegalStateException("无法获取 IP 地址，请确认设备已连接到 Wi-Fi 或以太网。")
            }
            val newServer = RemoteInputServer(router)
            pendingServer = newServer
            withContext(Dispatchers.IO) { newServer.start() }
            startedServer = newServer
            val code = router.rotatePairingCode()
            // B6-P2-1：发布 server 与读停服标志放同一把锁里——之前 server 在
            // 锁外赋值，stop() 的停服协程可能刚把 server 置 null，随后这里
            // 又把已启动的 server 赋回去，服务漏跑、状态却显示已停。
            val stopNow = startMutex.withLock {
                server = startedServer
                stopRequestedDuringStart
            }
            if (stopNow) {
                startMutex.withLock { stopLocked() }
                return
            }
            startRotationJob()
            _state.update {
                it.copy(
                    isRunning = true,
                    serverUrl = "http://$ip:${RemoteInputService.PORT}",
                    pairingCode = code,
                    error = null,
                    pairingCodeRemainingSeconds =
                        (RemoteInputLogic.PAIRING_CODE_TTL_MS / 1000).toInt(),
                )
            }
        } catch (e: CancellationException) {
            // B-P1-9：取消原样抛出，不写 error 态、不按“失败”重起。
            // B7-P1-1：取消分支也要停掉已启动的 server——之前只重抛，server
            // 占着 12346 端口泄漏，后续再启动必报端口占用、直至进程重启都
            // 不可用。pendingServer 兜底取消落在 start() 期间的窗口（此时
            // startedServer 还没赋值，但端口可能已绑上）。
            // 清理走 NonCancellable：本协程已取消，普通 withLock 会立刻再抛 CE。
            cancelled = true
            val leaked = startedServer ?: pendingServer
            runCatching { leaked?.stop() }
            runCatching {
                withContext(NonCancellable) {
                    startMutex.withLock { if (server === leaked) server = null }
                }
            }
            router.clearPairingCode()
            throw e
        } catch (e: Exception) {
            if (!stopRequestedDuringStart) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        serverUrl = null,
                        pairingCode = null,
                        error = "远程输入启动失败：${e.message ?: "端口 ${RemoteInputService.PORT} 被占用或无网络"}",
                    )
                }
            }
            // B6-P2-1：只停本轮起的 server（局部变量），不碰共享字段里
            // 可能已是新一轮的 server；共享字段只在仍是本轮 server 时才清。
            // B7-P1-1：同上，pendingServer 兜底 start() 期间的窗口。
            val failedServer = startedServer ?: pendingServer
            runCatching { failedServer?.stop() }
            startMutex.withLock { if (server === failedServer) server = null }
            router.clearPairingCode()
        } finally {
            // B7-P1-1：取消路径下本协程已取消，普通 withLock 会立刻再抛 CE、
            // starting 永远不清、之后 start() 恒早退——清理走 NonCancellable。
            runCatching {
                withContext(NonCancellable) {
                    startMutex.withLock {
                        starting = false
                        // B14-P2-6：先读停服标志再清零——停服请求落在发布点
                        // 之后、finally 清零之前时，旧实现无条件清掉、请求被丢，
                        // 服务漏跑（stop() 的协程在 IO 线程，发布点读标志与
                        // finally 之间虽无挂起点、但跨线程可交错，finally 里
                        // withContext 的挂起点更放大了窗口）；为真时补一次
                        // stopLocked()。stop() 那路只置了标志、并未停服，不会
                        // 重复停；stopLocked() 幂等，持 startMutex 调符合其约定。
                        val stopNow = stopRequestedDuringStart
                        stopRequestedDuringStart = false
                        if (stopNow) stopLocked()
                    }
                }
            }
            // B11-P2-3（已核实：不是死代码，别删）：启动期间用户又开了时
            // 按最新意图重起一次（取消不重起）。可达路径是“关→开”发生在
            // 启动进行中：stop() 已置 desiredRunning=false 并让停服协程把
            // stopRequestedDuringStart 置真，随后 start() 又把 desiredRunning
            // 置回 true、因 starting 为真直接返回；本轮启动随后失败
            //（catch 里因 stopRequestedDuringStart 为真不写 error）或走
            // stopNow 分支停服，finally 里 desiredRunning 为真、isRunning
            // 为假、error 为空，重起是唯一能兑现这次“开”意图的路径——
            // stop() 的意图复核只管“不该停时不停”，不管重起。正常成功/
            // 正常失败/取消时条件恒假，不会多余重起。
            if (!cancelled && desiredRunning && !_state.value.isRunning && _state.value.error == null) {
                start()
            }
        }
    }

    /** 停止服务；启动中调用则等启动完成后立刻停。 */
    fun stop() {
        desiredRunning = false
        scope.launch {
            startMutex.withLock {
                if (starting) {
                    stopRequestedDuringStart = true
                    return@withLock
                }
                // B5-P1-5：停服协程排队期间用户可能已重新打开（desiredRunning
                // 又为 true）；进锁后按最新意图复核，已重开就不再停服，
                // 否则服务停在关态、开关却显示开。
                if (desiredRunning) return@withLock
                stopLocked()
            }
        }
    }

    /** 同步停服（供 stop() 内部与启动后立刻停的路径用，调用方须已持锁或在单线程路径）。 */
    private fun stopLocked() {
        // B6-P2-1：synchronized 兜底并发重入——正常路径调用方已持 startMutex，
        // 但 release() 是锁外直接调，并发时两次 stopLocked 会交错停 server、
        // 取消轮换任务；锁内保证一次只跑一个停服。
        synchronized(stopLock) {
            rotationJob?.cancel()
            rotationJob = null
            runCatching { server?.stop() }
            server = null
            router.clearPairingCode()
            _state.update {
                it.copy(
                    isRunning = false,
                    serverUrl = null,
                    pairingCode = null,
                    error = null,
                    pairingCodeRemainingSeconds = null,
                )
            }
        }
    }

    /** P1-10/P2-8：配对码每 5 分钟轮换一次，state 里的显示同步更新；停服即停。
     * 每 5 秒刷新一次剩余秒数（供界面倒计时）；轮换后上一轮的码仍有 60 秒
     * 宽限期（见 RemoteInputRouter.matchesPairingCode）。 */
    private fun startRotationJob() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            while (desiredRunning) {
                delay(RemoteInputLogic.PAIRING_CODE_COUNTDOWN_TICK_MS)
                if (!desiredRunning || !_state.value.isRunning) break
                val issuedAt = router.pairingCodeIssuedAtMs
                if (issuedAt <= 0) continue
                val now = System.currentTimeMillis()
                if (now - issuedAt >= RemoteInputLogic.PAIRING_CODE_TTL_MS) {
                    val code = router.rotatePairingCode()
                    _state.update {
                        it.copy(
                            pairingCode = code,
                            pairingCodeRemainingSeconds =
                                (RemoteInputLogic.PAIRING_CODE_TTL_MS / 1000).toInt(),
                        )
                    }
                } else {
                    val remaining =
                        ((RemoteInputLogic.PAIRING_CODE_TTL_MS - (now - issuedAt)) / 1000)
                            .toInt().coerceAtLeast(0)
                    _state.update { it.copy(pairingCodeRemainingSeconds = remaining) }
                }
            }
        }
    }

    /** 供 Application 退出时释放（正常流程不需要调）。 */
    fun release() {
        // B4-P2-24：先停 HTTP 服务（轮换任务、server.stop()、清配对码）
        // 再取消 scope——之前只取消 scope，server 还在后台跑着。
        // P2-7（第 5 轮）：stop() 是 scope.launch 的异步停服，onDestroy 时
        // 若 scope 先被取消，stopLocked() 永远跑不到，NanoHTTPD 会继续占着
        // 12346 端口直到进程被杀。release() 只在真退出时调，直接同步停服；
        // 先把 desiredRunning 置 false，排队的停服协程进锁后不再因
        // B5-P1-5 的复核而跳过停服。
        // 第 6 轮 P2-11：stop() 有 stopRequestedDuringStart 拦飞行中的 start()，
        // release() 之前没有——退出瞬间若 start() 正在 newServer.start() 与
        // server 赋值之间，start() 恢复后照常赋值、拉起轮换、置 isRunning，
        // 停掉的服务又被复活。这里同样置位，start() 恢复后走 stopLocked()；
        // 正在退出的进程里该标志不再被消费也无害（release 后不再调 start）。
        stopRequestedDuringStart = true
        desiredRunning = false
        stopLocked()
        scope.cancel()
    }
}
