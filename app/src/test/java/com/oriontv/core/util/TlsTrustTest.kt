package com.oriontv.core.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.7 回归测试：内置的 ISRG Root X1 根证书必须真实、可用。
 * 背景：华为 M3（Android 7.0）系统信任库没有 ISRG Root X1，
 * Let's Encrypt 的流地址（hd.kuktxu.com）握手报
 * Trust anchor not found，两个源都播不了；本测试守住“根证书
 * 在包里且是真的 ISRG Root X1”这一前提（平板实测守住“能播”）。
 */
class TlsTrustTest {

    /** 读打包进应用的真根文件（Gradle 单测工作目录是 app/）。 */
    private fun bundledRootPem(): ByteArray {
        val candidates = listOf(
            File("src/main/res/raw/isrg_root_x1.pem"),
            File("app/src/main/res/raw/isrg_root_x1.pem"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到内置根证书 res/raw/isrg_root_x1.pem，候选：$candidates")
        return file.readBytes()
    }

    @Test
    fun `bundled root parses to ISRG Root X1 CA certificate`() {
        val cert = TlsTrust.parsePemCertificate(bundledRootPem())
        assertTrue(cert.subjectX500Principal.name.contains("CN=ISRG Root X1"))
        assertTrue(cert.issuerX500Principal.name.contains("CN=ISRG Root X1"))
        // CA 证书（basicConstraints >= 0 才是 CA）。
        assertTrue(cert.basicConstraints >= 0)
    }

    @Test
    fun `bundled root fingerprint matches published ISRG Root X1 fingerprint`() {
        val cert = TlsTrust.parsePemCertificate(bundledRootPem())
        assertEquals(TlsTrust.ISRG_ROOT_X1_SHA256, TlsTrust.sha256Fingerprint(cert))
    }

    @Test
    fun `ssl context builds with bundled root as accepted issuer`() {
        val pem = bundledRootPem()
        val sslContext = TlsTrust.sslContextWithExtraRoots(pem)
        assertTrue(sslContext.socketFactory != null)
        val cert = TlsTrust.parsePemCertificate(pem)
        val trustManager = TlsTrust.combinedTrustManager(listOf(cert))
        assertTrue(
            trustManager.acceptedIssuers.any { it.encoded.contentEquals(cert.encoded) },
        )
    }
}
