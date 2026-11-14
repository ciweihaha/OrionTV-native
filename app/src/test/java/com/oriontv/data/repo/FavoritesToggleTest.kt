package com.oriontv.data.repo

import com.oriontv.data.api.Favorite
import com.oriontv.data.api.UnauthorizedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 服务端收藏 toggle 读-改-写判定测试（第 6 轮 D6-P1-2）：
 * 读失败必须抛错，不能默认按“不存在”走添加分支。
 */
class FavoritesToggleTest {

    private val favorite = Favorite(title = "片", source_name = "源A")

    @Test
    fun `read failure throws and never adds`() {
        var addCalls = 0
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                toggleServerFavorite(
                    existsCheck = { throw IOException("GET 失败") },
                    addFavorite = { addCalls++; true },
                    deleteFavorite = { true },
                )
            }
        }
        assertTrue(thrown.message!!.contains("读取收藏状态失败"))
        assertEquals(0, addCalls)
    }

    @Test
    fun `existing favorite is deleted`() {
        var deleteCalls = 0
        val result = runBlocking {
            toggleServerFavorite(
                existsCheck = { true },
                addFavorite = { error("must not add when exists") },
                deleteFavorite = { deleteCalls++; true },
            )
        }
        assertFalse(result)
        assertEquals(1, deleteCalls)
    }

    @Test
    fun `missing favorite is added`() {
        var addCalls = 0
        val result = runBlocking {
            toggleServerFavorite(
                existsCheck = { false },
                addFavorite = { addCalls++; true },
                deleteFavorite = { error("must not delete when missing") },
            )
        }
        assertTrue(result)
        assertEquals(1, addCalls)
    }

    @Test
    fun `delete failure throws`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                toggleServerFavorite(
                    existsCheck = { true },
                    addFavorite = { true },
                    deleteFavorite = { false },
                )
            }
        }
    }

    @Test
    fun `add failure throws`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                toggleServerFavorite(
                    existsCheck = { false },
                    addFavorite = { false },
                    deleteFavorite = { true },
                )
            }
        }
    }

    @Test
    fun `unauthorized from read propagates unwrapped`() {
        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                toggleServerFavorite(
                    existsCheck = { throw UnauthorizedException("登录已过期") },
                    addFavorite = { true },
                    deleteFavorite = { true },
                )
            }
        }
    }

    @Test
    fun `cancellation from read propagates`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                toggleServerFavorite(
                    existsCheck = { throw CancellationException("cancelled") },
                    addFavorite = { true },
                    deleteFavorite = { true },
                )
            }
        }
    }
}
