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

    /** 第 73 轮：读 X2 根文件（图床 jinyingimage/huyajs 等链到 X2）。 */
    private fun bundledRootX2Pem(): ByteArray {
        val candidates = listOf(
            File("src/main/res/raw/isrg_root_x2.pem"),
            File("app/src/main/res/raw/isrg_root_x2.pem"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到内置根证书 res/raw/isrg_root_x2.pem，候选：$candidates")
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
    fun `bundled X2 root parses to ISRG Root X2 CA certificate`() {
        // 第 73 轮：X2 根必须真实、可用——电视行 5 个图床链到 X2。
        val cert = TlsTrust.parsePemCertificate(bundledRootX2Pem())
        assertTrue(cert.subjectX500Principal.name.contains("CN=ISRG Root X2"))
        assertTrue(cert.issuerX500Principal.name.contains("CN=ISRG Root X2"))
        assertTrue(cert.basicConstraints >= 0)
    }

    @Test
    fun `bundled X2 root fingerprint matches published ISRG Root X2 fingerprint`() {
        val cert = TlsTrust.parsePemCertificate(bundledRootX2Pem())
        assertEquals(TlsTrust.ISRG_ROOT_X2_SHA256, TlsTrust.sha256Fingerprint(cert))
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

    @Test
    fun `ssl context builds with both bundled roots as accepted issuers`() {
        // 第 73 轮：X1 + X2 两个根一起装进组合信任管理器。
        val x1 = TlsTrust.parsePemCertificate(bundledRootPem())
        val x2 = TlsTrust.parsePemCertificate(bundledRootX2Pem())
        val sslContext = TlsTrust.sslContextWithExtraRoots(bundledRootPem(), bundledRootX2Pem())
        assertTrue(sslContext.socketFactory != null)
        val trustManager = TlsTrust.combinedTrustManager(listOf(x1, x2))
        assertTrue(
            trustManager.acceptedIssuers.any { it.encoded.contentEquals(x1.encoded) },
        )
        assertTrue(
            trustManager.acceptedIssuers.any { it.encoded.contentEquals(x2.encoded) },
        )
    }
}
