package com.terminuke.app.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.Resource
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import android.util.Base64
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class HostKeyInfo(val hostname: String, val port: Int, val keyType: String, val fingerprint: String)

class RemoteShell(
    private val shell: Session.Shell,
    private val sshClient: SSHClient,
    private val sshSession: Session,
) {
    val input: InputStream = PipedInputStream(64 * 1024)
    val output: OutputStream = shell.outputStream
    private val mergedOutput = PipedOutputStream(input as PipedInputStream)
    private val resizeExecutor = Executors.newSingleThreadExecutor()
    private val streamExecutor = Executors.newFixedThreadPool(2)
    private val streamsRemaining = AtomicInteger(2)

    init {
        pump(shell.inputStream)
        pump(shell.errorStream)
    }

    private fun pump(source: InputStream) {
        streamExecutor.execute {
            try {
                source.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(mergedOutput) { mergedOutput.write(buffer, 0, count); mergedOutput.flush() }
                    }
                }
            } catch (_: Exception) {
                // Session closure ends both stream pumps.
            } finally {
                if (streamsRemaining.decrementAndGet() == 0) runCatching { mergedOutput.close() }
            }
        }
    }

    fun resize(columns: Int, rows: Int) {
        resizeExecutor.execute { runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) } }
    }

    fun close() {
        resizeExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        runCatching { input.close() }
        runCatching { sshSession.close() }
        runCatching { sshClient.disconnect() }
    }
}

class SshClient {
    fun connect(
        host: String,
        port: Int,
        username: String,
        password: CharArray?,
        privateKeyPem: ByteArray?,
        keyPassphrase: CharArray?,
        interactiveAuth: Boolean,
        trustVerifier: (HostKeyInfo) -> Boolean,
        interactiveResponse: (String, Boolean) -> CharArray,
    ): RemoteShell {
        val client = SSHClient()
        client.connectTimeout = 15_000
        client.timeout = 0
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, remotePort: Int, publicKey: PublicKey): Boolean =
                trustVerifier(HostKeyInfo(hostname, remotePort, sshKeyType(publicKey), fingerprint(publicKey)))

            override fun findExistingAlgorithms(hostname: String, remotePort: Int): List<String> = emptyList()
        })
        try {
            client.connect(host, port)
            when {
                privateKeyPem != null -> {
                    val provider = client.loadKeys(privateKeyPem.toString(Charsets.US_ASCII), keyPassphrase)
                    client.authPublickey(username, provider)
                }
                interactiveAuth -> {
                    val responses = mutableListOf<CharArray>()
                    val provider = object : ChallengeResponseProvider {
                        override fun getSubmethods(): List<String> = emptyList()
                        override fun init(resource: Resource<*>?, name: String?, instruction: String?) = Unit
                        override fun getResponse(prompt: String, echo: Boolean): CharArray =
                            interactiveResponse(prompt, echo).also(responses::add)
                        override fun shouldRetry(): Boolean = false
                    }
                    try {
                        client.auth(username, AuthKeyboardInteractive(provider))
                    } finally {
                        responses.forEach { it.fill('\u0000') }
                    }
                }
                password != null -> client.authPassword(username, password)
                else -> error("Choose a password or private key to authenticate.")
            }
            val session = client.startSession()
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val shell = session.startShell()
            return RemoteShell(shell, client, session)
        } catch (error: Exception) {
            runCatching { client.disconnect() }
            throw error
        }
    }

    private fun fingerprint(key: PublicKey): String = "SHA256:" + Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(encodeSshPublicKey(key)),
        Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun sshKeyType(key: PublicKey): String = when (key) {
        is RSAPublicKey -> "ssh-rsa"
        is ECPublicKey -> "ecdsa-sha2-nistp${key.params.curve.field.fieldSize}"
        else -> if (key.algorithm.equals("EdDSA", ignoreCase = true) || key.algorithm.equals("Ed25519", ignoreCase = true)) {
            "ssh-ed25519"
        } else {
            throw IllegalArgumentException("Unsupported SSH server host-key algorithm: ${key.algorithm}")
        }
    }

    private fun encodeSshPublicKey(key: PublicKey): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        fun putString(value: ByteArray) { data.writeInt(value.size); data.write(value) }
        fun putMpint(value: BigInteger) {
            val bytes = value.toByteArray()
            putString(bytes)
        }
        when (key) {
            is RSAPublicKey -> {
                putString("ssh-rsa".toByteArray())
                putMpint(key.publicExponent)
                putMpint(key.modulus)
            }
            is ECPublicKey -> {
                val curve = "nistp${key.params.curve.field.fieldSize}"
                putString("ecdsa-sha2-$curve".toByteArray())
                putString(curve.toByteArray())
                val coordinateSize = (key.params.curve.field.fieldSize + 7) / 8
                val point = byteArrayOf(4) + key.w.affineX.toFixedBytes(coordinateSize) + key.w.affineY.toFixedBytes(coordinateSize)
                putString(point)
            }
            else -> {
                require(sshKeyType(key) == "ssh-ed25519") { "Unsupported SSH server host-key algorithm: ${key.algorithm}" }
                putString("ssh-ed25519".toByteArray())
                // RFC 8410 SubjectPublicKeyInfo ends with the raw 32-byte Ed25519 public key.
                putString(key.encoded.takeLast(32).toByteArray())
            }
        }
        return output.toByteArray()
    }

    private fun BigInteger.toFixedBytes(size: Int): ByteArray {
        val bytes = toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
        return ByteArray(size - bytes.size) + bytes
    }
}
