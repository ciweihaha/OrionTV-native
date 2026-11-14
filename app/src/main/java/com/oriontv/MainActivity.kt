package com.oriontv

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.oriontv.feature.play.PlayerManager
import com.oriontv.navigation.OrionNavHost
import com.oriontv.ui.theme.OrionTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 单 Activity 入口，只做 TV 横屏。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var playerManager: PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                OrionNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * 切到后台时进画中画（阶段 4，后台播放与画中画）：
     * 只有正在播放且设置里开了“后台播放与画中画”才进 PiP；
     * 开关关着时不拦截，播放页退出即停播（PlayViewModel.onCleared）。
     * 注意：进 PiP 后 Activity 不销毁，播放继续；用户点 PiP 窗口回前台后恢复全屏。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!playerManager.backgroundPlaybackEnabled) return
        if (!playerManager.isPlaying) return
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
}
