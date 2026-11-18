package com.oriontv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.play.PlayerManager
import com.oriontv.feature.remoteinput.RemoteInputManager
import com.oriontv.navigation.OrionNavHost
import com.oriontv.ui.theme.OrionTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/** 单 Activity 入口，只做 TV 横屏。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var playerManager: PlayerManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var remoteInputManager: RemoteInputManager

    /** C4-P1-6：热启动深链用——singleTask 下新 Intent 走 onNewIntent，交给它处理。 */
    private var navController: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                val navController = rememberNavController()
                this.navController = navController
                OrionNavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 热启动深链（adb/语音助手在应用已在前台/后台时发 oriontv://…）：
        // 无此回调时 singleTask 会直接丢弃新 Intent，详情/播放深链永远到不了。
        // C5-P2-6：匹配失败时记日志——之前 handleDeepLink 返回 false 也静默丢弃。
        // C7-P2-4：重复深链不堆叠——navigate(Uri) + launchSingleTop，栈顶已是
        // 同一路由（同参）时不再压栈；之前 handleDeepLink 每次都压，重复深链
        // 会堆出多个相同详情/播放页。无匹配时抛错，按未处理记日志。
        val controller = navController
        val uri = intent.data
        val handled = if (controller != null && uri != null) {
            runCatching {
                controller.navigate(uri, androidx.navigation.navOptions { launchSingleTop = true })
                true
            }.getOrDefault(false)
        } else {
            controller?.handleDeepLink(intent) == true
        }
        if (!handled) {
            android.util.Log.w("MainActivity", "深链未匹配任何路由，已丢弃：$intent")
        }
    }

    /**
     * P1-7/P2-23：远程输入跟随应用生命周期——
     * 进前台时如果设置里开着，把服务拉起来（冷启动恢复，不再只靠进设置页触发）；
     * 切后台时不停服，改为 60 秒宽限后停（P2-13，见 onStop）。
     */
    private var remoteInputStopJob: Job? = null

    override fun onStart() {
        super.onStart()
        // 回前台：先取消待执行的延迟停服，再按设置恢复服务。
        remoteInputStopJob?.cancel()
        remoteInputStopJob = null
        lifecycleScope.launch {
            // B9-P2-1：DataStore 读失败不让协程未捕获崩溃；失败即跳过本次恢复。
            // B14-P2-8：取消原样抛出——runCatching 会连取消一起吞，与全仓
            // “取消原样抛出”约定不一致。
            val enabled = try {
                settingsRepository.remoteInputEnabled.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (enabled) {
                remoteInputManager.start()
            }
        }
    }

    override fun onStop() {
        // P2-13（第 5 轮）：切后台不停服，改为 60 秒宽限后停。直接停服的
        // 副作用：从更新卡片点“立即安装”调起系统安装器时 MainActivity
        // 同样走 onStop，服务停掉、回来时 onStart 重启并换新配对码，
        // 手机端须重新配对。60 秒宽限覆盖安装流程（调起安装器→安装→返回）；
        // 真按 Home 离开的，服务仍在 60 秒内停掉，不随进程常驻占着
        // 12346 端口。
        remoteInputStopJob = lifecycleScope.launch {
            delay(REMOTE_INPUT_STOP_DELAY_MS)
            remoteInputManager.stop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // 真退出（非配置变更重建）时释放管理器；否则单例 scope 被 cancel 后无法再启动。
        if (isFinishing) {
            remoteInputStopJob?.cancel()
            remoteInputManager.release()
        }
        super.onDestroy()
    }

    /**
     * 切到后台时暂停（第 74 轮：后台播放与画中画已整个删掉）：
     * 按 Home 不销毁 ViewModel，旧实现无任何暂停逻辑时音频会继续播；
     * 现在切后台即暂停，不进画中画、不留后台音频。
     * A8-P1-3：不按 isPlaying 判定——正在缓冲时 isPlaying=false 也要暂停，
     * 否则缓冲完成出声即变无窗口后台音频。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playerManager.pause()
    }

    companion object {
        /** 切后台后延迟停服的宽限（P2-13：覆盖调起安装器的流程）。 */
        internal const val REMOTE_INPUT_STOP_DELAY_MS = 60_000L
    }
}
