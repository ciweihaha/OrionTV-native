package com.oriontv.feature.play

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * 播放器代际/所有者 token（纯逻辑，可单测；见 PlaybackGenerationTest）：
 * 直播与点播两个 ViewModel 共用一个 PlayerManager 单例，各自 onCleared 都
 * 会异步 pause/stop；没有代际校验时，后销毁的 VM 会停掉先启动的另一页的播放。
 * 每次认领播放 +1 代，清理时只停自己那一代。
 */
class PlaybackGeneration {
    private var generation: Long = 0L

    /** 认领播放，返回本代 token。 */
    fun claim(): Long = ++generation

    /** 该 token 是否仍是当前代（即认领者仍是最新所有者）。0 号 token 从未发放过，永远不算当前代。 */
    fun isCurrent(token: Long): Boolean = token != 0L && token == generation
}

/**
 * ExoPlayer 单例（plan §4.5）：点播与直播共用，换 MediaItem 即换台/换集；
 * Player.Listener 翻译成沿用 PlaybackStatus 形状
 *（isLoaded/isPlaying/isBuffering/positionMs/durationMs/didJustFinish/error），
 * 降低各页改造成本。
 *
 * 超时与错误：VOD 就绪 20 秒超时；直播 15 秒超时；错误分结构化类型（SSL/网络/其他），
 * 不再用 RN 的错误字符串包含判断。自动换源策略照搬 plan §4.5。
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }

    /**
     * 后台播放开关（设置页“后台播放与画中画”）：PlayViewModel 开播时从设置读入，
     * MainActivity.onUserLeaveHint 据此决定是否进画中画，PlayViewModel.onCleared
     * 据此决定退出播放页后是停播还是继续播。
     */
    @Volatile
    var backgroundPlaybackEnabled: Boolean = false

    /** 播放所有权代际：各页 onCleared 只停自己那一代（见 PlaybackGeneration）。 */
    private val ownership = PlaybackGeneration()

    /** 认领播放，返回本代 token（进页时调一次）。 */
    fun claimPlayback(): Long = ownership.claim()

    /** 该 token 是否仍是当前播放所有者（A4-P2-9：错误回调的归属校验用）。 */
    fun isCurrentOwner(token: Long): Boolean = ownership.isCurrent(token)

    /** 只有 token 仍是当前代时才暂停（过期代的清理不碰别人的播放）。 */
    fun pauseIfOwner(token: Long) {
        if (ownership.isCurrent(token)) pause()
    }

    /** 只有 token 仍是当前代时才停播。 */
    fun stopIfOwner(token: Long) {
        if (ownership.isCurrent(token)) stop()
    }

    /** 当前播放状态（沿用 RN PlaybackStatus 形状）。 */
    data class PlaybackStatus(
        val isLoaded: Boolean = false,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val didJustFinish: Boolean = false,
        val error: PlayerError? = null,
    )

    /** 结构化播放错误（替代 RN 的错误字符串匹配）。 */
    sealed interface PlayerError {
        /** SSL/证书错误。 */
        data object Ssl : PlayerError

        /** 网络错误（超时/断网/5xx）。 */
        data object Network : PlayerError

        /** 其他错误。 */
        data class Other(val message: String? = null) : PlayerError
    }

    fun playUrl(url: String, startPositionMs: Long = 0L) {        player.setMediaItem(MediaItem.fromUri(url))
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.prepare()
        player.play()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** 倍速（按影片记忆，由 PlayViewModel 在开播时恢复）。 */
    fun setPlaybackSpeed(rate: Float) {
        player.playbackParameters = PlaybackParameters(rate)
    }

    /** 停播（不 release 单例，直播页还共用它）。 */
    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    val currentPositionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    val durationMs: Long get() = player.duration.coerceAtLeast(0L)
    val bufferedPositionMs: Long get() = player.bufferedPosition.coerceAtLeast(0L)
    val isPlaying: Boolean get() = player.isPlaying
    /** 是否自然播完（STATE_ENDED；第 6 轮 P1-1：播完态下 isPlaying 恒 false，
     * 须用它与“用户暂停”区分——A8-P1-1 起下一集倒计时只在播完态走）。 */
    val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING
    /** 是否已加载媒体项（setMediaItem 后、stop 清掉前为真；A8-P1-3：按 Home
     * 时正在缓冲 isPlaying=false，旧实现按“正在播”才进 PiP，缓冲中按 Home
     * 既不进 PiP 也不暂停、稍后出声变无窗口后台音频——改按它进 PiP）。 */
    val hasMediaItem: Boolean get() = player.currentMediaItem != null

    /**
     * 当前已挂的媒体 URL（A12-P2-1：回页时缓冲中的同台直接 play() 续播、
     * 不 stop()+playUrl() 重挂——须先确认播放器里挂的确实是同一条 URL；
     * 未挂媒体项时 null。Uri 往返可能与原串有编码差异，对不上时调用方
     * 只当“不是同 URL”、走原有重播，保守、不误判。 */
    val currentUrl: String? get() = player.currentMediaItem?.localConfiguration?.uri?.toString()

    fun release() {
        player.release()
    }

    fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    companion object {
        /** 结构化错误分类（替代 RN 的错误字符串匹配）：SSL / 网络 / 其他。 */
        fun classifyError(error: PlaybackException): PlayerError {
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is SSLException) return PlayerError.Ssl
                if (cause is java.io.IOException) return PlayerError.Network
                cause = cause.cause
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlayerError.Network
                else -> PlayerError.Other(error.message)
            }
        }
    }
}
