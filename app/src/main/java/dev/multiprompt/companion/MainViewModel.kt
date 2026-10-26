package dev.multiprompt.companion

import android.app.Application
import android.os.SystemClock
import android.util.Log
import dev.multiprompt.companion.account.DWELL
import dev.multiprompt.companion.account.VisitDwell
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.AgentHarness
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.DissolvedSession
import dev.multiprompt.companion.model.RemoteDirectory
import dev.multiprompt.companion.model.RefreshMerge
import dev.multiprompt.companion.data.UnreadCount
import dev.multiprompt.companion.notify.ReplyNotificationPolicy
import dev.multiprompt.companion.notify.ReplyNotifier
import dev.multiprompt.companion.ui.SessionPreview
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.RetiredHosts
import dev.multiprompt.companion.data.SessionSearch
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.data.CrashReport
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.skills.SkillStore
import dev.multiprompt.companion.auth.SyncAuthManager
import android.content.Intent
import dev.multiprompt.companion.account.AccountHosts
import dev.multiprompt.companion.account.AccountHostMap
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import dev.multiprompt.companion.account.frequentSessions
import dev.multiprompt.companion.account.UsedStampThrottle
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.inboxSplitIds
import dev.multiprompt.companion.account.isFavoriteSession
import dev.multiprompt.companion.account.isHiddenAsMuted
import dev.multiprompt.companion.account.isMutedSession
import dev.multiprompt.companion.account.AccountLayoutStore
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.account.PendingLayout
import dev.multiprompt.companion.account.DeviceKeyEntry
import dev.multiprompt.companion.account.DeviceKeyStatus
import dev.multiprompt.companion.ui.PendingDevice
import dev.multiprompt.companion.ui.sshKeyFingerprint
import org.json.JSONObject
import dev.multiprompt.companion.ssh.PresentedHostKey
import dev.multiprompt.companion.ssh.SshProblem
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.update.UpdateManager
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.upload.ScreencastUploader
import dev.multiprompt.companion.ui.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val updatingHostIds: Set<String> = emptySet(),
    val pendingHostKeys: Map<String, PresentedHostKey> = emptyMap(),
    val refreshing: Boolean = false,
    val editorHost: HostProfile? = null,
    val editorVisible: Boolean = false,
    val editorError: String? = null,
    val terminal: TerminalConnection? = null,
    val terminalSession: TmuxSession? = null,
    val userSelectedSessionKey: String? = null,
    val reader: SessionReaderConnection? = null,
    val readerSession: TmuxSession? = null,
    val readerUnreadMessageKey: String? = null,
    val unreadSessionKeys: Set<String> = emptySet(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val repliedSessionKeys: Set<String> = emptySet(),
    val archivedSessionKeys: Set<String> = emptySet(),
    val sessionBucket: SessionBucket = SessionBucket.OPEN,
    val dissolvedSessions: List<DissolvedSession> = emptyList(),
    val crashReport: CrashReport? = null,
    val signedIn: Boolean = false,
    val accountLayout: AccountLayout? = null,
    val accountSyncing: Boolean = false,
    val accountPending: Boolean = false,
    val accountError: String? = null,
    val accountHosts: Map<Int, HostProfile> = emptyMap(),
    val pendingDevice: PendingDevice? = null,
    val deviceApprovalBusy: Boolean = false,
    val deviceApprovalError: String? = null,
    val workspaces: List<Workspace> = emptyList(),
    val workspaceSplitIds: List<String?> = listOf(null),
    val selectedWorkspaceId: String? = null,
    val workspaceSelectionInitialized: Boolean = false,
    val sessionWorkspaceIds: Map<String, String> = emptyMap(),
    val sessionInteractionEpochSeconds: Map<String, Long> = emptyMap(),
    val creatingSession: Boolean = false,
    val sessionActionError: String? = null,
    val sessionEtas: Map<String, TmuxText.SessionEta> = emptyMap(),
    val etaAccuracy: List<TmuxText.AgentAccuracy>? = null,
    val etaAccuracyLoading: Boolean = false,
    val etaAccuracyError: String? = null,
    val newestSessionsAtBottom: Boolean = true,
    val allSplitOnRight: Boolean = true,
    val readerDefaultFontScale: Float = 1f,
    val readerTechnicalMode: Boolean = false,
    val shellFontSize: Float? = null,
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val waitingIdleObservations: Map<String, Int> = emptyMap(),
    val readerSwipeOrder: List<String> = emptyList(),
    val terminalFromReader: Boolean = false,
    val newSession: NewSessionPicker? = null,
) {
    val showFrequentSplit: Boolean get() = accountLayout?.settings?.get("frequent") ?: true
    val showFavoritesSplit: Boolean get() = accountLayout?.settings?.get("favorites") ?: true
    val showAllSplit: Boolean get() = accountLayout?.settings?.get("all") ?: true
    val frequentFirst: Boolean get() = accountLayout?.settings?.get("frequent_first") ?: true
    val visibleWorkspaceSplitIds: List<String?> get() = inboxSplitIds(
        workspaceSplitIds, allSplitOnRight, showFrequentSplit, showFavoritesSplit, showAllSplit, frequentFirst,
    )
}

enum class NewSessionStep { HOST, PATH, HARNESS, EXISTING }

enum class NewSessionMode { NEW, EXISTING }

data class NewSessionPicker(
    val hostId: String? = null,
    val accountWorkspace: String? = null,
    val mode: NewSessionMode = NewSessionMode.NEW,
    val path: String = "",
    val directories: List<RemoteDirectory> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val sessions: List<TmuxSession> = emptyList(),
    val openSessionKeys: Set<String> = emptySet(),
    val needsAttentionOnly: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val chosenPath: String? = null,
) {
    val step: NewSessionStep
        get() = when {
            hostId == null -> NewSessionStep.HOST
            mode == NewSessionMode.EXISTING -> NewSessionStep.EXISTING
            chosenPath == null -> NewSessionStep.PATH
            else -> NewSessionStep.HARNESS
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MultipromptApplication
    private val hosts = app.hostStore
    private val secrets = app.secretStore
    private val sessionReads = app.sessionReadStore
    private val sessionCache = app.sessionCacheStore
    private val dissolvedStore = app.dissolvedSessionStore
    private val crashReportStore = app.crashReportStore
    private val workspaceStore = app.workspaceStore
    private val ssh = app.sshRepository
    val dictation: DeepgramDictation = app.deepgramDictation
    val updates: UpdateManager = app.updateManager
    val screencast: ScreencastUploader = app.screencastUploader
    val skills: SkillStore = app.skillStore
    val syncAuth: SyncAuthManager = app.syncAuth

    private val accountStore = AccountLayoutStore(application)
    private var pendingLayout = accountStore.load(syncAuth.accountId())
    private var usedStampThrottle = UsedStampThrottle(pendingLayout?.base)
    private val foreground = MutableStateFlow(false)
    private var replyNotificationsBaselined = false
    private var pendingNotificationKey: String? = null
    private var trustRefreshQueued = false
    private var accountRequestRunning = false
    private var accountGeneration = 0
    private val deviceKeyMutex = Mutex()
    private var deviceKeyRefreshRunning = false
    private var pendingDeviceEntry: DeviceKeyEntry? = null
    private val initialWorkspaces = pendingLayout?.edited?.workspaces.orEmpty().map { Workspace(id = it, name = it, hostId = "", remotePath = "") }
    private val _state = MutableStateFlow(
        AppUiState(
            hosts = hosts.load(),
            workspaces = initialWorkspaces,
            workspaceSplitIds = listOf(null) + initialWorkspaces.map { it.id },
            signedIn = syncAuth.hasSession(),
            accountLayout = pendingLayout?.edited,
            accountPending = pendingLayout?.dirty == true,
            accountHosts = AccountHostMap.resolve(hosts.load(), accountStore.hostOverrides()),
            newestSessionsAtBottom = sessionReads.newestSessionsAtBottom(),
            allSplitOnRight = sessionReads.allSplitOnRight(),
            readerDefaultFontScale = sessionReads.readerDefaultFontScale(),
            readerTechnicalMode = sessionReads.readerTechnicalMode(),
            shellFontSize = sessionReads.shellFontSize(),
            appTheme = sessionReads.appTheme(),
            dissolvedSessions = dissolvedStore.load(),
            crashReport = crashReportStore.loadForDisplay(),
        ).let { initial ->
            initial.copy(selectedWorkspaceId = if (null in initial.visibleWorkspaceSplitIds) null else initial.visibleWorkspaceSplitIds.first())
        },
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val warmReaders = LinkedHashMap<String, SessionReaderConnection>()
    private val warmReaderTouchedAt = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            _state.map { state ->
                state.accountLayout?.tabs.orEmpty().filter { it.favorite == true }.map { it.identity }.toSet() to state.accountHosts
            }.distinctUntilChanged().collectLatest {
                val active = ReplyNotifier.activeKeys(app)
                ReplyNotifier.cancel(app, active - favoriteReplyKeys(_state.value, active))
            }
        }
        viewModelScope.launch {
            combine(_state, foreground) { state, active ->
                (state.terminalSession ?: state.readerSession)?.takeIf {
                    active && SessionReadStore.key(it.hostId, it.name) == state.userSelectedSessionKey
                }?.let {
                    it.hostId to it.name.lowercase(java.util.Locale.ROOT)
                }
            }.distinctUntilChanged().collectLatest { front ->
                if (front == null) return@collectLatest
                val dwell = VisitDwell(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                delay(DWELL)
                val startedAt = dwell.visitAt(SystemClock.elapsedRealtime()) ?: return@collectLatest
                val session = _state.value.terminalSession ?: _state.value.readerSession ?: return@collectLatest
                if (!foreground.value || front != (session.hostId to session.name.lowercase(java.util.Locale.ROOT))) return@collectLatest
                recordSessionVisit(session, startedAt)
            }
        }
        RetiredHosts.hostsToRemove(_state.value.hosts).forEach(::deleteHost)
        uploadCrashReport()
        updates.check()
        if (_state.value.hosts.isNotEmpty()) refresh()
        viewModelScope.launch {
            var backgroundTicks = 0
            while (true) {
                delay(SESSION_REFRESH_INTERVAL_MS)
                sweepWarmReaders()
                backgroundTicks = if (foreground.value) 0 else backgroundTicks + 1
                val due = foreground.value || backgroundTicks % BACKGROUND_REFRESH_EVERY == 0
                if (due && _state.value.hosts.isNotEmpty()) refresh()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(20_000)
                if (foreground.value) syncAccount()
            }
        }
    }

    fun onResume() {
        foreground.value = true
        refresh()
        syncAccount()
    }

    fun onPause() { foreground.value = false }

    fun onStop() { dictation.onAppBackgrounded() }

    fun select(section: AppSection) {
        _state.update { it.copy(section = section) }
    }

    fun openReleaseNotes() {
        _state.value.terminal?.close()
        _state.update {
            it.copy(
                terminal = null,
                terminalSession = null,
                terminalFromReader = false,
                editorVisible = false,
                section = AppSection.UPDATE,
            )
        }
        closeReader()
    }

    fun showEtaAccuracy(session: TmuxSession) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        _state.update {
            it.copy(etaAccuracy = emptyList(), etaAccuracyLoading = true, etaAccuracyError = null)
        }
        viewModelScope.launch {
            runCatching { ssh.etaAccuracy(host) }
                .onSuccess { rows ->
                    _state.update { it.copy(etaAccuracy = rows, etaAccuracyLoading = false) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            etaAccuracyLoading = false,
                            etaAccuracyError = throwable.message ?: "Could not read the estimate log",
                        )
                    }
                }
        }
    }

    fun dismissEtaAccuracy() {
        _state.update { it.copy(etaAccuracy = null, etaAccuracyLoading = false, etaAccuracyError = null) }
    }

    fun clearSessionActionError() {
        _state.update { it.copy(sessionActionError = null) }
    }

    private fun uploadCrashReport() {
        viewModelScope.launch(Dispatchers.IO) {
            crashReportStore.loadPending().forEach { report ->
                syncAuth.uploadCrashReport(report, crashReportStore::markUploaded)
            }
        }
    }

    fun clearCrashReport() {
        crashReportStore.dismiss()
        uploadCrashReport()
        _state.update { it.copy(crashReport = null) }
    }

    fun setNewestSessionsAtBottom(enabled: Boolean) {
        sessionReads.setNewestSessionsAtBottom(enabled)
        _state.update { it.copy(newestSessionsAtBottom = enabled) }
    }

    fun setAllSplitOnRight(enabled: Boolean) {
        sessionReads.setAllSplitOnRight(enabled)
        _state.update { it.copy(allSplitOnRight = enabled) }
    }

    fun setSessionListSetting(key: String, enabled: Boolean) {
        editAccountLayout { it.setSetting(key, enabled) }?.let { error -> _state.update { it.copy(accountError = error) } }
    }

    fun setReaderDefaultFontScale(scale: Float) {
        sessionReads.setReaderDefaultFontScale(scale)
        _state.update { it.copy(readerDefaultFontScale = sessionReads.readerDefaultFontScale()) }
    }

    fun setReaderTechnicalMode(enabled: Boolean) {
        sessionReads.setReaderTechnicalMode(enabled)
        _state.update { it.copy(readerTechnicalMode = enabled) }
    }

    fun setShellFontSize(size: Float) {
        sessionReads.setShellFontSize(size)
        _state.update { it.copy(shellFontSize = sessionReads.shellFontSize()) }
    }

    fun setAppTheme(theme: AppTheme) {
        sessionReads.setAppTheme(theme)
        _state.update { it.copy(appTheme = theme) }
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
            hostKeys = if (connectionIdentityChanged) emptyList() else existing?.hostKeys.orEmpty(),
            dismissedHostKeyFingerprint = if (connectionIdentityChanged) null else existing?.dismissedHostKeyFingerprint,
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

    fun addUserOnHost(source: HostProfile, username: String) {
        val user = username.trim()
        val key = secrets.get(source.keySecretId)
        if (user.isEmpty() || key == null) {
            _state.update { it.copy(hostErrors = it.hostErrors + (source.id to "Could not copy this host's key")) }
            return
        }
        val id = UUID.randomUUID().toString()
        val keySecretId = "host_key_$id"
        secrets.put(keySecretId, key)
        val passphraseSecretId = source.passphraseSecretId?.let { sourceId ->
            secrets.get(sourceId)?.let { passphrase ->
                "host_passphrase_$id".also { secrets.put(it, passphrase) }
            }
        }
        hosts.upsert(
            source.copy(
                id = id,
                label = "${source.label} ($user)",
                username = user,
                dismissedHostKeyFingerprint = null,
                keySecretId = keySecretId,
                passphraseSecretId = passphraseSecretId,
            ),
        )
        _state.update { it.copy(hosts = hosts.load(), section = AppSection.SESSIONS) }
        refresh()
    }

    fun deleteHost(host: HostProfile) {
        hosts.delete(host.id)
        val remainingHosts = hosts.load()
        listOfNotNull(host.keySecretId, host.passphraseSecretId).distinct().forEach { secretId ->
            if (remainingHosts.none { it.keySecretId == secretId || it.passphraseSecretId == secretId }) {
                secrets.remove(secretId)
            }
        }
        accountStore.hostOverrides().filterValues { it == host.id }.keys.forEach { slot ->
            accountStore.setHost(slot, null)
        }
        sessionReads.removeHost(host.id)
        dissolvedStore.removeHost(host.id)
        workspaceStore.removeHost(host.id)
        sessionCache.removeHost(host.id)
        val sessionPrefix = SessionReadStore.key(host.id, "")
        warmReaders.keys.filter { it.startsWith(sessionPrefix) }.forEach { key ->
            warmReaders.remove(key)?.close()
            warmReaderTouchedAt.remove(key)
        }
        val current = _state.value
        if (current.readerSession?.hostId == host.id) current.reader?.close()
        if (current.terminalSession?.hostId == host.id) current.terminal?.close()
        _state.update {
            val workspaces = it.workspaces.filterNot { workspace -> workspace.hostId == host.id }
            val workspaceIds = workspaces.mapTo(mutableSetOf()) { workspace -> workspace.id }
            it.copy(
                hosts = remainingHosts,
                accountHosts = AccountHostMap.resolve(remainingHosts, accountStore.hostOverrides()),
                editorHost = it.editorHost?.takeUnless { profile -> profile.id == host.id },
                editorVisible = it.editorVisible && it.editorHost?.id != host.id,
                editorError = it.editorError.takeUnless { _ -> it.editorHost?.id == host.id },
                newSession = it.newSession?.takeUnless { picker -> picker.hostId == host.id },
                reader = it.reader.takeUnless { _ -> it.readerSession?.hostId == host.id },
                readerSession = it.readerSession?.takeUnless { session -> session.hostId == host.id },
                terminal = it.terminal.takeUnless { _ -> it.terminalSession?.hostId == host.id },
                terminalSession = it.terminalSession?.takeUnless { session -> session.hostId == host.id },
                terminalFromReader = it.terminalFromReader && it.terminalSession?.hostId != host.id,
                readerSwipeOrder = it.readerSwipeOrder.filterNot { key -> key.startsWith(sessionPrefix) },
                unreadSessionKeys = it.unreadSessionKeys.filterNot { key -> key.startsWith(sessionPrefix) }.toSet(),
                unreadCounts = it.unreadCounts.filterKeys { key -> !key.startsWith(sessionPrefix) },
                archivedSessionKeys = it.archivedSessionKeys.filterNot { key -> key.startsWith(sessionPrefix) }.toSet(),
                sessionWorkspaceIds = it.sessionWorkspaceIds.filterKeys { key -> !key.startsWith(sessionPrefix) },
                sessionInteractionEpochSeconds = it.sessionInteractionEpochSeconds.filterKeys { key -> !key.startsWith(sessionPrefix) },
                waitingIdleObservations = it.waitingIdleObservations.filterKeys { key -> !key.startsWith(sessionPrefix) },
                sessionEtas = it.sessionEtas.filterKeys { key -> !key.startsWith(sessionPrefix) },
                workspaces = workspaces,
                workspaceSplitIds = it.workspaceSplitIds.filter { id -> id == null || id in workspaceIds },
                sessions = it.sessions.filterNot { session -> session.hostId == host.id },
                hostErrors = it.hostErrors - host.id,
                cachedHostIds = it.cachedHostIds - host.id,
                updatingHostIds = it.updatingHostIds - host.id,
                dissolvedSessions = it.dissolvedSessions.filterNot { session -> session.hostId == host.id },
                pendingHostKeys = it.pendingHostKeys - host.id,
            ).let { next ->
                next.copy(selectedWorkspaceId = if (it.selectedWorkspaceId in next.visibleWorkspaceSplitIds) it.selectedWorkspaceId else next.visibleWorkspaceSplitIds.first())
            }
        }
    }

    fun trustHostKey(hostId: String) {
        val presented = _state.value.pendingHostKeys[hostId] ?: return
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        hosts.upsert(
            host.copy(
                hostKeyType = presented.type,
                hostKeyFingerprint = presented.fingerprint,
                hostKeys = listOfNotNull(presented.publicKey),
                dismissedHostKeyFingerprint = null,
            ),
        )
        _state.update {
            it.copy(
                hosts = hosts.load(),
                pendingHostKeys = it.pendingHostKeys - hostId,
                hostErrors = it.hostErrors - hostId,
            )
        }
        if (_state.value.refreshing) trustRefreshQueued = true else refresh()
    }

    fun dismissHostKey(hostId: String) {
        val presented = _state.value.pendingHostKeys[hostId] ?: return
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        hosts.upsert(host.copy(dismissedHostKeyFingerprint = "${presented.type} ${presented.fingerprint}"))
        _state.update {
            it.copy(
                hosts = hosts.load(),
                pendingHostKeys = it.pendingHostKeys - hostId,
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
            val results = mutableMapOf<String, HostRefresh>()
            _state.update { applyRefresh(it, snapshot, cachedByHost, results, final = false) }
            openPendingNotificationSession()
            snapshot.map { host ->
                async {
                    val result = runCatching { ssh.listSessions(host) }
                        .fold(
                            onSuccess = { HostRefresh(host.id, it.sessions, it.etaStates) },
                            onFailure = { throwable ->
                                HostRefresh(
                                    hostId = host.id,
                                    error = throwable.message ?: "SSH failed",
                                    hostKey = (throwable as? SshProblem.HostKeyRequired)?.presented,
                                )
                            },
                        )
                    results[host.id] = result
                    _state.update { applyRefresh(it, snapshot, cachedByHost, results, final = false) }
                }
            }.awaitAll()
            results.values.filter { it.error == null }.forEach { result ->
                sessionCache.save(result.hostId, result.sessions)
            }
            _state.update { applyRefresh(it, snapshot, cachedByHost, results, final = true) }
            openPendingNotificationSession()
            pendingNotificationKey = null
            syncReplyNotifications()
            if (trustRefreshQueued) {
                trustRefreshQueued = false
                refresh()
            }
        }
    }

    private fun favoriteReplyKeys(state: AppUiState, keys: Set<String>): Set<String> = keys.filterTo(linkedSetOf()) { key ->
        val session = TmuxSession(key.substringBefore("::"), key.substringAfter("::"), 1, 0, 0)
        isFavoriteSession(session, state.accountLayout, state.accountHosts)
    }

    private fun syncReplyNotifications() {
        val state = _state.value
        val front = if (foreground.value) {
            (state.terminalSession ?: state.readerSession)?.let { SessionReadStore.key(it.hostId, it.name) }
        } else {
            null
        }
        val active = ReplyNotifier.activeKeys(app)
        val plan = ReplyNotificationPolicy.plan(
            replyNotificationsBaselined,
            state.repliedSessionKeys,
            state.unreadCounts,
            front,
            active,
            favoriteReplyKeys(state, state.unreadCounts.keys + active),
        )
        replyNotificationsBaselined = true
        _state.update { it.copy(repliedSessionKeys = emptySet()) }
        ReplyNotifier.cancel(app, plan.cancel)
        plan.notify.forEach { key ->
            val session = state.sessions.firstOrNull { SessionReadStore.key(it.hostId, it.name) == key } ?: return@forEach
            val preview = SessionPreview.line(session.preview).ifBlank { "New reply" }
            ReplyNotifier.show(app, session.hostId, session.name, session.displayName, preview)
        }
    }

    fun openSessionFromNotification(hostId: String, sessionName: String) {
        pendingNotificationKey = SessionReadStore.key(hostId, sessionName)
        openPendingNotificationSession()
    }

    private fun openPendingNotificationSession() {
        val key = pendingNotificationKey ?: return
        val session = _state.value.sessions.firstOrNull { SessionReadStore.key(it.hostId, it.name) == key } ?: return
        pendingNotificationKey = null
        openReader(session, userInitiated = true)
    }

    private fun applyRefresh(
        current: AppUiState,
        snapshot: List<HostProfile>,
        cachedByHost: Map<String, List<TmuxSession>>,
        answered: Map<String, HostRefresh>,
        final: Boolean,
    ): AppUiState {
        val results = snapshot.mapNotNull { answered[it.id] }
        val merged = RefreshMerge.merge(
            hostIds = snapshot.map { it.id },
            currentSessions = current.sessions,
            cachedByHost = cachedByHost,
            results = answered.mapValues { (_, result) -> RefreshMerge.HostResult(result.sessions, result.error) },
            final = final,
            currentUpdatingHostIds = current.updatingHostIds,
        )
        val sessions = merged.sessions.map { session ->
            sessionReads.displayName(session)?.let { session.copy(title = it) } ?: session
        }
        val mapping = AccountHostMap.resolve(current.hosts, accountStore.hostOverrides())
        val sessionWorkspaceIds = sessions.mapNotNull { session ->
            current.accountLayout?.let { AccountHostMap.tabFor(session, it, mapping) }?.let { tab ->
                SessionReadStore.key(session.hostId, session.name) to tab.workspace
            }
        }.toMap()
        val interactionEpochSeconds = current.sessionInteractionEpochSeconds + sessions
            .mapNotNull { session ->
                sessionReads.lastInteractedAt(session).takeIf { it > 0 }?.let {
                    SessionReadStore.key(session.hostId, session.name) to it
                }
            }
            .toMap()
        val idleObservations = if (!final) current.waitingIdleObservations else sessions.mapNotNull { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            val observations = if (TmuxText.isWaitingForInput(session.preview, session.agent)) {
                (current.waitingIdleObservations[key] ?: 0) + 1
            } else {
                0
            }
            observations.takeIf { it > 0 }?.let { key to it }
        }.toMap()
        val archivedKeys = sessions
            .filter { session ->
                val key = SessionReadStore.key(session.hostId, session.name)
                sessionReads.isArchived(
                    session,
                    needsAttention = (idleObservations[key] ?: 0) >= IDLE_OBSERVATIONS_TO_RELEASE,
                    restoreReleased = final,
                )
            }
            .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) }
        val orderedWorkspaces = current.workspaces
        val workspaceSplitIds = listOf(null) + orderedWorkspaces.map { it.id }
        val visibleSplitIds = current.copy(workspaceSplitIds = workspaceSplitIds).visibleWorkspaceSplitIds
        val selectedWorkspaceId = if (current.selectedWorkspaceId in visibleSplitIds) current.selectedWorkspaceId else visibleSplitIds.first()
        val etaNow = System.currentTimeMillis()
        val sessionEtas = results
            .filter { it.error == null }
            .flatMap { result ->
                result.etaStates.mapNotNull { (name, state) ->
                    SessionReadStore.key(result.hostId, name) to state.toEta(etaNow)
                }
            }
            .toMap()
        val unreadKeys = sessions
            .filterNot { isMutedSession(it, current.accountLayout, mapping) }
            .filter { SessionReadStore.key(it.hostId, it.name) !in archivedKeys }
            .filter { TmuxText.isWaitingForInput(it.preview, it.agent) }
            .filter(sessionReads::isUnread)
            .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) }
        val replied = mutableSetOf<String>()
        val unreadCounts = sessions.associate { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            val stored = sessionReads.unreadCountState(session)
            val counted = if (answered[session.hostId]?.error == null && session.hostId in answered) UnreadCount.step(
                stored,
                activity = session.lastActivityEpochSeconds,
                waiting = TmuxText.isWaitingForInput(session.preview, session.agent),
                unread = sessionReads.isUnread(session),
            ) else stored
            if (counted != stored) sessionReads.saveUnreadCountState(session, counted)
            if (counted.count > stored.count) replied += key
            key to UnreadCount.displayed(counted.count, key in unreadKeys)
        }
        return current.copy(
            sessions = sessions,
            sessionEtas = if (final) sessionEtas else current.sessionEtas + sessionEtas,
            accountHosts = mapping,
            readerSession = current.readerSession?.let { open ->
                val tab = current.accountLayout?.let { AccountHostMap.tabFor(open, it, mapping) }
                tab?.let { AccountHostMap.sessionFor(it, sessions, mapping) }
                    ?: sessions.firstOrNull { it.hostId == open.hostId && it.name == open.name } ?: open
            },
            terminalSession = current.terminalSession?.let { open ->
                val tab = current.accountLayout?.let { AccountHostMap.tabFor(open, it, mapping) }
                tab?.let { AccountHostMap.sessionFor(it, sessions, mapping) }
                    ?: sessions.firstOrNull { it.hostId == open.hostId && it.name == open.name } ?: open
            },
            hostErrors = results.mapNotNull { result ->
                result.error?.let { result.hostId to it }
            }.toMap(),
            cachedHostIds = merged.cachedHostIds,
            updatingHostIds = merged.updatingHostIds,
            pendingHostKeys = current.pendingHostKeys.filterKeys { hostId ->
                hostId !in answered && current.hosts.any { it.id == hostId }
            } + results.mapNotNull { result ->
                val host = current.hosts.firstOrNull { it.id == result.hostId } ?: return@mapNotNull null
                result.hostKey?.takeUnless { key ->
                    (host.hostKeyType == key.type && host.hostKeyFingerprint == key.fingerprint) ||
                        host.dismissedHostKeyFingerprint == "${key.type} ${key.fingerprint}"
                }?.let { result.hostId to it }
            }.toMap(),
            unreadSessionKeys = unreadKeys,
            unreadCounts = unreadCounts,
            repliedSessionKeys = current.repliedSessionKeys + replied,
            archivedSessionKeys = archivedKeys,
            workspaces = orderedWorkspaces,
            workspaceSplitIds = workspaceSplitIds,
            selectedWorkspaceId = selectedWorkspaceId,
            workspaceSelectionInitialized = orderedWorkspaces.isNotEmpty() ||
                current.workspaceSelectionInitialized,
            sessionWorkspaceIds = sessionWorkspaceIds,
            sessionInteractionEpochSeconds = interactionEpochSeconds,
            waitingIdleObservations = if (final) idleObservations.filterKeys { it in archivedKeys }
                else current.waitingIdleObservations,
            refreshing = !final,
        )
    }

    private fun warmReader(key: String, create: () -> SessionReaderConnection): SessionReaderConnection {
        sweepWarmReaders(except = key)
        val existing = warmReaders[key]
        val reader = if (existing != null && existing.state.value.status != ReaderStatus.Closed) {
            existing
        } else {
            existing?.close()
            create().also { it.start() }
        }
        warmReaders.remove(key)
        warmReaders[key] = reader
        warmReaderTouchedAt[key] = System.currentTimeMillis()
        while (warmReaders.size > MAX_WARM_READERS) {
            val oldest = warmReaders.keys.first()
            if (oldest == key) break
            warmReaders.remove(oldest)?.close()
            warmReaderTouchedAt.remove(oldest)
        }
        return reader
    }

    private fun sweepWarmReaders(except: String? = null) {
        val now = System.currentTimeMillis()
        warmReaders.keys.toList().forEach { key ->
            if (key == except || key == _state.value.readerSession?.let {
                    SessionReadStore.key(it.hostId, it.name)
                }
            ) {
                return@forEach
            }
            if (now - (warmReaderTouchedAt[key] ?: 0) >= READER_CACHE_TTL_MS) {
                warmReaders.remove(key)?.close()
                warmReaderTouchedAt.remove(key)
            }
        }
    }

    private fun closeWarmReaders() {
        warmReaders.values.forEach { it.close() }
        warmReaders.clear()
        warmReaderTouchedAt.clear()
    }

    fun openReader(session: TmuxSession, userInitiated: Boolean = false) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        val swipeOrder = _state.value.readerSwipeOrder.ifEmpty {
            visibleInboxSessions(_state.value)
                .let { if (_state.value.newestSessionsAtBottom) it.asReversed() else it }
                .map { SessionReadStore.key(it.hostId, it.name) }
        }
        noteSessionInteraction(session)
        val unreadMessageKey = sessionReads.unreadMessageKey(session)
        sessionReads.markRead(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        ReplyNotifier.cancel(app, key)
        _state.value.terminal?.close()
        val reader = warmReader(key) {
            SessionReaderConnection(
                repository = ssh,
                host = host,
                tmuxSessionName = session.name,
                agent = session.agent,
                historyScript = app.chatHistoryScript,
                historyCache = app.chatHistoryCache,
            )
        }
        _state.update {
            it.copy(
                terminal = null,
                terminalSession = null,
                reader = reader,
                readerSession = session,
                readerUnreadMessageKey = unreadMessageKey,
                userSelectedSessionKey = key.takeIf { userInitiated },
                readerSwipeOrder = swipeOrder,
                unreadSessionKeys = it.unreadSessionKeys - key,
                unreadCounts = it.unreadCounts - key,
            )
        }
    }

    fun markReaderRead() {
        val session = _state.value.readerSession ?: return
        sessionReads.markRead(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        ReplyNotifier.cancel(app, key)
        _state.update { it.copy(readerUnreadMessageKey = null, unreadSessionKeys = it.unreadSessionKeys - key, unreadCounts = it.unreadCounts - key) }
    }

    fun markReaderUnreadFrom(messageKey: String) {
        val session = _state.value.readerSession ?: return
        sessionReads.markUnreadFrom(session, messageKey)
        markSessionUnread(session)
        _state.update { it.copy(readerUnreadMessageKey = messageKey) }
    }

    fun markSessionUnread(session: TmuxSession) {
        sessionReads.markUnread(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update {
            it.copy(
                unreadSessionKeys = it.unreadSessionKeys + key,
                unreadCounts = it.unreadCounts + (key to sessionReads.unreadCountState(session).count),
            )
        }
    }

    fun archiveSession(session: TmuxSession) {
        sessionReads.archive(session)
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update {
            it.copy(
                archivedSessionKeys = it.archivedSessionKeys + key,
                unreadSessionKeys = it.unreadSessionKeys - key,
                unreadCounts = it.unreadCounts - key,
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
                unreadCounts = it.unreadCounts - key,
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
            it.copy(selectedWorkspaceId = if (workspaceId in it.visibleWorkspaceSplitIds) workspaceId else it.visibleWorkspaceSplitIds.first(), workspaceSelectionInitialized = true)
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
                workspaceSplitIds = listOf(null) + current.workspaces.map { it.id },
            )
        }
    }

    fun stampSessionInput(session: TmuxSession) {
        if (!_state.value.signedIn) return
        val pending = pendingLayout ?: return
        val tab = AccountHostMap.tabFor(session, pending.edited, _state.value.accountHosts) ?: return
        try {
            saveAccountLayout(pending.copy(edited = pending.edited.stampInput(tab.identity, System.currentTimeMillis())))
            if (usedStampThrottle.shouldPush(pendingLayout!!)) syncAccount()
        } catch (e: Exception) {
            _state.update { it.copy(accountError = e.message ?: "Could not save session use") }
        }
    }

    private fun recordSessionVisit(session: TmuxSession, startedAt: Long) {
        if (!_state.value.signedIn) return
        val pending = pendingLayout ?: return
        val tab = AccountHostMap.tabFor(session, pending.edited, _state.value.accountHosts) ?: return
        try {
            saveAccountLayout(pending.copy(edited = pending.edited.recordVisit(tab.identity, startedAt)))
            syncAccount()
        } catch (e: Exception) {
            _state.update { it.copy(accountError = e.message ?: "Could not save session visit") }
        }
    }

    fun moveWorkspaceSplit(workspaceId: String?, delta: Int) {
        if (workspaceId == null || workspaceId == FAVORITES_SPLIT_ID || workspaceId == FREQUENT_SPLIT_ID) return
        editAccountLayout { layout ->
            val index = layout.workspaces.indexOf(workspaceId)
            if (index < 0) layout else layout.copy(workspaces = layout.workspaces.toMutableList().apply {
                add((index + delta).coerceIn(indices), removeAt(index))
            })
        }?.let { error -> _state.update { it.copy(accountError = error) } }
    }

    fun resetWorkspaceSplitOrder() { publishAccountLayout() }

    fun openAdjacentWorkspace(delta: Int) {
        val splits = _state.value.visibleWorkspaceSplitIds
        val index = splits.indexOf(_state.value.selectedWorkspaceId).coerceAtLeast(0)
        selectWorkspace(splits[Math.floorMod(index + delta, splits.size)])
    }

    fun createWorkspace(name: String): String? =
        editAccountLayout { it.copy(workspaces = it.workspaces + name.trim()) }

    fun moveSession(session: TmuxSession, workspace: Workspace) {
        addAccountSession(session, workspace.name)?.let { error -> _state.update { it.copy(accountError = error) } }
    }

    fun renameSession(session: TmuxSession, requestedName: String): String? {
        val displayName = requestedName.trim()
        val tab = _state.value.accountLayout?.let { AccountHostMap.tabFor(session, it, _state.value.accountHosts) }
        if (tab != null) return editAccountLayout { it.renameTab(tab.identity, displayName) }
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
        viewModelScope.launch {
            val claudeSessionId = if (session.agent == AgentKind.CLAUDE &&
                session.workingDirectory.isNotBlank()
            ) {
                current.hosts.firstOrNull { it.id == session.hostId }
                    ?.let { host -> ssh.latestClaudeSessionId(host, session.workingDirectory) }
            } else {
                null
            }
            destroySession(
                session,
                DissolvedSession.from(
                    session = session,
                    workspaceId = current.sessionWorkspaceIds[key],
                    workspaceName = current.sessionWorkspaceIds[key]
                        ?.let { id -> current.workspaces.firstOrNull { it.id == id }?.name }
                        .orEmpty(),
                    claudeSessionId = claudeSessionId,
                ),
            )
        }
    }

    fun endSession(session: TmuxSession) {
        destroySession(session, dissolved = null)
    }

    fun forgetSession(session: TmuxSession) {
        _state.value.accountLayout?.let { AccountHostMap.tabFor(session, it, _state.value.accountHosts) }?.let(::closeAccountTab)
        val key = SessionReadStore.key(session.hostId, session.name)
        sessionCache.save(
            session.hostId,
            sessionCache.load(session.hostId).filterNot { it.name == session.name },
        )
        warmReaders.remove(key)?.close()
        warmReaderTouchedAt.remove(key)
        val readerWasOpen = _state.value.readerSession?.let {
            it.hostId == session.hostId && it.name == session.name
        } == true
        val terminalWasOpen = _state.value.terminalSession?.let {
            it.hostId == session.hostId && it.name == session.name
        } == true
        if (terminalWasOpen) _state.value.terminal?.close()
        _state.update {
            it.copy(
                sessions = it.sessions.filterNot { item ->
                    item.hostId == session.hostId && item.name == session.name
                },
                sessionWorkspaceIds = it.sessionWorkspaceIds - key,
                unreadSessionKeys = it.unreadSessionKeys - key,
                unreadCounts = it.unreadCounts - key,
                archivedSessionKeys = it.archivedSessionKeys - key,
                sessionInteractionEpochSeconds = it.sessionInteractionEpochSeconds - key,
                reader = if (readerWasOpen) null else it.reader,
                readerSession = if (readerWasOpen) null else it.readerSession,
                terminal = if (terminalWasOpen) null else it.terminal,
                terminalSession = if (terminalWasOpen) null else it.terminalSession,
                sessionActionError = null,
            )
        }
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
                    warmReaders.remove(key)?.close()
                    warmReaderTouchedAt.remove(key)
                    if (terminalWasOpen) _state.value.terminal?.close()
                    dissolved?.let(dissolvedStore::upsert)
                    sessionReads.restore(session)
                    sessionCache.save(
                        session.hostId,
                        sessionCache.load(session.hostId).filterNot { it.name == session.name },
                    )
                    val successor = if (readerWasOpen) neighbourAfterRemoving(key) else null
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
                            unreadCounts = it.unreadCounts - key,
                            archivedSessionKeys = it.archivedSessionKeys - key,
                            sessionWorkspaceIds = it.sessionWorkspaceIds - key,
                            dissolvedSessions = dissolved?.let { record ->
                                it.dissolvedSessions.filterNot { old -> old.key == record.key } + record
                            } ?: it.dissolvedSessions,
                        )
                    }
                    _state.value.accountLayout?.let { AccountHostMap.tabFor(session, it, _state.value.accountHosts) }?.let(::closeAccountTab)
                    successor?.let { openReader(it) }
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

    fun closeReader() {
        _state.update { it.copy(reader = null, readerSession = null, readerSwipeOrder = emptyList()) }
        sweepWarmReaders()
    }

    fun startNewSession() {
        _state.update { it.copy(newSession = NewSessionPicker(accountWorkspace = it.selectedWorkspaceId?.takeUnless { id -> id == FAVORITES_SPLIT_ID || id == FREQUENT_SPLIT_ID }), sessionActionError = null) }
        _state.value.hosts.singleOrNull()?.let { host -> chooseNewSessionHost(host.id) }
    }

    fun closeNewSession() {
        _state.update { it.copy(newSession = null) }
    }

    fun chooseNewSessionHost(hostId: String) {
        if (_state.value.hosts.none { it.id == hostId }) return
        _state.update { it.copy(newSession = NewSessionPicker(hostId = hostId, accountWorkspace = it.newSession?.accountWorkspace, loading = true)) }
        loadNewSessionDirectory(hostId, null)
    }

    fun browseNewSessionDirectory(path: String) {
        val hostId = _state.value.newSession?.hostId ?: return
        loadNewSessionDirectory(hostId, path)
    }

    fun chooseNewSessionFolder(path: String) {
        _state.update { it.copy(newSession = it.newSession?.copy(chosenPath = path, error = null)) }
    }

    fun backToNewSessionFolder() {
        _state.update { it.copy(newSession = it.newSession?.copy(chosenPath = null)) }
    }

    fun setNewSessionMode(mode: NewSessionMode) {
        val picker = _state.value.newSession ?: return
        _state.update { it.copy(newSession = it.newSession?.copy(mode = mode, error = null)) }
        val hostId = picker.hostId ?: return
        when (mode) {
            NewSessionMode.EXISTING -> loadHostSessions(hostId)
            NewSessionMode.NEW -> if (picker.path.isBlank()) loadNewSessionDirectory(hostId, null)
        }
    }

    fun setNewSessionNeedsAttentionOnly(needsAttentionOnly: Boolean) {
        _state.update {
            it.copy(newSession = it.newSession?.copy(needsAttentionOnly = needsAttentionOnly))
        }
    }

    private fun loadHostSessions(hostId: String) {
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(newSession = it.newSession?.copy(loading = true, error = null)) }
            runCatching { ssh.listSessions(host) }
                .onSuccess { hostSessions ->
                    val sessions = hostSessions.sessions
                    val openKeys = _state.value.sessions
                        .filter { it.hostId == hostId }
                        .mapTo(mutableSetOf()) { SessionReadStore.key(it.hostId, it.name) }
                    openKeys += warmReaders.keys
                    _state.update {
                        it.copy(
                            newSession = it.newSession?.copy(
                                sessions = sessions,
                                openSessionKeys = openKeys,
                                loading = false,
                                error = null,
                            ),
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            newSession = it.newSession?.copy(
                                loading = false,
                                error = throwable.message ?: "Could not list the sessions",
                            ),
                        )
                    }
                }
        }
    }

    fun openExistingSession(session: TmuxSession) {
        val key = SessionReadStore.key(session.hostId, session.name)
        _state.update { current ->
            current.copy(
                sessions = (current.sessions.filterNot {
                    SessionReadStore.key(it.hostId, it.name) == key
                } + session),
                archivedSessionKeys = current.archivedSessionKeys - key,
                unreadSessionKeys = current.unreadSessionKeys - key,
                unreadCounts = current.unreadCounts - key,
                newSession = null,
                sessionActionError = null,
            )
        }
        if (session.agent == AgentKind.OTHER) openTerminal(session) else openReader(session)
        refresh()
    }

    fun createNewSession(harness: AgentHarness) {
        val picker = _state.value.newSession ?: return
        val hostId = picker.hostId ?: return
        val path = picker.chosenPath ?: return
        if (_state.value.creatingSession) return
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(creatingSession = true, sessionActionError = null) }
            runCatching { ssh.createAgentSession(host, path, harness) }
                .onSuccess { sessionName ->
                    val session = TmuxSession(
                        hostId = host.id,
                        name = sessionName,
                        windows = 1,
                        attachedClients = 0,
                        lastActivityEpochSeconds = System.currentTimeMillis() / 1000,
                        workingDirectory = path,
                    )
                    workspaceStore.bumpPathUsage(host.id, path)
                    _state.update {
                        it.copy(
                            sessions = (it.sessions + session).distinctBy { item -> SessionReadStore.key(item.hostId, item.name) },
                            creatingSession = false,
                            newSession = null,
                        )
                    }
                    picker.accountWorkspace?.let { workspace ->
                        addAccountSession(session, workspace)?.let { error -> _state.update { it.copy(accountError = error) } }
                    }
                    if (harness == AgentHarness.SHELL) openTerminal(session) else openReader(session)
                    refresh()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            creatingSession = false,
                            sessionActionError = throwable.message ?: "Could not create the session",
                        )
                    }
                }
        }
    }

    private fun loadNewSessionDirectory(hostId: String, path: String?) {
        val host = _state.value.hosts.firstOrNull { it.id == hostId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(newSession = it.newSession?.copy(loading = true, error = null)) }
            runCatching { ssh.listDirectories(host, path) }
                .onSuccess { listing ->
                    _state.update {
                        it.copy(
                            newSession = it.newSession?.copy(
                                path = listing.root,
                                directories = listing.directories,
                                usage = workspaceStore.pathUsage(hostId),
                                loading = false,
                                error = null,
                            ),
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            newSession = it.newSession?.copy(
                                loading = false,
                                error = throwable.message ?: "Could not list the folders",
                            ),
                        )
                    }
                }
        }
    }

    fun archiveReaderAndOpenNext() {
        archiveReaderUntilAndOpenNext(null)
    }

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

    private fun neighbourAfterRemoving(key: String): TmuxSession? {
        val state = _state.value
        val live = visibleInboxSessions(state).associateBy { SessionReadStore.key(it.hostId, it.name) }
        val order = state.readerSwipeOrder.ifEmpty { live.keys.toList() }
        val index = order.indexOf(key)
        if (index < 0) return null
        val remaining = order.filterNot { it == key }
        if (remaining.isEmpty()) return null
        return live[remaining.getOrNull(index - 1) ?: remaining.getOrNull(index) ?: remaining.first()]
    }

    fun openAdjacentReaderSession(delta: Int) {
        val state = _state.value
        val current = state.readerSession ?: return
        val live = visibleInboxSessions(state).associateBy { SessionReadStore.key(it.hostId, it.name) }
        val sessions = state.readerSwipeOrder.mapNotNull { live[it] }
            .ifEmpty { visibleInboxSessions(state) }
        if (sessions.size < 2) return
        val index = sessions.indexOfFirst { it.hostId == current.hostId && it.name == current.name }
        if (index < 0) return
        openReader(sessions[Math.floorMod(index + delta, sessions.size)])
    }

    fun openTerminal(session: TmuxSession, userInitiated: Boolean = false) {
        val host = _state.value.hosts.firstOrNull { it.id == session.hostId } ?: return
        val fromReader = _state.value.readerSession?.let {
            it.hostId == session.hostId && it.name == session.name
        } == true
        noteSessionInteraction(session)
        ReplyNotifier.cancel(app, SessionReadStore.key(session.hostId, session.name))
        _state.value.terminal?.close()
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
                userSelectedSessionKey = SessionReadStore.key(session.hostId, session.name).takeIf { userInitiated },
                terminalFromReader = fromReader,
                reader = null,
                readerSession = null,
            )
        }
    }

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
        val state = _state.value
        val session = state.terminalSession.takeIf { state.terminalFromReader }
        state.terminal?.close()
        _state.update { it.copy(terminal = null, terminalSession = null, terminalFromReader = false) }
        session?.let { openReader(it) }
        refresh()
    }

    fun installUpdate(release: UpdateRelease) = updates.install(release)

    fun signInAccount(launch: (Intent) -> Unit) {
        val generation = accountGeneration
        viewModelScope.launch {
            try {
                val intent = syncAuth.buildAuthorizationIntent()
                if (generation == accountGeneration) launch(intent)
            } catch (e: Exception) {
                _state.update { it.copy(accountError = e.message ?: "Could not open the sign-in browser") }
            }
        }
    }

    fun onAuthRedirect(intent: Intent) {
        val generation = accountGeneration
        viewModelScope.launch {
            val result = syncAuth.handleAuthorizationResponse(intent)
            if (generation != accountGeneration) return@launch
            when (result) {
                SyncAuthManager.TokenResult.Success -> {
                    pendingLayout = accountStore.load(syncAuth.accountId())
                    usedStampThrottle = UsedStampThrottle(pendingLayout?.base)
                    _state.update { it.copy(signedIn = true, accountError = null) }
                    publishAccountLayout()
                    uploadCrashReport()
                    syncAccount()
                }
                is SyncAuthManager.TokenResult.Failure ->
                    _state.update { it.copy(accountError = result.message) }
            }
        }
    }

    fun signOutAccount() {
        accountGeneration++
        pendingLayout = null
        pendingDeviceEntry = null
        usedStampThrottle = UsedStampThrottle()
        _state.update { it.copy(signedIn = false, accountLayout = null, accountPending = false,
            accountError = null, pendingDevice = null, deviceApprovalBusy = false, deviceApprovalError = null,
            workspaces = emptyList(), workspaceSplitIds = listOf(null), sessionWorkspaceIds = emptyMap()) }
        viewModelScope.launch { syncAuth.signOut() }
    }

    fun mapAccountHost(slot: Int, hostId: String?) {
        accountStore.setHost(slot, hostId)
        publishAccountLayout()
    }

    private fun publishAccountLayout() {
        val layout = pendingLayout?.edited
        _state.update { current ->
            val mapping = AccountHostMap.resolve(current.hosts, accountStore.hostOverrides())
            val workspaces = layout?.workspaces.orEmpty().map { Workspace(id = it, name = it, hostId = "", remotePath = "") }
            val assignments = current.sessions.mapNotNull { session ->
                layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.let { SessionReadStore.key(session.hostId, session.name) to it.workspace }
            }.toMap()
            current.copy(accountLayout = layout, accountPending = pendingLayout?.dirty == true, accountHosts = mapping,
                workspaces = workspaces, workspaceSplitIds = listOf(null) + workspaces.map { it.id }, sessionWorkspaceIds = assignments,
                readerSession = current.readerSession?.let { open ->
                    layout?.let { AccountHostMap.tabFor(open, it, mapping) }?.let { AccountHostMap.sessionFor(it, current.sessions, mapping) } ?: open
                },
                terminalSession = current.terminalSession?.let { open ->
                    layout?.let { AccountHostMap.tabFor(open, it, mapping) }?.let { AccountHostMap.sessionFor(it, current.sessions, mapping) } ?: open
                },
            ).let { next ->
                next.copy(selectedWorkspaceId = if (current.selectedWorkspaceId in next.visibleWorkspaceSplitIds) current.selectedWorkspaceId else next.visibleWorkspaceSplitIds.first())
            }
        }
    }

    private fun saveAccountLayout(value: PendingLayout) {
        val account = syncAuth.accountId() ?: throw IllegalStateException("Sign in before editing account workspaces")
        val mapping = AccountHostMap.resolve(_state.value.hosts, accountStore.hostOverrides())
        val next = value.copy(
            base = if (syncAuth.supportsHosts) value.base else value.base.copy(hosts = null),
            edited = value.edited.copy(hosts = if (syncAuth.supportsHosts) AccountHosts.publish(value.edited.hosts, mapping) else null),
        )
        accountStore.save(account, next)
        pendingLayout = next
        publishAccountLayout()
    }

    fun editAccountLayout(edit: (AccountLayout) -> AccountLayout): String? {
        if (!_state.value.signedIn) return "Sign in before editing account workspaces"
        val pending = pendingLayout ?: return "Pull the account layout before editing"
        return try {
            saveAccountLayout(pending.copy(edited = edit(pending.edited).validate()))
            syncAccount()
            null
        } catch (e: Exception) { e.message ?: "Could not save account edit" }
    }

    fun addAccountSession(session: TmuxSession, workspace: String): String? {
        val slot = _state.value.accountHosts.entries.firstOrNull { it.value.id == session.hostId }?.key
            ?: return "Map this SSH profile to an account host first; direct inne/agent tmux is not host 0"
        return editAccountLayout { layout ->
            val existing = AccountHostMap.tabFor(session, layout, _state.value.accountHosts)
            if (existing != null) layout.moveTab(existing.identity, workspace)
            else layout.addTab(AccountTab(session.displayName.take(64), session.name, slot, workspace))
        }
    }

    fun closeAccountTab(tab: AccountTab) {
        editAccountLayout { it.closeTab(tab.identity) }?.let { error -> _state.update { it.copy(accountError = error) } }
    }

    private fun importAccountHosts(layout: AccountLayout) {
        if (!syncAuth.supportsHosts) return
        val local = hosts.load()
        val overrides = accountStore.hostOverrides()
        val merged = AccountHosts.merge(layout.hosts.orEmpty(), local, overrides)
        merged.profiles.filter { profile -> local.none { it.id == profile.id && it.label == profile.label } }.forEach(hosts::upsert)
        merged.overrides.filter { (slot, id) -> overrides[slot] != id }.forEach { (slot, id) -> accountStore.setHost(slot, id) }
        _state.update { it.copy(hosts = hosts.load()) }
    }

    private fun syncDeviceKeys() {
        if (!syncAuth.hasSession() || deviceKeyRefreshRunning || _state.value.deviceApprovalBusy) return
        deviceKeyRefreshRunning = true
        val generation = accountGeneration
        viewModelScope.launch {
            try {
                deviceKeyMutex.withLock {
                    if (generation != accountGeneration || _state.value.deviceApprovalBusy) return@withLock
                    val listing = withContext(Dispatchers.IO) {
                        val publicKey = app.deviceKeyStore.ensure()
                        if (app.deviceKeyClient.publish(publicKey) == null) null else app.deviceKeyClient.list()
                    }
                    if (generation != accountGeneration || _state.value.deviceApprovalBusy) return@withLock
                    val entry = listing?.keys?.firstOrNull {
                        it.status == DeviceKeyStatus.PENDING && it.deviceId != listing.deviceId
                    }
                    pendingDeviceEntry = entry
                    _state.update { current ->
                        current.copy(
                            pendingDevice = entry?.let { PendingDevice(it.deviceId, it.name, sshKeyFingerprint(it.publicKey)) },
                            deviceApprovalError = current.deviceApprovalError.takeIf { current.pendingDevice?.id == entry?.deviceId },
                        )
                    }
                    if (listing != null) withContext(Dispatchers.IO) {
                        app.deviceApproval.cleanupRevoked(listing, hosts.load()).forEach {
                            Log.w("DeviceKeys", "Could not remove a revoked device key from ${it.host.label}")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                deviceKeyRefreshRunning = false
            }
        }
    }

    fun approveDevice() {
        val entry = pendingDeviceEntry ?: return
        if (!_state.value.signedIn || _state.value.deviceApprovalBusy) return
        val generation = accountGeneration
        _state.update { it.copy(deviceApprovalBusy = true, deviceApprovalError = null) }
        viewModelScope.launch {
            var approved = false
            try {
                val result = deviceKeyMutex.withLock {
                    if (generation != accountGeneration) return@launch
                    withContext(Dispatchers.IO) { app.deviceApproval.approve(entry, hosts.load()) }
                }
                if (generation != accountGeneration) return@launch
                approved = result.approved
                if (approved) {
                    pendingDeviceEntry = null
                    _state.update { it.copy(pendingDevice = null) }
                } else {
                    val names = result.failures.map { it.host.label }.distinct().joinToString(", ")
                    val error = if (names.isEmpty()) "Could not approve this device. Try again."
                        else "Could not add it to $names. Try again."
                    _state.update { it.copy(deviceApprovalError = error) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == accountGeneration) _state.update {
                    it.copy(deviceApprovalError = "Could not approve this device. Try again.")
                }
            } finally {
                if (generation == accountGeneration) {
                    _state.update { it.copy(deviceApprovalBusy = false) }
                    if (approved) syncDeviceKeys()
                }
            }
        }
    }

    fun denyDevice() {
        val entry = pendingDeviceEntry ?: return
        if (!_state.value.signedIn || _state.value.deviceApprovalBusy) return
        val generation = accountGeneration
        _state.update { it.copy(deviceApprovalBusy = true, deviceApprovalError = null) }
        viewModelScope.launch {
            try {
                val denied = deviceKeyMutex.withLock {
                    if (generation != accountGeneration) return@launch
                    withContext(Dispatchers.IO) { app.deviceApproval.deny(entry, hosts.load()) }
                }
                if (generation != accountGeneration) return@launch
                if (denied) {
                    pendingDeviceEntry = null
                    _state.update { it.copy(pendingDevice = null) }
                } else _state.update { it.copy(deviceApprovalError = "Could not decline this device. Try again.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == accountGeneration) _state.update {
                    it.copy(deviceApprovalError = "Could not decline this device. Try again.")
                }
            } finally {
                if (generation == accountGeneration) _state.update { it.copy(deviceApprovalBusy = false) }
            }
        }
    }

    fun syncAccount() {
        if (accountRequestRunning) return
        accountRequestRunning = true
        val generation = accountGeneration
        viewModelScope.launch {
            _state.update { it.copy(accountSyncing = true) }
            try {
                val finishedLogin = syncAuth.finishPending()
                if (generation != accountGeneration) return@launch
                if (finishedLogin) {
                    pendingLayout = accountStore.load(syncAuth.accountId())
                    usedStampThrottle = UsedStampThrottle(pendingLayout?.base)
                    publishAccountLayout()
                }
                if (!syncAuth.hasSession()) return@launch
                uploadCrashReport()
                _state.update { it.copy(signedIn = true) }
                syncDeviceKeys()
                var remote = AccountLayout.response(syncAuth.request("GET", "/v1/layout").json)
                if (generation != accountGeneration) return@launch
                importAccountHosts(remote)
                usedStampThrottle.observe(remote)
                saveAccountLayout(pendingLayout?.pull(remote) ?: PendingLayout(remote))
                var retries = 0
                var retryConflict = false
                while (pendingLayout?.let { it.dirty && (retryConflict || usedStampThrottle.shouldPush(it)) } == true && retries++ < 4) {
                    val sent = pendingLayout!!.edited
                    val reply = syncAuth.request("PUT", "/v1/layout", JSONObject().put("base_revision", remote.revision).put("layout", sent.copy(hosts = if (syncAuth.supportsHosts) AccountHosts.publish(remote.hosts, _state.value.accountHosts) else null).json()))
                    if (generation != accountGeneration) return@launch
                    retryConflict = reply.status == 409
                    if (retryConflict) {
                        remote = AccountLayout.response(syncAuth.request("GET", "/v1/layout").json)
                        if (generation != accountGeneration) return@launch
                        importAccountHosts(remote)
                        usedStampThrottle.observe(remote)
                        saveAccountLayout(pendingLayout!!.pull(remote))
                    } else {
                        remote = AccountLayout.response(reply.json)
                        importAccountHosts(remote)
                        usedStampThrottle.observe(remote)
                        saveAccountLayout(pendingLayout!!.acknowledge(sent, remote))
                    }
                }
                _state.update { it.copy(accountError = if (pendingLayout?.let { usedStampThrottle.shouldPush(it) } == true) "Account changed repeatedly; pending edits will retry" else null) }
            } catch (e: Exception) {
                if (generation == accountGeneration) _state.update { it.copy(signedIn = syncAuth.hasSession(), accountError = e.message ?: "Account offline; pending edits saved") }
            } finally {
                accountRequestRunning = false
                _state.update { it.copy(accountSyncing = false) }
            }
        }
    }

    override fun onCleared() {
        _state.value.terminal?.close()
        closeWarmReaders()
        super.onCleared()
    }

    private data class HostRefresh(
        val hostId: String,
        val sessions: List<TmuxSession> = emptyList(),
        val etaStates: Map<String, TmuxText.SessionEtaState> = emptyMap(),
        val error: String? = null,
        val hostKey: PresentedHostKey? = null,
    )

    private fun visibleInboxSessions(state: AppUiState): List<TmuxSession> = state.sessions
        .filterNot { isHiddenAsMuted(it, state.accountLayout, state.accountHosts, state.selectedWorkspaceId) }
        .filter { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            val archived = key in state.archivedSessionKeys
            val bucketMatches = when (state.sessionBucket) {
                SessionBucket.ARCHIVE -> archived
                SessionBucket.WAITING -> archived
                else -> !archived
            }
            bucketMatches && when (state.selectedWorkspaceId) {
                null -> true
                FAVORITES_SPLIT_ID -> isFavoriteSession(session, state.accountLayout, state.accountHosts)
                FREQUENT_SPLIT_ID -> true
                else -> state.sessionWorkspaceIds[key] == state.selectedWorkspaceId
            }
        }
        .let { sessions ->
            if (state.selectedWorkspaceId == FREQUENT_SPLIT_ID) {
                frequentSessions(sessions, state.accountLayout, state.accountHosts, System.currentTimeMillis())
            } else SessionSearch.newestFirst(sessions, state.sessionInteractionEpochSeconds)
        }

    private companion object {
        const val MAX_WARM_READERS = 3
        const val READER_CACHE_TTL_MS = 15 * 60 * 1000L
        const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        const val MAX_DISPLAY_NAME_LENGTH = 63
        const val IDLE_OBSERVATIONS_TO_RELEASE = 2
        const val SESSION_REFRESH_INTERVAL_MS = 10_000L
        const val BACKGROUND_REFRESH_EVERY = 6
    }
}
