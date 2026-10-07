package com.terminuke.app.data

import android.util.Base64
import com.terminuke.app.crypto.CryptoManager
import com.terminuke.app.data.db.KeyDao
import com.terminuke.app.data.db.SshKeyEntity
import kotlinx.coroutines.flow.Flow
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.RSAPublicKey
import java.util.UUID

class KeyRepository(private val dao: KeyDao, private val crypto: CryptoManager) {
    fun observe(): Flow<List<SshKeyEntity>> = dao.observeKeys()

    suspend fun generateEd25519(name: String): SshKeyEntity {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
        val pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME).generateKeyPair()
        val alias = UUID.randomUUID().toString()
        val privatePem = pem("PRIVATE KEY", pair.private.encoded)
        try {
            crypto.store(alias, privatePem)
        } finally {
            privatePem.fill(0)
        }
        val publicKey = "ssh-ed25519 ${Base64.encodeToString(encodeOpenSshKey("ssh-ed25519", pair.public.encoded), Base64.NO_WRAP)}"
        return SshKeyEntity(alias, name.trim(), publicKey).also { dao.upsert(it) }
    }

    suspend fun generateRsa(name: String, bits: Int = 3072): SshKeyEntity {
        require(bits in setOf(2048, 3072, 4096)) { "Choose an RSA key size of 2048, 3072, or 4096 bits." }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
        val pair = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME).apply { initialize(bits) }.generateKeyPair()
        val alias = UUID.randomUUID().toString()
        val privatePem = pem("PRIVATE KEY", pair.private.encoded)
        try {
            crypto.store(alias, privatePem)
        } finally {
            privatePem.fill(0)
        }
        val publicKey = pair.public as RSAPublicKey
        val blob = ByteArrayOutputStream()
        fun put(value: ByteArray) {
            blob.write(ByteBuffer.allocate(4).putInt(value.size).array())
            blob.write(value)
        }
        fun putMpint(value: java.math.BigInteger) = put(value.toByteArray())
        put("ssh-rsa".toByteArray(StandardCharsets.US_ASCII))
        putMpint(publicKey.publicExponent)
        putMpint(publicKey.modulus)
        val authorizedKey = "ssh-rsa ${Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)}"
        return SshKeyEntity(alias, name.trim(), authorizedKey, "rsa$bits").also { dao.upsert(it) }
    }

    suspend fun importPrivatePem(name: String, pem: ByteArray): SshKeyEntity {
        require(pem.size <= 64 * 1024) { "Key file is too large (64 KB max)." }
        val text = pem.toString(StandardCharsets.US_ASCII)
        require(text.contains("-----BEGIN ") && text.contains("PRIVATE KEY-----")) { "Choose an OpenSSH or PEM private key." }
        val alias = UUID.randomUUID().toString()
        crypto.store(alias, pem)
        val entity = SshKeyEntity(alias, name.trim().ifBlank { "Imported key" }, "", "unknown")
        dao.upsert(entity)
        return entity
    }

    suspend fun loadPrivatePem(alias: String): ByteArray = crypto.load(alias)

    suspend fun delete(alias: String) {
        crypto.delete(alias)
        dao.delete(alias)
    }

    private fun pem(type: String, bytes: ByteArray): ByteArray {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$encoded\n-----END $type-----\n".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun encodeOpenSshKey(type: String, encodedPublicKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val raw = encodedPublicKey.takeLast(32).toByteArray()
        fun putString(bytes: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        putString(type.toByteArray(StandardCharsets.US_ASCII))
        putString(raw)
        return out.toByteArray()
    }
}
