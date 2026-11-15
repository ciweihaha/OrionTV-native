package com.oriontv.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务端存储快照判定测试（第 16 轮 B16-P2-3）：判定与地址同属一次快照。 */
class ServerStorageSnapshotTest {

    @Test
    fun `redis with url uses server storage and keeps the url`() {
        val snapshot = resolveServerStorage("redis", "https://example.com")
        assertTrue(snapshot.useServer)
        assertEquals("https://example.com", snapshot.baseUrl)
    }

    @Test
    fun `redis without url falls back to local`() {
        val snapshot = resolveServerStorage("redis", "")
        assertFalse(snapshot.useServer)
        assertEquals("", snapshot.baseUrl)
    }

    @Test
    fun `localstorage with url falls back to local`() {
        val snapshot = resolveServerStorage("localstorage", "https://example.com")
        assertFalse(snapshot.useServer)
        assertEquals("https://example.com", snapshot.baseUrl)
    }

    @Test
    fun `unknown storage type falls back to local`() {
        val snapshot = resolveServerStorage("", "https://example.com")
        assertFalse(snapshot.useServer)
    }

    @Test
    fun `blank url is not a configured server`() {
        val snapshot = resolveServerStorage("redis", "   ")
        assertFalse(snapshot.useServer)
    }
}
