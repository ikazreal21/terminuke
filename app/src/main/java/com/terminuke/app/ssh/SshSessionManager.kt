package com.terminuke.app.ssh

import com.terminuke.app.data.db.HostDao
import com.terminuke.app.data.db.HostEntity
import com.terminuke.app.data.db.KnownHostDao
import com.terminuke.app.data.db.KnownHostEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface SessionState {
    data object Disconnected : SessionState
    data class Connecting(val hostLabel: String) : SessionState
    data class AwaitingHostTrust(val key: HostKeyInfo) : SessionState
    data class AwaitingAuthResponse(val prompt: String, val echo: Boolean) : SessionState
    data class Connected(val host: HostEntity, val shell: RemoteShell) : SessionState
    data class Failed(val message: String) : SessionState
}

class SshSessionManager(private val hostDao: HostDao, private val knownHostDao: KnownHostDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sshClient = SshClient()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private var trustDecision: CompletableDeferred<TrustDecision>? = null
    private var authResponse: CompletableDeferred<CharArray>? = null
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var generation = 0L

    fun connect(
        host: HostEntity,
        password: CharArray?,
        privatePem: ByteArray?,
        keyPassphrase: CharArray? = null,
    ) {
        disconnect()
        val attempt = generation
        mutableState.value = SessionState.Connecting(host.label)
        connectionJob = scope.launch {
            try {
                val shell = withContext(Dispatchers.IO) {
                    sshClient.connect(
                        host.hostname, host.port, host.username, password, privatePem, keyPassphrase,
                        interactiveAuth = host.authType == "keyboard-interactive",
                        trustVerifier = { keyInfo ->
                        val saved = runBlocking { knownHostDao.get(keyInfo.hostname, keyInfo.port) }
                        if (saved != null) {
                            if (saved.fingerprintSha256 == keyInfo.fingerprint) true
                            else {
                                mutableState.value = SessionState.Failed("Server key changed. Expected ${saved.fingerprintSha256}, received ${keyInfo.fingerprint}. Connection rejected.")
                                false
                            }
                        } else {
                            val answer = CompletableDeferred<TrustDecision>()
                            trustDecision = answer
                            if (generation == attempt) mutableState.value = SessionState.AwaitingHostTrust(keyInfo)
                            val decision = runBlocking { withTimeout(120_000) { answer.await() } }
                            if (decision == TrustDecision.ALWAYS) runBlocking {
                                    knownHostDao.trust(
                                        KnownHostEntity(keyInfo.hostname, keyInfo.port, keyInfo.keyType, keyInfo.fingerprint),
                                    )
                            }
                            decision != TrustDecision.REJECT
                        }
                    },
                        interactiveResponse = { prompt, echo ->
                            val answer = CompletableDeferred<CharArray>()
                            authResponse = answer
                            if (generation == attempt) mutableState.value = SessionState.AwaitingAuthResponse(prompt, echo)
                            runBlocking { withTimeout(120_000) { answer.await() } }
                        },
                    )
                }
                if (generation == attempt) {
                    runCatching { hostDao.touchConnected(host.id, System.currentTimeMillis()) }
                    mutableState.value = SessionState.Connected(host, shell)
                } else shell.close()
            } catch (error: Exception) {
                if (generation == attempt && mutableState.value !is SessionState.Failed) {
                    mutableState.value = SessionState.Failed(error.message ?: "SSH connection failed.")
                }
            } finally {
                password?.fill('\u0000')
                privatePem?.fill(0)
                keyPassphrase?.fill('\u0000')
                if (generation == attempt) {
                    trustDecision = null
                    authResponse = null
                }
            }
        }
    }

    fun answerHostTrust(trust: Boolean, always: Boolean = false) {
        trustDecision?.complete(if (!trust) TrustDecision.REJECT else if (always) TrustDecision.ALWAYS else TrustDecision.ONCE)
    }

    fun answerAuthPrompt(response: CharArray) {
        authResponse?.complete(response)
    }

    fun disconnect() {
        generation++
        trustDecision?.complete(TrustDecision.REJECT)
        authResponse?.complete(charArrayOf())
        connectionJob?.cancel()
        (mutableState.value as? SessionState.Connected)?.shell?.close()
        mutableState.value = SessionState.Disconnected
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}

private enum class TrustDecision { ONCE, ALWAYS, REJECT }
