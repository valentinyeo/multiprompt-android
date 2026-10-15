package dev.multiprompt.companion.ssh

import android.util.Log
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.AgentHarness
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.RemoteDirectory
import dev.multiprompt.companion.model.RemoteListing
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.DissolvedSession
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.reader.TranscriptProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    data class StreamSnapshot(
        val output: String,
        val details: TmuxText.RuntimeDetails,
        val pickerOptions: List<TmuxText.ModelPickerOption>,
        val waitingForInput: Boolean,
        val alternateOn: Boolean,
        val confirmationVisible: Boolean,
        val eta: TmuxText.SessionEtaState?,
        val hookState: TmuxText.HookState?,
    )

    data class HostSessions(
        val sessions: List<TmuxSession>,
        val etaStates: Map<String, TmuxText.SessionEtaState> = emptyMap(),
    )

    suspend fun listSessions(host: HostProfile): HostSessions = withContext(Dispatchers.IO) {
        withTimeout(CONNECTION_TIMEOUT_MS) {
            withAuthenticatedClient(host) { client ->
                val result = execute(client, TmuxParser.command())
                val sessions = TmuxParser.parse(host.id, result.stdout)
                val etaStates = TmuxParser.parseEtaStates(result.stdout)
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
                HostSessions(sessions = sessions, etaStates = etaStates)
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
        agent: AgentKind = AgentKind.OTHER,
        onSnapshot: (StreamSnapshot) -> Unit,
    ) = coroutineScope {
        val session = client.openSession()
            ?: throw SshProblem.Connection("The SSH server refused a reader channel")
        try {
            if (!session.requestExec(TmuxCommands.stream(sessionName))) {
                throw SshProblem.Connection("The SSH server refused the tmux reader")
            }
            val err = async { session.stderr.drain() }
            var alternateOn = false
            var etaState: TmuxText.SessionEtaState? = null
            var hookState: TmuxText.HookState? = null
            val pending = StringBuilder()
            for (chunk in session.stdout) {
                pending.append(chunk.toString(Charsets.UTF_8))
                while (true) {
                    val end = pending.indexOf("\n")
                    if (end < 0) break
                    val line = pending.substring(0, end).trimEnd('\r')
                    pending.delete(0, end + 1)
                    if (line.startsWith(TmuxCommands.ALT_PREFIX)) {
                        alternateOn = line.removePrefix(TmuxCommands.ALT_PREFIX).trim() == "1"
                        continue
                    }
                    if (line.startsWith(TmuxCommands.ETA_PREFIX)) {
                        val payload = line.removePrefix(TmuxCommands.ETA_PREFIX).trim()
                        val decoded = TmuxText.decodeHex(payload)
                        etaState = decoded
                            .takeIf(String::isNotBlank)
                            ?.let(TmuxText::parseSessionEtaStates)
                            ?.values
                            ?.firstOrNull()
                        hookState = decoded
                            .takeIf(String::isNotBlank)
                            ?.let(TmuxText::parseHookStates)
                            ?.values
                            ?.firstOrNull()
                        continue
                    }
                    if (line.startsWith(TmuxCommands.SNAPSHOT_PREFIX)) {
                        val encoded = line.removePrefix(TmuxCommands.SNAPSHOT_PREFIX)
                        if (encoded.length > MAX_SNAPSHOT_HEX_CHARS) {
                            throw SshProblem.Connection("The tmux snapshot was unexpectedly large")
                        }
                        val rawOutput = TmuxText.leftAligned(TmuxText.decodeHex(encoded))
                        val mobileOutput = TmuxText.withoutActiveComposer(
                            rawOutput,
                            agent,
                        )
                        onSnapshot(
                            StreamSnapshot(
                                output = mobileOutput,
                                details = TmuxText.runtimeDetails(rawOutput),
                                pickerOptions = TmuxText.modelPickerOptions(rawOutput),
                                waitingForInput = TmuxText.isWaitingForInput(rawOutput, agent),
                                alternateOn = alternateOn,
                                confirmationVisible = TmuxText.isModelSwitchConfirmation(rawOutput),
                                eta = etaState,
                                hookState = hookState,
                            ),
                        )
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

    suspend fun streamTranscript(
        client: SshClient,
        initialIdentity: TranscriptProtocol.Identity,
        onResponse: (TranscriptProtocol.Response) -> Unit,
    ) = coroutineScope {
        require(TranscriptProtocol.supports(initialIdentity.agent))
        val session = client.openSession()
            ?: throw SshProblem.Connection("The SSH server refused a transcript channel")
        try {
            if (!session.requestExec("exec python3 ~/.local/share/zigshell/mp_complete.py --serve")) {
                throw SshProblem.Connection("The SSH server refused the transcript helper")
            }
            launch { for (chunk in session.stderr) {  } }
            val framer = TranscriptProtocol.Framer()
            var identity = initialIdentity
            var token: String? = null
            var requestId = 0L
            while (isActive) {
                if (requestId % 15L == 0L) {
                    identity = initialIdentity
                    token = null
                }
                requestId++
                val response = withTimeout(20_000L) {
                    session.write(TranscriptProtocol.request(requestId, identity, token).toByteArray(Charsets.UTF_8))
                    var wire: String? = null
                    while (wire == null) {
                        val chunk = session.stdout.receiveCatching().getOrNull()
                            ?: throw SshProblem.Connection("The transcript helper is unavailable")
                        val frames = framer.feed(chunk)
                        check(frames.size <= 1 && (frames.isEmpty() || !framer.hasPending())) {
                            "Unexpected transcript response"
                        }
                        wire = frames.firstOrNull()
                    }
                    TranscriptProtocol.parse(wire, requestId, identity)
                        ?: throw SshProblem.Connection("Invalid transcript response")
                }
                check(response.status != "same" || token != null) { "Unexpected unchanged transcript" }
                if (response.status == "missing") {
                    identity = initialIdentity
                    token = null
                } else {
                    identity = identity.copy(sid = response.sid)
                    token = response.token
                }
                onResponse(response)
                delay(2_000L)
            }
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
                    TmuxAction.SCROLL_UP -> "scroll the agent history up"
                    TmuxAction.SCROLL_BOTTOM -> "return to the live view"
                },
            )
    }

    suspend fun createClaudeSession(host: HostProfile, remotePath: String): String =
        createAgentSession(host, remotePath, AgentHarness.CLAUDE)

    suspend fun createShellSession(host: HostProfile, remotePath: String): String =
        createAgentSession(host, remotePath, AgentHarness.SHELL)

    suspend fun createAgentSession(
        host: HostProfile,
        remotePath: String,
        harness: AgentHarness,
    ): String = withContext(Dispatchers.IO) {
        withTimeout(CONNECTION_TIMEOUT_MS) {
            withAuthenticatedClient(host) { client ->
                val project = remotePath.substringAfterLast('/').lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .ifBlank { "workspace" }
                    .take(24)
                val sessionName = "${harness.namePrefix}-$project-${System.currentTimeMillis().toString(36)}"
                val result = execute(client, TmuxCommands.createAgentSession(sessionName, remotePath, harness))
                result.requireSuccess("create the ${harness.label} session")
                require(result.stdout.lineSequence().any {
                    it.trim() == "${TmuxCommands.CREATED_PREFIX}$sessionName"
                }) { "The VPS did not confirm the new tmux session" }
                sessionName
            }
        }
    }

    suspend fun listDirectories(host: HostProfile, path: String? = null): RemoteListing =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    val result = execute(client, TmuxCommands.listDirectories(path))
                    result.requireSuccess("list the folders in ${path ?: "the home directory"}")
                    var root = path.orEmpty()
                    val directories = buildList {
                        result.stdout.lineSequence()
                            .filter(String::isNotBlank)
                            .forEach { line ->
                                val separator = line.indexOf('\t')
                                val name = if (separator > 0) line.substring(0, separator) else line.trim()
                                when {
                                    name == DIRECTORY_ROOT_MARKER -> {
                                        root = line.substring(separator + 1).trim()
                                    }
                                    name.isNotBlank() -> add(
                                        RemoteDirectory(
                                            name = name,
                                            isRepo = separator > 0 &&
                                                line.substring(separator + 1).trim() == "1",
                                        ),
                                    )
                                }
                            }
                    }.take(MAX_DIRECTORY_ENTRIES)
                    RemoteListing(root = root, directories = directories)
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

    suspend fun latestClaudeSessionId(host: HostProfile, workingDirectory: String): String? =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(LAUNCH_LOOKUP_TIMEOUT_MS) {
                    withAuthenticatedClient(host) { client ->
                        execute(client, TmuxCommands.latestClaudeSessionId(workingDirectory))
                            .stdout.trim()
                            .lineSequence()
                            .firstOrNull { it.isNotBlank() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                null
            }
        }

    suspend fun etaAccuracy(host: HostProfile): List<TmuxText.AgentAccuracy> =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    val result = execute(client, TmuxCommands.etaLogCommand())
                    result.requireSuccess("read the estimate log")
                    TmuxText.etaAccuracy(TmuxText.decodeHex(result.stdout.trim()))
                }
            }
        }

    suspend fun resurrectSession(host: HostProfile, session: DissolvedSession) =
        withContext(Dispatchers.IO) {
            require(session.resumeCommand.isNotBlank()) { "This agent does not expose a resume command" }
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    execute(
                        client,
                        TmuxCommands.resurrectSession(
                            sessionName = session.tmuxSessionName,
                            displayName = session.displayName,
                            workingDirectory = session.workingDirectory,
                            resumeCommand = session.resumeCommand,
                        ),
                    ).requireSuccess("restore the archived session")
                }
            }
        }

    suspend fun renameSession(host: HostProfile, sessionName: String, displayName: String) =
        withContext(Dispatchers.IO) {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withAuthenticatedClient(host) { client ->
                    sendPrompt(client, sessionName, "/rename $displayName")
                    execute(client, TmuxCommands.renameWindow(sessionName, displayName))
                        .requireSuccess("rename the tmux window")
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

    suspend fun selectModelPickerOption(client: SshClient, sessionName: String, index: Int) {
        execute(client, TmuxCommands.modelPickerOption(sessionName, index))
            .requireSuccess("select the model picker option")
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
        const val MAX_DIRECTORY_ENTRIES = 500
        const val LAUNCH_LOOKUP_TIMEOUT_MS = 8_000L
        const val DIRECTORY_ROOT_MARKER = "@root"
    }
}
