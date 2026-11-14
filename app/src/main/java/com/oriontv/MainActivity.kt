package com.oriontv

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import javax.inject.Inject

/** 切到后台时的动作（纯决策，可单测；见 MainActivityLeaveHintTest）。 */
enum class LeaveHintAction {
    /** 开关开着且正在播：进画中画。 */
    EnterPip,

    /** 开关关着：暂停，VM 不销毁也不会继续播后台音频。 */
    Pause,

    /** 开关开着但没在播：什么都不做。 */
    None,
}

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

    /**
     * A4-P2-5：后台播放开关的最新值（持续跟随 DataStore 流）；onUserLeaveHint
     * 处同步读它，不再只靠进播放页时异步读一次的旧值（进页后立刻按 Home
     * 时异步读入还没跑完，会误暂停）。
     */
    @Volatile
    private var backgroundPlaybackCached: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            settingsRepository.backgroundPlayback.collect { enabled ->
                backgroundPlaybackCached = enabled
                playerManager.backgroundPlaybackEnabled = enabled
            }
        }
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
        val handled = navController?.handleDeepLink(intent) == true
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
            if (settingsRepository.remoteInputEnabled.first()) {
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
        // 12346 端口。进画中画时不走 onStop，不受影响。
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
     * 切到后台时进画中画（阶段 4，后台播放与画中画）：
     * 只有正在播放且设置里开了“后台播放与画中画”才进 PiP；
     * 开关关着时直接暂停——按 Home 不销毁 ViewModel，旧实现无任何暂停逻辑，
     * 音频会继续播；进 PiP 后 Activity 不销毁，播放继续；
     * 用户点 PiP 窗口回前台后恢复全屏。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // A4-P2-5：读持续跟新的缓存值，不是进页时异步读入的旧值。
        when (leaveHintAction(backgroundPlaybackCached, playerManager.isPlaying)) {
            LeaveHintAction.EnterPip -> {
                try {
                    enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .build(),
                    )
                } catch (_: Exception) {
                    // 设备不支持 PiP 时静默降级：只保留后台音频（播放器本身不因进不了 PiP 而停）。
                }
            }
            LeaveHintAction.Pause -> playerManager.pause()
            LeaveHintAction.None -> Unit
        }
    }

    companion object {
        /** 切后台后延迟停服的宽限（P2-13：覆盖调起安装器的流程）。 */
        internal const val REMOTE_INPUT_STOP_DELAY_MS = 60_000L

        /** 切后台决策：开关开 + 在播 → PiP；开关关 → 暂停；开关开 + 未播 → 不动。 */
        internal fun leaveHintAction(backgroundPlaybackEnabled: Boolean, isPlaying: Boolean): LeaveHintAction =
            when {
                backgroundPlaybackEnabled && isPlaying -> LeaveHintAction.EnterPip
                !backgroundPlaybackEnabled -> LeaveHintAction.Pause
                else -> LeaveHintAction.None
            }
    }
}
