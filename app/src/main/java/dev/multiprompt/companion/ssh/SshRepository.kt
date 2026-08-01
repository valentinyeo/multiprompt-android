package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import java.io.ByteArrayOutputStream

data class PresentedHostKey(
    val type: String,
    val fingerprint: String,
    val changed: Boolean,
)

sealed class SshProblem(message: String) : Exception(message) {
    class HostKeyRequired(val presented: PresentedHostKey) :
        SshProblem(
            if (presented.changed) {
                "Host key changed: ${presented.fingerprint}"
            } else {
                "Trust host key ${presented.fingerprint} to continue"
            },
        )

    class Connection(message: String) : SshProblem(message)
    class Authentication(message: String) : SshProblem(message)
}

class SshRepository(private val secrets: SecretStore) {
    suspend fun listSessions(host: HostProfile): List<TmuxSession> = withContext(Dispatchers.IO) {
        withTimeout(CONNECTION_TIMEOUT_MS) {
            withAuthenticatedClient(host) { client ->
                val output = execute(client, TmuxParser.command())
                TmuxParser.parse(host.id, output)
            }
        }
    }

    suspend fun connect(host: HostProfile): SshClient = withContext(Dispatchers.IO) {
        val probe = PinningHostKeyVerifier(host.hostKeyType, host.hostKeyFingerprint)
        val client = SshClient(
            SshClientConfig {
                this.host = host.hostname
                this.port = host.port
                hostKeyVerifier = probe
                clientVersion = "SSH-2.0-multiprompt_android_1"
            },
        )
        val result = client.connect()
        if (result !is ConnectResult.Success) {
            runCatching { client.disconnect() }
            probe.presented?.let { presented -> throw SshProblem.HostKeyRequired(presented) }
            throw SshProblem.Connection("Could not connect to ${host.label}: $result")
        }

        val privateKey = secrets.get(host.keySecretId)
            ?: run {
                client.disconnect()
                throw SshProblem.Authentication("The imported private key is missing")
            }
        val passphrase = host.passphraseSecretId
            ?.let(secrets::get)
            ?.toString(Charsets.UTF_8)
            ?.ifBlank { null }
        val auth = client.authenticatePublicKey(host.username, privateKey, passphrase)
        if (auth !is AuthResult.Success) {
            client.disconnect()
            throw SshProblem.Authentication("SSH key authentication failed: $auth")
        }
        client
    }

    private suspend fun <T> withAuthenticatedClient(
        host: HostProfile,
        block: suspend (SshClient) -> T,
    ): T {
        val client = connect(host)
        return try {
            block(client)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun execute(client: SshClient, command: String): String {
        val session = client.openSession()
            ?: throw SshProblem.Connection("The SSH server refused a command channel")
        return try {
            if (!session.requestExec(command)) {
                throw SshProblem.Connection("The SSH server refused the tmux query")
            }
            val output = ByteArrayOutputStream()
            while (true) {
                val chunk = session.read() ?: break
                if (output.size() + chunk.size > MAX_COMMAND_OUTPUT) {
                    throw SshProblem.Connection("The tmux session list was unexpectedly large")
                }
                output.write(chunk)
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            session.close()
        }
    }

    private class PinningHostKeyVerifier(
        private val expectedType: String?,
        private val expectedFingerprint: String?,
    ) : HostKeyVerifier {
        @Volatile
        var presented: PresentedHostKey? = null
            private set

        override suspend fun verify(key: PublicKey): Boolean {
            val fingerprint = KeyFingerprint.sha256(key.encoded)
            val accepted = expectedFingerprint != null &&
                expectedType == key.type &&
                expectedFingerprint == fingerprint
            if (!accepted) {
                presented = PresentedHostKey(
                    type = key.type,
                    fingerprint = fingerprint,
                    changed = expectedFingerprint != null,
                )
            }
            return accepted
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 20_000L
        const val MAX_COMMAND_OUTPUT = 2 * 1024 * 1024
    }
}

