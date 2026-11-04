package io.zer0.muse.web

import android.content.Context
import io.zer0.common.Logger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v2.x: 嵌入式 Web 服务器的 HTTPS 自签证书工具。
 *
 * 设计:
 *  - 首次启用 HTTPS 时生成 RSA 2048 密钥 + 自签 X.509 证书,持久化到 filesDir(PKCS12 keystore),
 *    之后每次启动直接复用,不再重复生成;
 *  - 证书仅用于「局域网点对点加密」:浏览器/客户端首次访问会提示"证书不受信任",
 *    用户可核对 [TlsMaterial.fingerprint] 指纹后手动信任(自签证书无公共 CA 背书);
 *  - 证书生成用 BouncyCastle 构造并签名 X.509 DER。BC(`bcpkix`/`bcprov`)已由
 *    `com.tom-roush:pdfbox-android` 传递进 classpath,本方案未新增任何依赖。
 *
 * 线程:所有方法均涉及文件读写与密钥运算,必须在 IO 线程调用。
 */
internal object WebServerTls {

    private const val TAG = "WebServerTls"

    /** PKCS12 keystore 文件名(存放于 filesDir)。 */
    private const val KEYSTORE_FILE = "muse-web-tls.p12"

    /** keystore 口令文件名(存放于 filesDir,应用私有目录)。 */
    private const val PASSWORD_FILE = "muse-web-tls.pwd"

    private const val KEYSTORE_TYPE = "PKCS12"

    /** 证书别名(keystore 内条目名)。 */
    const val ALIAS = "muse-web"

    /** 证书主题。自签证书不做域名校验,CN 仅作展示。 */
    private const val SUBJECT = "CN=muse-web, O=Muse, OU=Embedded Web Server"

    /** 有效期(10 年):局域网自签证书,避免频繁轮换。 */
    private const val VALIDITY_DAYS = 3650L

    /** RSA 密钥长度。 */
    private const val KEY_SIZE = 2048

    /** 生成的 TLS 材料:keystore + 口令 + 证书指纹。 */
    data class TlsMaterial(
        val keyStore: KeyStore,
        val alias: String,
        val password: CharArray,
        /** 证书 SHA-256 指纹(大写十六进制,冒号分隔),供用户在浏览器警告页核对。 */
        val fingerprint: String,
    )

    /**
     * 载入已有自签证书;不存在或损坏时重新生成并持久化。
     * 需在 IO 线程调用(涉及文件读写与密钥生成)。
     */
    fun loadOrCreate(context: Context): TlsMaterial {
        val keyStoreFile = File(context.filesDir, KEYSTORE_FILE)
        val passwordFile = File(context.filesDir, PASSWORD_FILE)

        loadExisting(keyStoreFile, passwordFile)?.let { return it }

        Logger.i(TAG, "Generating HTTPS self-signed certificate for the first time (filesDir/$KEYSTORE_FILE)")
        return generate(keyStoreFile, passwordFile)
    }

    /** 尝试载入既有 keystore;任一环节失败返回 null(由调用方回退到重新生成)。 */
    private fun loadExisting(keyStoreFile: File, passwordFile: File): TlsMaterial? {
        if (!keyStoreFile.exists() || !passwordFile.exists()) return null
        return try {
            val password = passwordFile.readText().trim().toCharArray()
            if (password.isEmpty()) return null
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            FileInputStream(keyStoreFile).use { keyStore.load(it, password) }
            if (!keyStore.containsAlias(ALIAS) || !keyStore.isKeyEntry(ALIAS)) return null
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
            TlsMaterial(keyStore, ALIAS, password, fingerprintOf(certificate))
        } catch (t: Throwable) {
            // 不吞 CancellationException:仅在真实失败时回退。
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "Failed to load existing HTTPS certificate; regenerating: ${t.message}")
            null
        }
    }

    /** 生成密钥对与自签证书,写入 keystore 与口令文件。 */
    private fun generate(keyStoreFile: File, passwordFile: File): TlsMaterial {
        val password = randomPassword()
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(KEY_SIZE) }
            .generateKeyPair()
        val certificate = selfSign(keyPair)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))

        passwordFile.writeText(String(password))
        FileOutputStream(keyStoreFile).use { keyStore.store(it, password) }

        return TlsMaterial(keyStore, ALIAS, password, fingerprintOf(certificate))
    }

    /**
     * 构造并签名自签 X.509 证书。
     * 主题含 localhost / 127.0.0.1 的 SAN,便于同机客户端比对(局域网 IP 访问仍会因自签而告警)。
     */
    private fun selfSign(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS))
        val serial = BigInteger(64, SecureRandom())
        val name = X500Name(SUBJECT)

        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    ),
                ),
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        // 自校验:证书与私钥不匹配时立即暴露,而非等到 TLS 握手才失败。
        certificate.verify(keyPair.public)
        return certificate
    }

    /** 证书 SHA-256 指纹(大写十六进制,冒号分隔)。 */
    private fun fingerprintOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /** 生成 URL 安全随机口令(keystore 口令,与应用私有目录同存)。 */
    private fun randomPassword(): CharArray {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }
}
