package com.oriontv.feature.remoteinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteInputManagerLogicTest {

    @Test
    fun `unchanged ip keeps current server url`() {
        // B58-P2-3：IP 没变时返回 null，不用更新 state。
        assertNull(
            RemoteInputManager.resolveServerUrlAfterIpChange(
                currentServerUrl = "http://192.168.1.25:12346",
                newIp = "192.168.1.25",
                port = 12346,
            ),
        )
    }

    @Test
    fun `changed ip returns new server url`() {
        // B58-P2-3：DHCP 重分配后返回新地址，设置页与二维码随之刷新。
        assertEquals(
            "http://192.168.1.30:12346",
            RemoteInputManager.resolveServerUrlAfterIpChange(
                currentServerUrl = "http://192.168.1.25:12346",
                newIp = "192.168.1.30",
                port = 12346,
            ),
        )
    }

    @Test
    fun `missing ip keeps old server url`() {
        // B58-P2-3：取不到 IP（断网）时返回 null，保留旧地址，不清空。
        assertNull(
            RemoteInputManager.resolveServerUrlAfterIpChange(
                currentServerUrl = "http://192.168.1.25:12346",
                newIp = null,
                port = 12346,
            ),
        )
        assertNull(
            RemoteInputManager.resolveServerUrlAfterIpChange(
                currentServerUrl = "http://192.168.1.25:12346",
                newIp = "",
                port = 12346,
            ),
        )
    }

    @Test
    fun `no current url with new ip returns new server url`() {
        assertEquals(
            "http://192.168.1.25:12346",
            RemoteInputManager.resolveServerUrlAfterIpChange(
                currentServerUrl = null,
                newIp = "192.168.1.25",
                port = 12346,
            ),
        )
    }
}
