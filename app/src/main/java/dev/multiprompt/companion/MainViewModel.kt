package dev.multiprompt.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.ssh.PresentedHostKey
import dev.multiprompt.companion.ssh.SshProblem
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.update.UpdateManager
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.upload.ScreencastUploader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppSection { SESSIONS, HOSTS, UPDATE }

data class AppUiState(
    val section: AppSection = AppSection.SESSIONS,
    val hosts: List<HostProfile> = emptyList(),
    val sessions: List<TmuxSession> = emptyList(),
    val hostErrors: Map<String, String> = emptyMap(),
    val pendingHostKeys: Map<String, PresentedHostKey> = emptyMap(),
    val refreshing: Boolean = false,
    val editorHost: HostProfile? = null,
    val editorVisible: Boolean = false,
    val editorError: String? = null,
    val terminal: TerminalConnection? = null,
    val terminalSession: TmuxSession? = null,
    val reader: SessionReaderConnection? = null,
    val readerSession: TmuxSession? = null,
    val unreadSessionKeys: Set<String> = emptySet(),
    val archivedSessionKeys: Set<String> = emptySet(),
    val showingArchivedSessions: Boolean = false,
    val workspaces: List<Workspace> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val sessionWorkspaceIds: Map<String, String> = emptyMap(),
    val creatingSession: Boolean = false,
    val sessionActionError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MultipromptApplication
    private val hosts = app.hostStore
    private val secrets = app.secretStore
    private val sessionReads = app.sessionReadStore
    private val workspaceStore = app.workspaceStore
    private val ssh = app.sshRepository
    val dictation: DeepgramDictation = app.deepgramDictation
    val updates: UpdateManager = app.updateManager
    val screencast: ScreencastUploader = app.screencastUploader

    private val _state = MutableStateFlow(
        AppUiState(hosts = hosts.load(), workspaces = workspaceStore.load()),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        updates.check()
        if (_state.value.hosts.isNotEmpty()) refresh()
        viewModelScope.launch {
            while (true) {
                delay(SESSION_REFRESH_INTERVAL_MS)
                if (_state.value.hosts.isNotEmpty()) refresh()
            }
        }
    }

    fun select(section: AppSection) {
        _state.update { it.copy(section = section) }
    }

    fun showHostEditor(host: HostProfile? = null) {
        _state.update { it.copy(editorHost = host, editorVisible = true, editorError = null) }
    }

    fun hideHostEditor() {
        _state.update { it.copy(editorVisible = false, editorHost = null, editorError = null) }
    }

    fun saveHost(draft: HostDraft, importedKey: ByteArray?) {
        val port = draft.port.toIntOrNull()
        val existing = draft.id?.let { id -> _state.value.hosts.firstOrNull { it.id == id } }
        val error = when {
            draft.label.isBlank() -> "Enter a label"
            draft.hostname.isBlank() -> "Enter a hostname"
            port == null || port !in 1..65535 -> "Enter a valid SSH port"
            draft.username.isBlank() -> "Enter an SSH username"
            importedKey == null && existing == null -> "Import an OpenSSH private key"
            importedKey != null && importedKey.size > MAX_PRIVATE_KEY_BYTES -> "Private key is too large"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(editorError = error) }
            return
        }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val keySecretId = existing?.keySecretId ?: "host_key_$id"
        val passphraseSecretId = when {
            draft.passphrase.isNotEmpty() -> "host_passphrase_$id"
            importedKey != null -> null
            else -> existing?.passphraseSecretId
        }
        importedKey?.let { secrets.put(keySecretId, it) }
        if (draft.passphrase.isNotEmpty()) {
            secrets.put(passphraseSecretId!!, draft.passphrase.toByteArray())
        } else if (importedKey != null) {
            secrets.remove(existing?.passphraseSecretId)
        }
        val connectionIdentityChanged = existing != null &&
            (existing.hostname != draft.hostname.trim() || existing.port != port)
        val profile = HostProfile(
            id = id,
            label = draft.label.trim(),
            hostname = draft.hostname.trim(),
            port = port!!,
            username = draft.username.trim(),
            keySecretId = keySecretId,
            passphraseSecretId = passphraseSecretId,
            hostKeyType = if (connectionIdentityChanged) null else existing?.hostKeyType,
            hostKeyFingerprint = if (connectionIdentityChanged) null else existing?.hostKeyFingerprint,
        )
        hosts.upsert(profile)
        _state.update {
            it.copy(
                hosts = hosts.load(),
                editorVisible = false,
                editorHost = null,
                editorError = null,
                section = AppSection.SESSIONS,
            )
        }
        refresh()
    }

    fun deleteHost(host: HostProfile) {
        hosts.delete(host.id)
        secrets.remove(host.keySecretId)
        secrets.remove(host.passphraseSecretId)
        sessionReads.removeHost(host.id)
        workspaceStore.removeHost(host.id)
        _state.update {
            it.copy(
                hosts = hosts.load(),
                sessions = it.sessions.filterNot { session -> session.hostId == host.id },
                hostErrors = it.hostErrors - host.id,
                pendingHostKeys = it.pendingHostKeys - host.id,
            )
        }
    }

    fun trustHostKey(hostId: String) {
        val presented = _state.value.pendingHostKeys[hostId] ?: return
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        hosts.upsert(
            host.copy(
                hostKeyType = presented.type,
                hostKeyFingerprint = presented.fingerprint,
            ),
        )
        _state.update {
            it.copy(
                hosts = hosts.load(),
                pendingHostKeys = it.pendingHostKeys - hostId,
                hostErrors = it.hostErrors - hostId,
            )
        }
        refresh()
    }

    fun dismissHostKey(hostId: String) {
        _state.update {
            it.copy(
                pendingHostKeys = it.pendingHostKeys - hostId,
                hostErrors = it.hostErrors - hostId,
            )
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        val snapshot = _state.value.hosts
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val results = snapshot.map { host ->
                async {
                    runCatching { ssh.listSessions(host) }
                        .fold(
                            onSuccess = { HostRefresh(host.id, it) },
                            onFailure = { throwable ->
                                HostRefresh(
                                    hostId = host.id,
                                    error = throwable.message ?: "SSH failed",
                                    hostKey = (throwable as? SshProblem.HostKeyRequired)?.presented,
                                )
                            },
                        )
                }
            }.awaitAll()
            _state.update { current ->
                val sessions = results.flatMap { it.sessions }
                val workspaces = workspaceStore.discover(sessions)
                val sessionWorkspaceIds = sessions.mapNotNull { session ->
                    workspaceStore.workspaceIdFor(session, workspaces)?.let { workspaceId ->
                        SessionReadStore.key(session.hostId, session.name) to workspaceId
                    }
                }.toMap()
                val archivedKeys = sessions
                    .filter(sessionReads::isArchived)
                    .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) }
                current.copy(
                    sessions = sessions,
                    readerSession = current.readerSession?.let { open ->
                        sessions.firstOrNull { it.hostId == open.hostId && it.name == open.name } ?: open
                    },
                    terminalSession = current.terminalSession?.let { open ->
                        sessions.firstOrNull { it.hostId == open.hostId && it.name == open.name } ?: open
                    },
                    hostErrors = results.mapNotNull { result ->
                        result.error?.let { result.hostId to it }
                    }.toMap(),
                    pendingHostKeys = results.mapNotNull { result ->
                        result.hostKey?.let { result.hostId to it }
                    }.toMap(),
                    unreadSessionKeys = sessions
                        .filter { SessionReadStore.key(it.hostId, it.name) !in archivedKeys }
                        .filter(sessionReads::isUnread)
                        .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) },
                    archivedSessionKeys = archivedKeys,
                    workspaces = workspaces,
                    sessionWorkspaceIds = sessionWorkspaceIds,
                    refreshing = false,
                )
            }
        }
    }

    fun openReader(session: TmuxSession) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        sessionReads.markRead(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.value.terminal?.close()
        _state.value.reader?.close()
        val reader = SessionReaderConnection(
            repository = ssh,
            host = host,
            tmuxSessionName = session.name,
        ).also { it.start() }
        _state.update {
            it.copy(
                terminal = null,
                terminalSession = null,
                reader = reader,
                readerSession = session,
                unreadSessionKeys = it.unreadSessionKeys - key,
            )
        }
    }

    fun markReaderRead() {
        val session = _state.value.readerSession ?: return
        sessionReads.markRead(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update { it.copy(unreadSessionKeys = it.unreadSessionKeys - key) }
    }

    fun markSessionUnread(session: TmuxSession) {
        sessionReads.markUnread(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update { it.copy(unreadSessionKeys = it.unreadSessionKeys + key) }
    }

    fun archiveSession(session: TmuxSession) {
        sessionReads.archive(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update {
            it.copy(
                archivedSessionKeys = it.archivedSessionKeys + key,
                unreadSessionKeys = it.unreadSessionKeys - key,
            )
        }
    }

    fun restoreSession(session: TmuxSession) {
        sessionReads.restore(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update { it.copy(archivedSessionKeys = it.archivedSessionKeys - key) }
    }

    fun toggleArchivedSessions() {
        _state.update { it.copy(showingArchivedSessions = !it.showingArchivedSessions) }
    }

    fun selectWorkspace(workspaceId: String?) {
        _state.update { it.copy(selectedWorkspaceId = workspaceId) }
    }

    fun openAdjacentWorkspace(delta: Int) {
        val splits = listOf<String?>(null) + _state.value.workspaces.map { it.id }
        if (splits.size < 2) return
        val current = splits.indexOf(_state.value.selectedWorkspaceId).coerceAtLeast(0)
        selectWorkspace(splits[Math.floorMod(current + delta, splits.size)])
    }

    fun createWorkspace(name: String, hostId: String, remotePath: String): String? {
        val cleanName = name.trim()
        val cleanPath = remotePath.trim().trimEnd('/')
        val error = when {
            cleanName.isBlank() -> "Enter a workspace name"
            _state.value.hosts.none { it.id == hostId } -> "Choose a VPS"
            !cleanPath.startsWith('/') -> "Enter an absolute VPS project path"
            else -> null
        }
        if (error != null) return error
        val workspace = Workspace(name = cleanName, hostId = hostId, remotePath = cleanPath)
        workspaceStore.upsert(workspace)
        _state.update {
            it.copy(
                workspaces = workspaceStore.load(),
                selectedWorkspaceId = workspace.id,
                sessionActionError = null,
            )
        }
        return null
    }

    fun moveSession(session: TmuxSession, workspace: Workspace) {
        workspaceStore.assign(session, workspace.id)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update { it.copy(sessionWorkspaceIds = it.sessionWorkspaceIds + (key to workspace.id)) }
    }

    fun createClaudeSession(workspace: Workspace) {
        if (_state.value.creatingSession) return
        val host = _state.value.hosts.firstOrNull { it.id == workspace.hostId }
        if (host == null) {
            _state.update { it.copy(sessionActionError = "The workspace VPS is missing") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(creatingSession = true, sessionActionError = null) }
            runCatching { ssh.createClaudeSession(host, workspace.remotePath) }
                .onSuccess { sessionName ->
                    val session = TmuxSession(
                        hostId = host.id,
                        name = sessionName,
                        windows = 1,
                        attachedClients = 0,
                        lastActivityEpochSeconds = System.currentTimeMillis() / 1000,
                        workingDirectory = workspace.remotePath,
                    )
                    workspaceStore.assign(session, workspace.id)
                    val key = SessionReadStore.key(session.hostId, session.name)
                    _state.update {
                        it.copy(
                            sessions = (it.sessions + session).distinctBy { item ->
                                SessionReadStore.key(item.hostId, item.name)
                            },
                            sessionWorkspaceIds = it.sessionWorkspaceIds + (key to workspace.id),
                            creatingSession = false,
                        )
                    }
                    openReader(session)
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            creatingSession = false,
                            sessionActionError = throwable.message ?: "Could not create the Claude session",
                        )
                    }
                }
        }
    }

    fun createShellSession(workspace: Workspace) {
        if (_state.value.creatingSession) return
        val host = _state.value.hosts.firstOrNull { it.id == workspace.hostId }
        if (host == null) {
            _state.update { it.copy(sessionActionError = "The workspace VPS is missing") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(creatingSession = true, sessionActionError = null) }
            runCatching { ssh.createShellSession(host, workspace.remotePath) }
                .onSuccess { sessionName ->
                    val session = TmuxSession(
                        hostId = host.id,
                        name = sessionName,
                        windows = 1,
                        attachedClients = 0,
                        lastActivityEpochSeconds = System.currentTimeMillis() / 1000,
                        workingDirectory = workspace.remotePath,
                    )
                    workspaceStore.assign(session, workspace.id)
                    val key = SessionReadStore.key(session.hostId, session.name)
                    _state.update {
                        it.copy(
                            sessions = (it.sessions + session).distinctBy { item ->
                                SessionReadStore.key(item.hostId, item.name)
                            },
                            sessionWorkspaceIds = it.sessionWorkspaceIds + (key to workspace.id),
                            creatingSession = false,
                        )
                    }
                    openTerminal(session)
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            creatingSession = false,
                            sessionActionError = throwable.message ?: "Could not create the terminal session",
                        )
                    }
                }
        }
    }

    fun closeReader() {
        _state.value.reader?.close()
        _state.update { it.copy(reader = null, readerSession = null) }
    }

    /** Archives the current thread and immediately advances through the remaining inbox. */
    fun archiveReaderAndOpenNext() {
        val current = _state.value.readerSession ?: return
        val visible = visibleInboxSessions(_state.value)
        val currentIndex = visible.indexOfFirst {
            it.hostId == current.hostId && it.name == current.name
        }
        archiveSession(current)
        val remaining = visible.filterNot {
            it.hostId == current.hostId && it.name == current.name
        }
        if (remaining.isEmpty()) {
            closeReader()
        } else {
            val nextIndex = if (currentIndex in remaining.indices) currentIndex else 0
            openReader(remaining[nextIndex])
        }
    }

    /** Swiping the reader sideways opens the neighbouring visible inbox session. */
    fun openAdjacentReaderSession(delta: Int) {
        val current = _state.value.readerSession ?: return
        val sessions = visibleInboxSessions(_state.value)
        if (sessions.size < 2) return
        val index = sessions.indexOfFirst { it.hostId == current.hostId && it.name == current.name }
        if (index < 0) return
        openReader(sessions[Math.floorMod(index + delta, sessions.size)])
    }

    fun openTerminal(session: TmuxSession) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        _state.value.terminal?.close()
        _state.value.reader?.close()
        val terminal = TerminalConnection(
            repository = ssh,
            host = host,
            tmuxSessionName = session.name,
            windowColumns = session.columns,
            windowRows = session.rows,
        ).also { it.start() }
        _state.update {
            it.copy(
                terminal = terminal,
                terminalSession = session,
                reader = null,
                readerSession = null,
            )
        }
    }

    /** Swiping the terminal sideways attaches the neighbouring session, wrapping at both ends. */
    fun openAdjacentSession(delta: Int) {
        val current = _state.value.terminalSession ?: return
        val sessions = _state.value.sessions
        if (sessions.size < 2) return
        val index = sessions.indexOfFirst { it.hostId == current.hostId && it.name == current.name }
        if (index < 0) return
        val next = Math.floorMod(index + delta, sessions.size)
        openTerminal(sessions[next])
    }

    fun closeTerminal() {
        _state.value.terminal?.close()
        _state.update { it.copy(terminal = null, terminalSession = null) }
        refresh()
    }

    fun installUpdate(release: UpdateRelease) = updates.install(release)

    override fun onCleared() {
        _state.value.terminal?.close()
        _state.value.reader?.close()
        super.onCleared()
    }

    private data class HostRefresh(
        val hostId: String,
        val sessions: List<TmuxSession> = emptyList(),
        val error: String? = null,
        val hostKey: PresentedHostKey? = null,
    )

    private fun visibleInboxSessions(state: AppUiState): List<TmuxSession> = state.sessions
        .filter { SessionReadStore.key(it.hostId, it.name) !in state.archivedSessionKeys }
        .filter { session ->
            state.selectedWorkspaceId == null ||
                state.sessionWorkspaceIds[SessionReadStore.key(session.hostId, session.name)] ==
                state.selectedWorkspaceId
        }
        .sortedByDescending { it.lastActivityEpochSeconds }

    private companion object {
        const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        const val SESSION_REFRESH_INTERVAL_MS = 10_000L
    }
}
