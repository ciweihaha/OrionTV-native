package com.oriontv.feature.play

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

    fun playUrl(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
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

    fun release() {
        player.release()
    }

    fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }
}
