package com.oriontv.core.util

import android.content.Context
import com.oriontv.R
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * v2.0.7：给旧系统补现代根证书（可单测的纯逻辑 + 薄薄一层安装）。
 *
 * 背景：v2.0.6 补上 media3-exoplayer-hls 后，点播不再报
 * ClassNotFoundException，但华为 M3（Android 7.0）上两个源都报
 * SSLHandshakeException / Trust anchor not found——流地址
 * hd.kuktxu.com 用的是 Let's Encrypt 证书，链到 ISRG Root X1，
 * 而 Android 7.0 的系统信任库里没有这个根（7.1.1 才内置）。
 * ExoPlayer 的 DefaultHttpDataSource 走 HttpURLConnection，用的是
 * 进程默认 SSLSocketFactory；这里把 ISRG Root X1（res/raw 内置，
 * 与 letsencrypt.org 发布的根证书逐字节一致）追加为信任锚，
 * 系统根仍优先校验、只在系统校验失败时才用追加的根兜底，
 * 不绕过、不放宽任何校验（自签/过期/错域名照样失败）。
 */
object TlsTrust {

    /** ISRG Root X1 的 SHA-256 指纹（letsencrypt.org 公布值，单测据此验根）。 */
    const val ISRG_ROOT_X1_SHA256 =
        "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6"

    private val installed = AtomicBoolean(false)

    /**
     * 从 res/raw 读内置根并装为 HttpsURLConnection 的默认信任。
     * 幂等、失败只记日志不抛错（启动不能因此崩；装不上时播放仍按旧行为走，
     * 证书错照样报 PlayerError.Ssl）。
     */
    fun installExtraTrustRoots(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val pem = context.resources.openRawResource(R.raw.isrg_root_x1).use { it.readBytes() }
            val sslContext = sslContextWithExtraRoots(pem)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            AppLog.i("TlsTrust", "已追加内置根证书到默认信任（ISRG Root X1）")
        }.onFailure { e ->
            AppLog.w("TlsTrust", "追加内置根证书失败：${e.message}")
        }
    }

    /** 纯逻辑：用给定的 PEM 根证书构造 SSLContext（可单测）。 */
    fun sslContextWithExtraRoots(vararg pemBytes: ByteArray): SSLContext {
        val certs = pemBytes.map(::parsePemCertificate)
        val trustManager = combinedTrustManager(certs)
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
    }

    /** 纯逻辑：PEM 文本解析为 X509 证书（可单测）。 */
    fun parsePemCertificate(pemBytes: ByteArray): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(pemBytes)) as X509Certificate
    }

    /** 纯逻辑：证书的 SHA-256 指纹（冒号分隔大写十六进制，可单测）。 */
    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** 系统信任管理器（null KeyStore 即系统信任库）。 */
    internal fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 组合信任管理器：先走系统校验，失败再走追加的根校验。
     * 追加的根只在系统根验证不过时才参与，不替代系统校验。
     */
    internal fun combinedTrustManager(extraCerts: List<X509Certificate>): X509TrustManager {
        val systemTm = systemTrustManager()
        val extraKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            extraCerts.forEachIndexed { index, cert ->
                setCertificateEntry("extra-root-$index", cert)
            }
        }
        val extraFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        extraFactory.init(extraKeyStore)
        val extraTm = extraFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkClientTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extraTm.checkClientTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extraTm.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + extraTm.acceptedIssuers
        }
    }
}
