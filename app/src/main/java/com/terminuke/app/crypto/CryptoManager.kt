package com.terminuke.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager(context: Context) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyDirectory = File(context.filesDir, "keys").apply { mkdirs() }

    @Synchronized
    fun store(alias: String, privatePem: ByteArray) {
        val ivAndCiphertext = encrypt(alias, privatePem)
        val destination = File(keyDirectory, "$alias.enc")
        val temporary = File(keyDirectory, ".$alias.tmp")
        try {
            temporary.writeBytes(ivAndCiphertext)
            check(temporary.renameTo(destination)) { "Could not save the encrypted SSH key." }
        } finally {
            temporary.delete()
            ivAndCiphertext.fill(0)
        }
    }

    @Synchronized
    fun load(alias: String): ByteArray {
        val stored = File(keyDirectory, "$alias.enc").readBytes()
        require(stored.size > GCM_IV_BYTES) { "Encrypted key file is incomplete." }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(128, stored.copyOfRange(0, GCM_IV_BYTES)))
            doFinal(stored, GCM_IV_BYTES, stored.size - GCM_IV_BYTES)
        }
    }

    @Synchronized
    fun delete(alias: String) {
        File(keyDirectory, "$alias.enc").delete()
        keyStore.deleteEntry(keyAlias(alias))
    }

    private fun encrypt(alias: String, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        return cipher.iv + cipher.doFinal(value)
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        (keyStore.getKey(keyAlias(alias), null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias(alias),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyAlias(alias: String) = "terminuke-key-$alias"

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
    }
}
