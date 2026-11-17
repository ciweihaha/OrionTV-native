package com.oriontv.feature.play

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * v2.0.6 回归测试：聚合源基本全是 m3u8，ExoPlayer 的 DefaultMediaSourceFactory
 * 遇到 .m3u8 会反射加载 HlsMediaSource$Factory；缺 media3-exoplayer-hls
 * 依赖时 dex 里没有该类，点播/直播开播即 ClassNotFoundException 全挂
 * （v2.0.5 平板实测，两个源、两个集数都“开播失败”）。
 * 只验证类在编译产物里，不初始化（media3 类初始化会碰 android.*，
 * JVM 单测里不安全）。
 */
class HlsDependencyTest {

    @Test
    fun `hls media source factory is on the classpath`() {
        val clazz = Class.forName(
            "androidx.media3.exoplayer.hls.HlsMediaSource\$Factory",
            false,
            javaClass.classLoader,
        )
        assertNotNull(clazz)
    }
}
