package dev.multiprompt.companion.ssh

import android.util.Log
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SessionExit
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
                val result = execute(client, TmuxParser.command())
                val sessions = TmuxParser.parse(host.id, result.stdout)
                Log.i(
                    LOG_TAG,
                    "tmux stdoutBytes=${result.stdout.toByteArray().size}, " +
                        "stderrBytes=${result.stderr.toByteArray().size}, sessions=${sessions.size}",
                )
                TmuxParser.error(result.stdout)?.let { error ->
                    throw SshProblem.Connection("The VPS cannot run tmux: $error")
                }
                if (!TmuxParser.hasEnvelope(result.stdout)) {
                    val detail = result.stderr.trim().ifBlank { result.stdout.trim() }
                    if (detail.isNotBlank()) {
                        throw SshProblem.Connection("tmux: ${detail.lines().first().take(200)}")
                    }
                    throw SshProblem.Connection("The VPS returned an incomplete tmux response. Refresh to retry.")
                }
                if (sessions.isEmpty() && result.stderr.isNotBlank()) {
                    throw SshProblem.Connection("tmux: ${result.stderr.trim().lines().first().take(200)}")
                }
                sessions
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

    suspend fun captureSession(client: SshClient, sessionName: String): String {
        val result = execute(client, TmuxCommands.capture(sessionName))
        result.requireSuccess("capture session output")
        return result.stdout.trimEnd()
    }

    suspend fun streamSession(
        client: SshClient,
        sessionName: String,
        onSnapshot: (String) -> Unit,
    ) = coroutineScope {
        val session = client.openSession()
            ?: throw SshProblem.Connection("The SSH server refused a reader channel")
        try {
            if (!session.requestExec(TmuxCommands.stream(sessionName))) {
                throw SshProblem.Connection("The SSH server refused the tmux reader")
            }
            val err = async { session.stderr.drain() }
            val pending = StringBuilder()
            for (chunk in session.stdout) {
                pending.append(chunk.toString(Charsets.UTF_8))
                while (true) {
                    val end = pending.indexOf("\n")
                    if (end < 0) break
                    val line = pending.substring(0, end).trimEnd('\r')
                    pending.delete(0, end + 1)
                    if (line.startsWith(TmuxCommands.SNAPSHOT_PREFIX)) {
                        val encoded = line.removePrefix(TmuxCommands.SNAPSHOT_PREFIX)
                        if (encoded.length > MAX_SNAPSHOT_HEX_CHARS) {
                            throw SshProblem.Connection("The tmux snapshot was unexpectedly large")
                        }
                        val mobileOutput = TmuxText.withoutActiveComposer(
                            TmuxText.leftAligned(TmuxText.decodeHex(encoded)),
                        )
                        onSnapshot(mobileOutput)
                    }
                }
                if (pending.length > MAX_SNAPSHOT_HEX_CHARS + TmuxCommands.SNAPSHOT_PREFIX.length) {
                    throw SshProblem.Connection("The tmux snapshot was unexpectedly large")
                }
            }
            CommandResult(
                stdout = "",
                stderr = err.await(),
                exit = session.exitInfo.await(),
            ).requireSuccess("keep the session reader connected")
        } finally {
            session.close()
        }
    }

    suspend fun sendPrompt(client: SshClient, sessionName: String, prompt: String) {
        require(prompt.isNotBlank()) { "Enter a prompt first" }
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PROMPT_BYTES) { "The prompt is too large" }
        execute(client, TmuxCommands.pastePrompt(sessionName), stdin = bytes)
            .requireSuccess("send the prompt")
    }

    suspend fun performAction(client: SshClient, sessionName: String, action: TmuxAction) {
        execute(client, TmuxCommands.action(sessionName, action))
            .requireSuccess(
                when (action) {
                    TmuxAction.ENTER -> "send Enter"
                    TmuxAction.INTERRUPT -> "interrupt the session"
                },
            )
    }

    suspend fun createClaudeSession(host: HostProfile, remotePath: String): String =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    val project = remotePath.substringAfterLast('/').lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .ifBlank { "workspace" }
                        .take(24)
                    val sessionName = "claude-$project-${System.currentTimeMillis().toString(36)}"
                    val result = execute(client, TmuxCommands.createClaudeSession(sessionName, remotePath))
                    result.requireSuccess("create the Claude session")
                    require(result.stdout.lineSequence().any {
                        it.trim() == "${TmuxCommands.CREATED_PREFIX}$sessionName"
                    }) { "The VPS did not confirm the new tmux session" }
                    sessionName
                }
            }
        }

    suspend fun createShellSession(host: HostProfile, remotePath: String): String =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    val project = remotePath.substringAfterLast('/').lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .ifBlank { "workspace" }
                        .take(24)
                    val sessionName = "shell-$project-${System.currentTimeMillis().toString(36)}"
                    val result = execute(client, TmuxCommands.createShellSession(sessionName, remotePath))
                    result.requireSuccess("create the terminal session")
                    require(result.stdout.lineSequence().any {
                        it.trim() == "${TmuxCommands.CREATED_PREFIX}$sessionName"
                    }) { "The VPS did not confirm the new tmux session" }
                    sessionName
                }
            }
        }

    suspend fun dissolveSession(host: HostProfile, sessionName: String) =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    execute(client, TmuxCommands.dissolveSession(sessionName))
                        .requireSuccess("dissolve the tmux session")
                }
            }
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

    private data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exit: SessionExit?,
    ) {
        fun requireSuccess(operation: String) {
            val status = exit as? SessionExit.Status
            if (status == null || status.code != 0L) {
                val detail = stderr.trim().lineSequence().firstOrNull().orEmpty().take(200)
                throw SshProblem.Connection(
                    if (detail.isBlank()) "Could not $operation" else "Could not $operation: $detail",
                )
            }
        }
    }

    private suspend fun execute(
        client: SshClient,
        command: String,
        stdin: ByteArray? = null,
    ): CommandResult = coroutineScope {
        val session = client.openSession()
            ?: throw SshProblem.Connection("The SSH server refused a command channel")
        try {
            if (!session.requestExec(command)) {
                throw SshProblem.Connection("The SSH server refused the tmux query")
            }
            val out = async { session.stdout.drain() }
            val err = async { session.stderr.drain() }
            if (stdin != null) {
                session.write(stdin)
                session.sendEof()
            }
            CommandResult(
                stdout = out.await(),
                stderr = err.await(),
                exit = session.exitInfo.await(),
            )
        } finally {
            session.close()
        }
    }

    private suspend fun ReceiveChannel<ByteArray>.drain(): String {
        val buffer = ByteArrayOutputStream()
        for (chunk in this) {
            if (buffer.size() + chunk.size > MAX_COMMAND_OUTPUT) {
                throw SshProblem.Connection("The tmux session list was unexpectedly large")
            }
            buffer.write(chunk)
        }
        return buffer.toString(Charsets.UTF_8.name())
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
        const val LOG_TAG = "MultipromptSSH"
        const val CONNECTION_TIMEOUT_MS = 20_000L
        const val MAX_COMMAND_OUTPUT = 2 * 1024 * 1024
        const val MAX_PROMPT_BYTES = 64 * 1024
        const val MAX_SNAPSHOT_HEX_CHARS = 1024 * 1024
    }
}
