package com.oriontv

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.play.PlayerManager
import com.oriontv.feature.remoteinput.RemoteInputManager
import com.oriontv.navigation.OrionNavHost
import com.oriontv.ui.theme.OrionTheme
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                OrionNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * P1-7/P2-23：远程输入跟随应用生命周期——
     * 进前台时如果设置里开着，把服务拉起来（冷启动恢复，不再只靠进设置页触发）；
     * 切后台时停服，不随进程常驻后台占着 12346 端口。
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            if (settingsRepository.remoteInputEnabled.first()) {
                remoteInputManager.start()
            }
        }
    }

    override fun onStop() {
        remoteInputManager.stop()
        super.onStop()
    }

    override fun onDestroy() {
        // 真退出（非配置变更重建）时释放管理器；否则单例 scope 被 cancel 后无法再启动。
        if (isFinishing) remoteInputManager.release()
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
        when (leaveHintAction(playerManager.backgroundPlaybackEnabled, playerManager.isPlaying)) {
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
        /** 切后台决策：开关开 + 在播 → PiP；开关关 → 暂停；开关开 + 未播 → 不动。 */
        internal fun leaveHintAction(backgroundPlaybackEnabled: Boolean, isPlaying: Boolean): LeaveHintAction =
            when {
                backgroundPlaybackEnabled && isPlaying -> LeaveHintAction.EnterPip
                !backgroundPlaybackEnabled -> LeaveHintAction.Pause
                else -> LeaveHintAction.None
            }
    }
}
