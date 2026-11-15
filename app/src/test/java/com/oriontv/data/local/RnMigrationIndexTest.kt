package com.oriontv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** RN 播放记录迁移的集号换算测试（第 8 轮 D8-P1-2）：RN 的 index 是 1 基，原生 Room 是 0 基。 */
class RnMigrationIndexTest {

    @Test
    fun `rn index 1 maps to local episode 0`() {
        assertEquals(0, toLocalEpisodeIndex(1))
    }

    @Test
    fun `rn index maps down by one`() {
        assertEquals(1, toLocalEpisodeIndex(2))
        assertEquals(9, toLocalEpisodeIndex(10))
    }

    @Test
    fun `dirty zero or negative rn index clamps to zero`() {
        // 脏记录 index=0/缺省时不变成 -1。
        assertEquals(0, toLocalEpisodeIndex(0))
        assertEquals(0, toLocalEpisodeIndex(-3))
    }
}
