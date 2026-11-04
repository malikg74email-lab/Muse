package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 本机作者密钥：由用户设备自己持有的一对 P-256 密钥，用于给助手起草的插件签名。
 *
 * 安全边界：
 *  - 私钥只保存在 [Context.noBackupFilesDir]（不进入 Android 备份、不写入插件包、
 *    不写入项目目录），以 PKCS#8 的标准 Base64 落盘，公钥以 X.509 SubjectPublicKeyInfo
 *    的标准 Base64 一并保存；私钥内容不对外暴露。
 *  - 签名只覆盖 [PluginSecurityGate.signaturePayload] 定义的内容，签名算法与会话
 *    校验算法一致（`SHA256withECDSA`，DER 编码）。
 *  - 密钥只在首次使用时生成一次，同一设备上 [publisherId]/公钥/指纹保持稳定；
 *    读取或生成失败返回可读失败，不抛异常到上层，也绝不静默换一把新密钥
 *    （换钥会让既有信任根与已签名插件全部失配）。
 *  - 本类只提供密钥；「是否需要用户确认」由 PluginManager 的签名启用路径负责，
 *    本类不会自行触发任何信任根写入。
 */
class LocalAuthorKeyStore internal constructor(
    private val storageFile: File,
) {

    /** Android 运行时使用的私有、不进入备份的作者密钥存储。 */
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, KEY_FILE_NAME),
    )

    /** 本机作者固定发行者 id；与外部市场发行者处于同一个信任根命名空间。 */
    val publisherId: String get() = PUBLISHER_ID

    /**
     * 本机作者公钥（X.509 SPKI 标准 Base64 单行）。
     *
     * 首次调用会生成并持久化密钥对；之后始终复用同一把。
     */
    fun publicKeyBase64(): Result<String> = material().map { it.publicKeyBase64 }

    /** 本机作者公钥的 SHA-256 指纹（小写 hex），用于与插件注册表记录比对。 */
    fun fingerprint(): Result<String> = material().map { it.fingerprint }

    /**
     * 用本机作者私钥签名 [payload]。
     *
     * 返回 `SHA256withECDSA` 的 DER 签名；调用方负责按 [PluginSecurityGate.signaturePayload]
     * 组装 payload 并把签名包进 manifest。
     */
    fun sign(payload: ByteArray): Result<ByteArray> {
        val material = material().getOrElse { error -> return Result.failure(error) }
        return try {
            val signer = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM)
            signer.initSign(material.privateKey)
            signer.update(payload)
            Result.success(signer.sign())
        } catch (error: Exception) {
            Logger.e(TAG, "Local author key signing failed", error)
            Result.failure(IllegalStateException("Local author key signing failed: ${error.message}", error))
        }
    }

    @Serializable
    private data class StoredAuthorKey(
        val publisherId: String,
        /** PKCS#8 编码的标准 Base64（单行）。 */
        val privateKeyPkcs8: String,
        /** X.509 SubjectPublicKeyInfo 编码的标准 Base64（单行）。 */
        val publicKeyX509: String,
    )

    private class AuthorKeyMaterial(
        val privateKey: PrivateKey,
        val publicKeyBase64: String,
        val fingerprint: String,
    )

    private val lock = Any()

    @Volatile
    private var cached: AuthorKeyMaterial? = null

    private fun material(): Result<AuthorKeyMaterial> = synchronized(lock) {
        cached?.let { return Result.success(it) }
        val loaded = if (storageFile.exists()) {
            readMaterial().getOrElse { error -> return Result.failure(error) }
        } else {
            val generated = generateMaterial().getOrElse { error -> return Result.failure(error) }
            try {
                persist(generated)
            } catch (error: Exception) {
                Logger.e(TAG, "Failed to write the local author key", error)
                return Result.failure(
                    IllegalStateException("Could not save the local author key: ${error.message ?: "Write failed"}", error),
                )
            }
            generated
        }
        cached = loaded
        Result.success(loaded)
    }

    private fun readMaterial(): Result<AuthorKeyMaterial> = try {
        val stored = AppJson.decodeFromString<StoredAuthorKey>(storageFile.readText())
        require(stored.publisherId == PUBLISHER_ID) { "Author key publisher mismatch" }
        val privateKeyBytes = decodeBase64(stored.privateKeyPkcs8, "Private key")
        val publicKeyBytes = decodeBase64(stored.publicKeyX509, "Public key")
        val privateKey = KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        require(privateKey is ECPrivateKey) { "The local author private key is not an EC private key" }
        val publicKey = KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        require(publicKey is ECPublicKey) { "The local author public key is not an EC public key" }
        require(privateKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "The local author private key is not P-256" }
        require(publicKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "The local author public key is not P-256" }
        // 校验落盘的一对密钥确实互相匹配：文件损坏/被换过时 fail-closed，
        // 而不是拿一把无法通过信任根校验的密钥去签名。
        val probe = ByteArray(32) { index -> index.toByte() }
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        val matches = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
        require(matches) { "The local author public key does not match the private key" }
        Result.success(
            AuthorKeyMaterial(
                privateKey = privateKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
                fingerprint = PluginSecurityGate.publicKeyFingerprint(publicKey.encoded),
            ),
        )
    } catch (error: Exception) {
        Logger.w(TAG, "Failed to read the local author key", error)
        Result.failure(
            IllegalStateException("Local author key unavailable: ${error.message ?: "File corrupted"}", error),
        )
    }

    private fun generateMaterial(): Result<AuthorKeyMaterial> {
        val pair = try {
            KeyPairGenerator.getInstance(PluginSecurityGate.KEY_ALGORITHM).run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to generate the local author key", error)
            return Result.failure(
                IllegalStateException("Could not generate the local author key: ${error.message ?: "System does not support P-256"}", error),
            )
        }
        return materialOf(pair)
    }

    private fun materialOf(pair: KeyPair): Result<AuthorKeyMaterial> = try {
        val privateKey = pair.private as ECPrivateKey
        val publicKey = pair.public as ECPublicKey
        require(privateKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "The private key is not P-256" }
        require(publicKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "The public key is not P-256" }
        Result.success(
            AuthorKeyMaterial(
                privateKey = privateKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
                fingerprint = PluginSecurityGate.publicKeyFingerprint(publicKey.encoded),
            ),
        )
    } catch (error: Exception) {
        Result.failure(IllegalStateException("Unsupported local author key format: ${error.message}", error))
    }

    private fun persist(material: AuthorKeyMaterial) {
        val stored = StoredAuthorKey(
            publisherId = PUBLISHER_ID,
            privateKeyPkcs8 = Base64.getEncoder().encodeToString(material.privateKey.encoded),
            publicKeyX509 = material.publicKeyBase64,
        )
        AtomicFileStore.writeText(
            storageFile,
            AppJson.encodeToString(StoredAuthorKey.serializer(), stored),
        )
        // 私钥文件权限尽力收紧：仅属主可读写，不能执行；部分文件系统不支持时忽略。
        runCatching {
            storageFile.setReadable(true, true)
            storageFile.setWritable(true, true)
            storageFile.setExecutable(false, false)
        }.onFailure { error ->
            Logger.w(TAG, "Failed to tighten local author key file permissions (does not affect functionality)", error)
        }
    }

    private fun decodeBase64(encoded: String, label: String): ByteArray {
        require(encoded.isNotBlank()) { "$label is empty" }
        require(encoded.length <= MAX_KEY_BASE64_CHARS) { "$label is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(bytes) == encoded) { "$label is not standard Base64" }
        return bytes
    }

    companion object {
        private const val TAG = "LocalAuthorKeyStore"

        /** 本机作者发行者 id：与外部发行者同一命名规则，且固定不可配置。 */
        const val PUBLISHER_ID = "local-author"

        private const val KEY_FILE_NAME = "muse_local_author_key.json"
        private const val P256_FIELD_SIZE = 256
        private const val MAX_KEY_BASE64_CHARS = 4096
    }
}
