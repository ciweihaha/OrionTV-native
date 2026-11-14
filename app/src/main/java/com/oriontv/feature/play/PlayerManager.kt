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

    fun playUrl(url: String, startPositionMs: Long = 0L) {
        player.setMediaItem(MediaItem.fromUri(url))
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
    val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING

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
