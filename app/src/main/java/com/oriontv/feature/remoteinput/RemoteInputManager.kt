package com.oriontv.feature.remoteinput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val router = RemoteInputRouter(
        onMessage = { message ->
            scope.launch {
                _state.update {
                    it.copy(lastMessage = message, lastMessageAt = System.currentTimeMillis())
                }
                // P2-13：缓冲为 0，无订阅者时直接丢弃，不滞留旧消息下次进搜索页误触发。
                _messages.tryEmit(message)
            }
        },
        onHandshake = {
            scope.launch { _handshakes.emit(Unit) }
        },
    )

    private val _state = MutableStateFlow(RemoteInputState())
    val state: StateFlow<RemoteInputState> = _state.asStateFlow()

    /** 远程文本流（搜索页订阅：收到即填入搜索框并搜索）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 0)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 配对成功事件流（扫码弹窗订阅：配对成功后自动关闭）。 */
    private val _handshakes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val handshakes: SharedFlow<Unit> = _handshakes.asSharedFlow()

    private var server: RemoteInputServer? = null
    private var starting = false
    private var stopRequestedDuringStart = false
    private var desiredRunning = false
    /** 配对码定期轮换任务（P1-10：每 5 分钟换一次码并刷新 state 里的显示）。 */
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
        try {
            // B4-P2-8：网络枚举 + 端口绑定是阻塞操作，切 IO 线程做，不占主线程。
            val ip = withContext(Dispatchers.IO) { RemoteInputServer.localIpAddress() }
            if (ip == null) {
                throw IllegalStateException("无法获取 IP 地址，请确认设备已连接到 Wi-Fi 或以太网。")
            }
            val newServer = RemoteInputServer(router)
            withContext(Dispatchers.IO) { newServer.start() }
            val code = router.rotatePairingCode()
            server = newServer
            if (stopRequestedDuringStart) {
                stopLocked()
                return
            }
            startRotationJob()
            _state.update {
                it.copy(
                    isRunning = true,
                    serverUrl = "http://$ip:${RemoteInputService.PORT}",
                    pairingCode = code,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            // B-P1-9：取消原样抛出，不写 error 态、不按“失败”重起。
            cancelled = true
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
            runCatching { server?.stop() }
            server = null
            router.clearPairingCode()
        } finally {
            startMutex.withLock {
                starting = false
                stopRequestedDuringStart = false
            }
            // 启动期间用户又开了：按最新意图重起一次（取消不重起）。
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
        rotationJob?.cancel()
        rotationJob = null
        runCatching { server?.stop() }
        server = null
        router.clearPairingCode()
        _state.update {
            it.copy(isRunning = false, serverUrl = null, pairingCode = null, error = null)
        }
    }

    /** P1-10：配对码每 5 分钟轮换一次，state 里的显示同步更新；停服即停。 */
    private fun startRotationJob() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            while (desiredRunning) {
                delay(RemoteInputLogic.PAIRING_CODE_TTL_MS)
                if (!desiredRunning || !_state.value.isRunning) break
                val code = router.rotatePairingCode()
                _state.update { it.copy(pairingCode = code) }
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
        desiredRunning = false
        stopLocked()
        scope.cancel()
    }
}
