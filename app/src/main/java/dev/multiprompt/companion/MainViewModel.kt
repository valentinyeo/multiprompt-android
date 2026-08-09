package dev.multiprompt.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.DissolvedSession
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.SessionSearch
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.ssh.PresentedHostKey
import dev.multiprompt.companion.ssh.SshProblem
import dev.multiprompt.companion.ssh.TmuxText
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

enum class SessionBucket { OPEN, WAITING, ARCHIVE }

data class AppUiState(
    val section: AppSection = AppSection.SESSIONS,
    val hosts: List<HostProfile> = emptyList(),
    val sessions: List<TmuxSession> = emptyList(),
    val hostErrors: Map<String, String> = emptyMap(),
    val cachedHostIds: Set<String> = emptySet(),
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
    val sessionBucket: SessionBucket = SessionBucket.OPEN,
    val dissolvedSessions: List<DissolvedSession> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val workspaceSplitIds: List<String?> = listOf(null),
    val selectedWorkspaceId: String? = null,
    val workspaceSelectionInitialized: Boolean = false,
    val sessionWorkspaceIds: Map<String, String> = emptyMap(),
    val sessionInteractionEpochSeconds: Map<String, Long> = emptyMap(),
    val creatingSession: Boolean = false,
    val sessionActionError: String? = null,
    val newestSessionsAtBottom: Boolean = true,
    val allSplitOnRight: Boolean = true,
    val readerDefaultFontScale: Float = 1f,
    val readerTechnicalMode: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MultipromptApplication
    private val hosts = app.hostStore
    private val secrets = app.secretStore
    private val sessionReads = app.sessionReadStore
    private val sessionCache = app.sessionCacheStore
    private val dissolvedStore = app.dissolvedSessionStore
    private val workspaceStore = app.workspaceStore
    private val ssh = app.sshRepository
    val dictation: DeepgramDictation = app.deepgramDictation
    val updates: UpdateManager = app.updateManager
    val screencast: ScreencastUploader = app.screencastUploader

    private val initialWorkspaces = workspaceStore.ordered(workspaceStore.load(), emptyMap())
    private val _state = MutableStateFlow(
        AppUiState(
            hosts = hosts.load(),
            workspaces = initialWorkspaces,
            workspaceSplitIds = workspaceStore.splitIds(initialWorkspaces, emptyMap()),
            newestSessionsAtBottom = sessionReads.newestSessionsAtBottom(),
            allSplitOnRight = sessionReads.allSplitOnRight(),
            readerDefaultFontScale = sessionReads.readerDefaultFontScale(),
            readerTechnicalMode = sessionReads.readerTechnicalMode(),
            dissolvedSessions = dissolvedStore.load(),
        ),
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

    fun setNewestSessionsAtBottom(enabled: Boolean) {
        sessionReads.setNewestSessionsAtBottom(enabled)
        _state.update { it.copy(newestSessionsAtBottom = enabled) }
    }

    fun setAllSplitOnRight(enabled: Boolean) {
        sessionReads.setAllSplitOnRight(enabled)
        _state.update { it.copy(allSplitOnRight = enabled) }
    }

    fun setReaderDefaultFontScale(scale: Float) {
        sessionReads.setReaderDefaultFontScale(scale)
        _state.update { it.copy(readerDefaultFontScale = sessionReads.readerDefaultFontScale()) }
    }

    fun setReaderTechnicalMode(enabled: Boolean) {
        sessionReads.setReaderTechnicalMode(enabled)
        _state.update { it.copy(readerTechnicalMode = enabled) }
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
        dissolvedStore.removeHost(host.id)
        workspaceStore.removeHost(host.id)
        _state.update {
            it.copy(
                hosts = hosts.load(),
                sessions = it.sessions.filterNot { session -> session.hostId == host.id },
                hostErrors = it.hostErrors - host.id,
                cachedHostIds = it.cachedHostIds - host.id,
                dissolvedSessions = it.dissolvedSessions.filterNot { session -> session.hostId == host.id },
                pendingHostKeys = it.pendingHostKeys - host.id,
            )
        }
        sessionCache.removeHost(host.id)
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
            val cachedByHost = snapshot.associate { host -> host.id to sessionCache.load(host.id) }
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
            results.filter { it.error == null }.forEach { result ->
                sessionCache.save(result.hostId, result.sessions)
            }
            _state.update { current ->
                val currentByHost = current.sessions.groupBy { it.hostId }
                val cachedHostIds = results.filter { result ->
                    result.error != null &&
                        (cachedByHost[result.hostId].orEmpty().isNotEmpty() ||
                            currentByHost[result.hostId].orEmpty().isNotEmpty())
                }.mapTo(mutableSetOf()) { it.hostId }
                val sessions = results.flatMap { result ->
                    if (result.error == null) {
                        result.sessions
                    } else {
                        cachedByHost[result.hostId].orEmpty().ifEmpty {
                            currentByHost[result.hostId].orEmpty()
                        }
                    }
                }.map { session ->
                    sessionReads.displayName(session)?.let { session.copy(title = it) } ?: session
                }
                val workspaces = workspaceStore.discover(sessions)
                val sessionWorkspaceIds = sessions.mapNotNull { session ->
                    workspaceStore.workspaceIdFor(session, workspaces)?.let { workspaceId ->
                        SessionReadStore.key(session.hostId, session.name) to workspaceId
                    }
                }.toMap()
                val interactionEpochSeconds = current.sessionInteractionEpochSeconds + sessions
                    .mapNotNull { session ->
                        sessionReads.lastInteractedAt(session).takeIf { it > 0 }?.let {
                            SessionReadStore.key(session.hostId, session.name) to it
                        }
                    }
                    .toMap()
                val archivedKeys = sessions
                    .filter { session ->
                        sessionReads.isArchived(
                            session,
                            needsAttention = TmuxText.isWaitingForInput(session.preview),
                        )
                    }
                    .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) }
                val orderedWorkspaces = workspaceStore.ordered(
                    workspaces,
                    sessionWorkspaceIds.values.groupingBy { it }.eachCount(),
                )
                val workspaceSplitIds = workspaceStore.splitIds(
                    orderedWorkspaces,
                    latestWorkspaceActivity(sessions, sessionWorkspaceIds, interactionEpochSeconds),
                )
                val selectedWorkspaceId = when {
                    !current.workspaceSelectionInitialized -> workspaceSplitIds.firstOrNull()
                    current.selectedWorkspaceId == null -> null
                    orderedWorkspaces.any { it.id == current.selectedWorkspaceId } ->
                        current.selectedWorkspaceId
                    else -> workspaceSplitIds.firstOrNull()
                }
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
                    cachedHostIds = cachedHostIds,
                    pendingHostKeys = results.mapNotNull { result ->
                        result.hostKey?.let { result.hostId to it }
                    }.toMap(),
                    unreadSessionKeys = sessions
                        .filter { SessionReadStore.key(it.hostId, it.name) !in archivedKeys }
                        .filter { TmuxText.isWaitingForInput(it.preview) }
                        .filter(sessionReads::isUnread)
                        .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) },
                    archivedSessionKeys = archivedKeys,
                    workspaces = orderedWorkspaces,
                    workspaceSplitIds = workspaceSplitIds,
                    selectedWorkspaceId = selectedWorkspaceId,
                    workspaceSelectionInitialized = orderedWorkspaces.isNotEmpty() ||
                        current.workspaceSelectionInitialized,
                    sessionWorkspaceIds = sessionWorkspaceIds,
                    sessionInteractionEpochSeconds = interactionEpochSeconds,
                    refreshing = false,
                )
            }
        }
    }

    fun openReader(session: TmuxSession) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        noteSessionInteraction(session)
        sessionReads.markRead(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.value.terminal?.close()
        _state.value.reader?.close()
        val reader = SessionReaderConnection(
            repository = ssh,
            host = host,
            tmuxSessionName = session.name,
            agent = session.agent,
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

    fun archiveSessionUntil(session: TmuxSession, resumeAtEpochSeconds: Long?) {
        sessionReads.archiveUntil(session, resumeAtEpochSeconds)
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
        _state.update {
            it.copy(
                sessionBucket = when (it.sessionBucket) {
                    SessionBucket.OPEN -> SessionBucket.WAITING
                    SessionBucket.WAITING -> SessionBucket.ARCHIVE
                    SessionBucket.ARCHIVE -> SessionBucket.OPEN
                },
            )
        }
    }

    fun selectSessionBucket(bucket: SessionBucket) {
        _state.update { it.copy(sessionBucket = bucket) }
    }

    fun selectWorkspace(workspaceId: String?) {
        _state.update {
            it.copy(selectedWorkspaceId = workspaceId, workspaceSelectionInitialized = true)
        }
    }

    fun noteSessionInteraction(session: TmuxSession) {
        val key = SessionReadStore.key(session.hostId, session.name)
        val at = System.currentTimeMillis() / 1000
        sessionReads.markInteracted(session, at)
        _state.update { current ->
            val interactions = current.sessionInteractionEpochSeconds + (key to at)
            current.copy(
                sessionInteractionEpochSeconds = interactions,
                workspaceSplitIds = workspaceStore.splitIds(
                    current.workspaces,
                    latestWorkspaceActivity(current.sessions, current.sessionWorkspaceIds, interactions),
                ),
            )
        }
    }

    fun moveWorkspaceSplit(workspaceId: String?, delta: Int) {
        _state.update { current ->
            current.copy(
                workspaceSplitIds = workspaceStore.moveSplit(
                    current.workspaceSplitIds,
                    workspaceId,
                    delta,
                ),
            )
        }
    }

    fun resetWorkspaceSplitOrder() {
        _state.update { current ->
            current.copy(
                workspaceSplitIds = workspaceStore.resetSplitOrder(
                    current.workspaces,
                    latestWorkspaceActivity(
                        current.sessions,
                        current.sessionWorkspaceIds,
                        current.sessionInteractionEpochSeconds,
                    ),
                ),
            )
        }
    }

    fun openAdjacentWorkspace(delta: Int) {
        val state = _state.value
        val splits = if (state.allSplitOnRight) {
            state.workspaceSplitIds.asReversed()
        } else {
            state.workspaceSplitIds
        }
        if (splits.size < 2) return
        val current = splits.indexOf(state.selectedWorkspaceId).coerceAtLeast(0)
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
            val workspaces = workspaceStore.ordered(
                workspaceStore.discover(it.sessions),
                it.sessionWorkspaceIds.values.groupingBy { id -> id }.eachCount(),
            )
            it.copy(
                workspaces = workspaces,
                workspaceSplitIds = workspaceStore.splitIds(
                    workspaces,
                    latestWorkspaceActivity(
                        it.sessions,
                        it.sessionWorkspaceIds,
                        it.sessionInteractionEpochSeconds,
                    ),
                ),
                selectedWorkspaceId = workspace.id,
                workspaceSelectionInitialized = true,
                sessionActionError = null,
            )
        }
        return null
    }

    fun moveSession(session: TmuxSession, workspace: Workspace) {
        workspaceStore.assign(session, workspace.id)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update {
            val assignments = it.sessionWorkspaceIds + (key to workspace.id)
            val workspaces = workspaceStore.ordered(
                it.workspaces,
                assignments.values.groupingBy { id -> id }.eachCount(),
            )
            it.copy(
                sessionWorkspaceIds = assignments,
                workspaces = workspaces,
                workspaceSplitIds = workspaceStore.splitIds(
                    workspaces,
                    latestWorkspaceActivity(it.sessions, assignments, it.sessionInteractionEpochSeconds),
                ),
            )
        }
    }

    fun renameSession(session: TmuxSession, requestedName: String): String? {
        val displayName = requestedName.trim()
        val error = when {
            displayName.isBlank() -> "Enter a session name"
            displayName.length > MAX_DISPLAY_NAME_LENGTH ->
                "Use $MAX_DISPLAY_NAME_LENGTH characters or fewer"
            displayName.any { it == '\n' || it == '\r' || it.code < 32 } ->
                "The name contains unsupported characters"
            else -> null
        }
        if (error != null) return error
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId }
            ?: return "The session VPS is missing"
        viewModelScope.launch {
            _state.update { it.copy(sessionActionError = null) }
            runCatching { ssh.renameSession(host, session.name, displayName) }
                .onSuccess {
                    sessionReads.setDisplayName(session, displayName)
                    _state.update { current ->
                        current.copy(
                            sessions = current.sessions.map { candidate ->
                                if (candidate.hostId == session.hostId && candidate.name == session.name) {
                                    candidate.copy(title = displayName)
                                } else {
                                    candidate
                                }
                            },
                            readerSession = current.readerSession?.let { open ->
                                if (open.hostId == session.hostId && open.name == session.name) {
                                    open.copy(title = displayName)
                                } else {
                                    open
                                }
                            },
                            terminalSession = current.terminalSession?.let { open ->
                                if (open.hostId == session.hostId && open.name == session.name) {
                                    open.copy(title = displayName)
                                } else {
                                    open
                                }
                            },
                        )
                    }
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(sessionActionError = throwable.message ?: "Could not rename the session")
                    }
                }
        }
        return null
    }

    fun readerFontScale(session: TmuxSession): Float = sessionReads.fontScale(session)

    fun saveReaderFontScale(session: TmuxSession, scale: Float) {
        sessionReads.setFontScale(session, scale)
    }

    fun resetReaderFontScale(session: TmuxSession): Float {
        sessionReads.clearFontScale(session)
        return sessionReads.fontScale(session)
    }

    fun dissolveSession(session: TmuxSession) {
        val current = _state.value
        val key = SessionReadStore.key(session.hostId, session.name)
        val dissolved = DissolvedSession.from(
            session = session,
            workspaceId = current.sessionWorkspaceIds[key],
            workspaceName = current.sessionWorkspaceIds[key]
                ?.let { id -> current.workspaces.firstOrNull { it.id == id }?.name }
                .orEmpty(),
        )
        destroySession(session, dissolved)
    }

    fun endSession(session: TmuxSession) {
        destroySession(session, dissolved = null)
    }

    private fun destroySession(session: TmuxSession, dissolved: DissolvedSession?) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId }
        if (host == null) {
            _state.update { it.copy(sessionActionError = "The session VPS is missing") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sessionActionError = null) }
            runCatching { ssh.dissolveSession(host, session.name) }
                .onSuccess {
                    val key = SessionReadStore.key(session.hostId, session.name)
                    val readerWasOpen = _state.value.readerSession?.let {
                        it.hostId == session.hostId && it.name == session.name
                    } == true
                    val terminalWasOpen = _state.value.terminalSession?.let {
                        it.hostId == session.hostId && it.name == session.name
                    } == true
                    if (readerWasOpen) _state.value.reader?.close()
                    if (terminalWasOpen) _state.value.terminal?.close()
                    dissolved?.let(dissolvedStore::upsert)
                    sessionReads.restore(session)
                    _state.update {
                        it.copy(
                            sessions = it.sessions.filterNot { candidate ->
                                candidate.hostId == session.hostId && candidate.name == session.name
                            },
                            reader = if (readerWasOpen) null else it.reader,
                            readerSession = if (readerWasOpen) null else it.readerSession,
                            terminal = if (terminalWasOpen) null else it.terminal,
                            terminalSession = if (terminalWasOpen) null else it.terminalSession,
                            unreadSessionKeys = it.unreadSessionKeys - key,
                            archivedSessionKeys = it.archivedSessionKeys - key,
                            sessionWorkspaceIds = it.sessionWorkspaceIds - key,
                            dissolvedSessions = dissolved?.let { record ->
                                it.dissolvedSessions.filterNot { old -> old.key == record.key } + record
                            } ?: it.dissolvedSessions,
                        )
                    }
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            sessionActionError = throwable.message ?: "Could not end the session",
                        )
                    }
                }
        }
    }

    fun restoreDissolvedSession(session: DissolvedSession) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId }
        if (host == null) {
            _state.update { it.copy(sessionActionError = "The session VPS is missing") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sessionActionError = null) }
            runCatching { ssh.resurrectSession(host, session) }
                .onSuccess {
                    dissolvedStore.remove(session)
                    _state.update { it.copy(dissolvedSessions = it.dissolvedSessions.filterNot { item -> item.key == session.key }) }
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(sessionActionError = throwable.message ?: "Could not restore the archived session")
                    }
                }
        }
    }

    fun forgetDissolvedSession(session: DissolvedSession) {
        dissolvedStore.remove(session)
        _state.update { it.copy(dissolvedSessions = it.dissolvedSessions.filterNot { item -> item.key == session.key }) }
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
        archiveReaderUntilAndOpenNext(null)
    }

    /** Snoozes the current thread and immediately advances through the remaining inbox. */
    fun archiveReaderUntilAndOpenNext(resumeAtEpochSeconds: Long?) {
        val current = _state.value.readerSession ?: return
        val visible = visibleInboxSessions(_state.value)
        val currentIndex = visible.indexOfFirst {
            it.hostId == current.hostId && it.name == current.name
        }
        archiveSessionUntil(current, resumeAtEpochSeconds)
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
        noteSessionInteraction(session)
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
        .let { sessions -> SessionSearch.newestFirst(sessions, state.sessionInteractionEpochSeconds) }

    private fun latestWorkspaceActivity(
        sessions: List<TmuxSession>,
        assignments: Map<String, String>,
        interactions: Map<String, Long> = emptyMap(),
    ): Map<String, Long> = sessions
        .mapNotNull { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            assignments[key]?.let { workspaceId ->
                workspaceId to maxOf(
                    session.lastActivityEpochSeconds,
                    interactions[key] ?: 0L,
                )
            }
        }
        .groupingBy { it.first }
        .fold(0L) { latest, entry -> maxOf(latest, entry.second) }

    private companion object {
        const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        const val MAX_DISPLAY_NAME_LENGTH = 63
        const val SESSION_REFRESH_INTERVAL_MS = 10_000L
    }
}
