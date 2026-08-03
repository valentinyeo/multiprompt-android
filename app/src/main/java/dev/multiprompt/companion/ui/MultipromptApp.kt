package dev.multiprompt.companion.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.terminal.TerminalStatus
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.update.UpdateState
import java.io.ByteArrayOutputStream
import org.connectbot.terminal.Terminal

@Composable
fun MultipromptApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updates.state.collectAsState()
    val context = LocalContext.current
    var permissionLaunchVersion by remember { mutableStateOf<Long?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pending = viewModel.updates.state.value as? UpdateState.PermissionRequired
        if (pending != null) viewModel.updates.resumeAfterPermission(pending.release)
    }

    LaunchedEffect(updateState) {
        val required = updateState as? UpdateState.PermissionRequired ?: return@LaunchedEffect
        if (permissionLaunchVersion != required.release.versionCode) {
            permissionLaunchVersion = required.release.versionCode
            permissionLauncher.launch(viewModel.updates.unknownSourcesSettingsIntent())
        }
    }

    val terminal = state.terminal
    if (terminal != null) {
        TerminalScreen(
            connection = terminal,
            title = state.terminalSession?.displayName ?: terminal.tmuxSessionName,
            columns = state.terminalSession?.columns ?: 0,
            onBack = viewModel::closeTerminal,
            onSwitchSession = viewModel::openAdjacentSession,
        )
        return
    }

    val reader = state.reader
    val readerSession = state.readerSession
    if (reader != null && readerSession != null) {
        ReaderScreen(
            connection = reader,
            dictation = viewModel.dictation,
            session = readerSession,
            hostLabel = state.hosts.firstOrNull { it.id == readerSession.hostId }?.label.orEmpty(),
            unread = SessionReadStore.key(readerSession.hostId, readerSession.name) in state.unreadSessionKeys,
            onBack = viewModel::closeReader,
            onMarkRead = viewModel::markReaderRead,
            onOpenTerminal = { viewModel.openTerminal(readerSession) },
        )
        return
    }

    if (state.editorVisible) {
        HostEditorScreen(
            host = state.editorHost,
            error = state.editorError,
            onCancel = viewModel::hideHostEditor,
            onSave = viewModel::saveHost,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Header(
                    refreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                )
                val available = updateState as? UpdateState.Available
                if (available != null) {
                    UpdateBanner(available.release) { viewModel.installUpdate(available.release) }
                }
            }
        },
        bottomBar = {
            NavigationBar(Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = state.section == AppSection.SESSIONS,
                    onClick = { viewModel.select(AppSection.SESSIONS) },
                    icon = { Icon(Icons.Default.Terminal, null) },
                    label = { Text("Sessions") },
                )
                NavigationBarItem(
                    selected = state.section == AppSection.HOSTS,
                    onClick = { viewModel.select(AppSection.HOSTS) },
                    icon = { Icon(Icons.Default.Computer, null) },
                    label = { Text("Hosts") },
                )
                NavigationBarItem(
                    selected = state.section == AppSection.UPDATE,
                    onClick = { viewModel.select(AppSection.UPDATE) },
                    icon = { Icon(Icons.Default.SystemUpdate, null) },
                    label = { Text("Update") },
                )
            }
        },
        floatingActionButton = {
            if (state.section == AppSection.HOSTS) {
                FloatingActionButton(onClick = { viewModel.showHostEditor() }) {
                    Icon(Icons.Default.Add, "Add host")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.section) {
                AppSection.SESSIONS -> SessionsScreen(state, viewModel::openReader) {
                    viewModel.select(AppSection.HOSTS)
                    viewModel.showHostEditor()
                }
                AppSection.HOSTS -> HostsScreen(
                    state = state,
                    onEdit = viewModel::showHostEditor,
                    onDelete = viewModel::deleteHost,
                    onTrust = viewModel::trustHostKey,
                )
                AppSection.UPDATE -> UpdateScreen(
                    state = updateState,
                    onCheck = { viewModel.updates.check(force = true) },
                    onInstall = viewModel::installUpdate,
                )
            }
        }
    }

    state.pendingHostKeys.entries.firstOrNull()?.let { (hostId, key) ->
        val hostLabel = state.hosts.firstOrNull { it.id == hostId }?.label ?: "SSH host"
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (key.changed) "SSH host key changed" else "Trust $hostLabel?")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (key.changed) {
                            "The saved identity for $hostLabel no longer matches. Only continue if you intentionally changed the server key."
                        } else {
                            "$hostLabel presented this identity on the first connection:"
                        },
                    )
                    Text(
                        key.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.trustHostKey(hostId) }) {
                    Text(if (key.changed) "Replace trusted key" else "Trust and connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissHostKey(hostId) }) {
                    Text("Not now")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(refreshing: Boolean, onRefresh: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("multiprompt", fontWeight = FontWeight.Bold)
                Text("mobile tmux companion", style = MaterialTheme.typography.labelSmall)
            }
        },
        actions = {
            if (refreshing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        },
    )
}

@Composable
private fun UpdateBanner(release: UpdateRelease, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("v${release.versionName} is ready", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        FilledTonalButton(onClick = onInstall) { Text("Update now") }
    }
}

@Composable
private fun SessionsScreen(
    state: AppUiState,
    onOpen: (TmuxSession) -> Unit,
    onAddHost: () -> Unit,
) {
    if (state.hosts.isEmpty()) {
        EmptyState("Connect your first VPS", "Import an SSH key, then the app will discover tmux sessions.", onAddHost)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.hosts.forEach { host ->
            item(key = "heading-${host.id}") {
                Text(host.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val error = state.hostErrors[host.id]
            if (error != null) {
                item(key = "error-${host.id}") { InlineError(error) }
            }
            val sessions = state.sessions.filter { it.hostId == host.id }
            items(sessions, key = { "${it.hostId}:${it.name}" }) { session ->
                SessionCard(
                    session = session,
                    unread = SessionReadStore.key(session.hostId, session.name) in state.unreadSessionKeys,
                ) { onOpen(session) }
            }
            if (sessions.isEmpty() && error == null && !state.refreshing) {
                item(key = "empty-${host.id}") {
                    Text("No tmux sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: TmuxSession, unread: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (unread) {
                    Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(session.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (session.title.isBlank()) {
                            "${session.agent.label} · ${session.windows} windows · ${relativeTime(session.lastActivityEpochSeconds)}"
                        } else {
                            "${session.name} · ${session.agent.label} · ${relativeTime(session.lastActivityEpochSeconds)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (session.attachedClients > 0) {
                    Text("LIVE ${session.attachedClients}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (session.preview.isNotBlank()) {
                Text(
                    session.preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = ReaderFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    connection: SessionReaderConnection,
    dictation: DeepgramDictation,
    session: TmuxSession,
    hostLabel: String,
    unread: Boolean,
    onBack: () -> Unit,
    onMarkRead: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val reader by connection.state.collectAsState()
    val dictationState by dictation.state.collectAsState()
    var prompt by remember(connection) { mutableStateOf("") }
    var pendingPromptAction by remember(connection) { mutableStateOf<Long?>(null) }
    var menuExpanded by remember(connection) { mutableStateOf(false) }
    var apiKeyDialogVisible by remember(connection) { mutableStateOf(false) }
    var apiKeyDraft by remember(connection) { mutableStateOf("") }
    var apiKeyError by remember(connection) { mutableStateOf<String?>(null) }
    var microphoneError by remember(connection) { mutableStateOf<String?>(null) }
    var dictationPrefix by remember(connection) { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var initialScrollComplete by remember(connection) { mutableStateOf(false) }
    val context = LocalContext.current
    val dictationActive = dictationState.status == DictationStatus.CONNECTING ||
        dictationState.status == DictationStatus.LISTENING ||
        dictationState.status == DictationStatus.FINISHING
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            microphoneError = null
            dictation.start()
        } else {
            microphoneError = "Microphone permission is required for dictation"
        }
    }
    val startDictation = {
        dictationPrefix = prompt.trimEnd()
        microphoneError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            dictation.start()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(reader.output) {
        val followBottom = !initialScrollComplete ||
            scrollState.maxValue - scrollState.value < with(density) { 56.dp.roundToPx() }
        withFrameNanos { }
        if (followBottom) scrollState.scrollTo(scrollState.maxValue)
        initialScrollComplete = true
    }
    LaunchedEffect(reader.completedActions) {
        val pending = pendingPromptAction
        if (pending != null && reader.completedActions > pending) {
            prompt = ""
            pendingPromptAction = null
        }
    }
    LaunchedEffect(reader.actionError) {
        if (reader.actionError != null) pendingPromptAction = null
    }
    LaunchedEffect(dictationState.transcript) {
        val spoken = dictationState.transcript.trim()
        if (spoken.isNotBlank()) {
            prompt = listOf(dictationPrefix, spoken)
                .filter(String::isNotBlank)
                .joinToString(" ")
        }
    }
    DisposableEffect(connection) {
        onDispose { dictation.stop() }
    }
    val submitPrompt = {
        if (prompt.isNotBlank() &&
            !reader.sending &&
            pendingPromptAction == null &&
            !dictationActive
        ) {
            val actionCount = reader.completedActions
            if (connection.sendPrompt(prompt)) {
                pendingPromptAction = actionCount
            }
        }
    }
    if (apiKeyDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                apiKeyDialogVisible = false
                apiKeyDraft = ""
                apiKeyError = null
            },
            title = { Text("Deepgram dictation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The key is encrypted with Android Keystore and stays on this device.")
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = {
                            apiKeyDraft = it
                            apiKeyError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (dictationState.configured) "Replacement API key" else "API key") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = apiKeyError != null,
                        supportingText = apiKeyError?.let { message -> { Text(message) } },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = apiKeyDraft.isNotBlank(),
                    onClick = {
                        if (dictation.saveApiKey(apiKeyDraft)) {
                            apiKeyDialogVisible = false
                            apiKeyDraft = ""
                            apiKeyError = null
                            startDictation()
                        } else {
                            apiKeyError = "Enter a valid API key"
                        }
                    },
                ) { Text("Save and dictate") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        apiKeyDialogVisible = false
                        apiKeyDraft = ""
                        apiKeyError = null
                    },
                ) { Text("Cancel") }
            },
        )
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "$hostLabel · ${session.agent.label} · ${readerStatusLabel(reader.status)}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sessions") }
                },
                actions = {
                    if (reader.status == ReaderStatus.Live) {
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(9.dp)
                                .background(MaterialTheme.colorScheme.secondary, CircleShape),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Session actions")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Enter / approve") },
                                enabled = !reader.sending,
                                onClick = {
                                    menuExpanded = false
                                    connection.sendEnter()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Interrupt session") },
                                enabled = !reader.sending,
                                onClick = {
                                    menuExpanded = false
                                    connection.interrupt()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (unread) "Mark read" else "Already read") },
                                enabled = unread,
                                onClick = {
                                    menuExpanded = false
                                    onMarkRead()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open live terminal") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenTerminal()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Dictation API key") },
                                onClick = {
                                    menuExpanded = false
                                    apiKeyDraft = ""
                                    apiKeyError = null
                                    apiKeyDialogVisible = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .imePadding()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message this session") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitPrompt() }),
                    readOnly = dictationActive,
                    trailingIcon = {
                        val sendEnabled = prompt.isNotBlank() &&
                            !reader.sending &&
                            pendingPromptAction == null &&
                            !dictationActive
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (dictationActive) {
                                        dictation.stop()
                                    } else if (dictationState.configured) {
                                        startDictation()
                                    } else {
                                        apiKeyDialogVisible = true
                                    }
                                },
                                enabled = !reader.sending,
                            ) {
                                when (dictationState.status) {
                                    DictationStatus.CONNECTING, DictationStatus.FINISHING -> {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    }
                                    DictationStatus.LISTENING -> {
                                        Icon(Icons.Default.Stop, "Stop dictation")
                                    }
                                    else -> {
                                        Icon(Icons.Default.Mic, "Start dictation")
                                    }
                                }
                            }
                            IconButton(
                                onClick = submitPrompt,
                                enabled = sendEnabled,
                                modifier = Modifier
                                    .padding(2.dp)
                                    .background(
                                        if (sendEnabled) MaterialTheme.colorScheme.primary else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        CircleShape,
                                    ),
                            ) {
                                if (reader.sending) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        "Send prompt",
                                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                (microphoneError ?: dictationState.error)?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                reader.actionError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (reader.status == ReaderStatus.Connecting && reader.output.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Connecting to live output…")
                }
            }
            val failure = reader.status as? ReaderStatus.Failed
            if (failure != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(failure.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Reconnecting automatically…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            SelectionContainer {
                Text(
                    reader.output.ifBlank {
                        if (failure == null && reader.status != ReaderStatus.Connecting) "No recent output" else ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = ReaderFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Start,
                    softWrap = true,
                )
            }
        }
    }
}

private fun readerStatusLabel(status: ReaderStatus): String = when (status) {
    ReaderStatus.Connecting -> "Connecting"
    ReaderStatus.Live -> "Live"
    ReaderStatus.Closed -> "Closed"
    is ReaderStatus.Failed -> "Reconnecting"
}

@Composable
private fun HostsScreen(
    state: AppUiState,
    onEdit: (HostProfile) -> Unit,
    onDelete: (HostProfile) -> Unit,
    onTrust: (String) -> Unit,
) {
    var deleting by remember { mutableStateOf<HostProfile?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.hosts, key = { it.id }) { host ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(host.label, fontWeight = FontWeight.Bold)
                            Text(
                                "${host.username}@${host.hostname}:${host.port}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        IconButton(onClick = { onEdit(host) }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { deleting = host }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    host.hostKeyFingerprint?.let {
                        Text("Pinned $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    state.pendingHostKeys[host.id]?.let { key ->
                        HorizontalDivider()
                        Text(
                            if (key.changed) "WARNING: the SSH host key changed" else "First connection: verify this SSH host key",
                            color = if (key.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(key.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Button(onClick = { onTrust(host.id) }) {
                            Text(if (key.changed) "Replace trusted key" else "Trust this key")
                        }
                    }
                    state.hostErrors[host.id]?.takeIf { state.pendingHostKeys[host.id] == null }?.let { InlineError(it) }
                }
            }
        }
    }
    deleting?.let { host ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${host.label}?") },
            text = { Text("The encrypted private key stored for this host will also be removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(host); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditorScreen(
    host: HostProfile?,
    error: String?,
    onCancel: () -> Unit,
    onSave: (HostDraft, ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    var label by remember(host?.id) { mutableStateOf(host?.label.orEmpty()) }
    var hostname by remember(host?.id) { mutableStateOf(host?.hostname.orEmpty()) }
    var port by remember(host?.id) { mutableStateOf((host?.port ?: 22).toString()) }
    var username by remember(host?.id) { mutableStateOf(host?.username.orEmpty()) }
    var passphrase by remember(host?.id) { mutableStateOf("") }
    var importedKey by remember(host?.id) { mutableStateOf<ByteArray?>(null) }
    var importedName by remember(host?.id) { mutableStateOf<String?>(null) }
    var importError by remember(host?.id) { mutableStateOf<String?>(null) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.readSmallFile(uri, 256 * 1024) }
                .onSuccess { bytes -> importedKey = bytes; importedName = uri.lastPathSegment; importError = null }
                .onFailure { importError = it.message ?: "Could not read key" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(if (host == null) "Add VPS" else "Edit ${host.label}") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Label") }, singleLine = true) }
            item { OutlinedTextField(hostname, { hostname = it }, Modifier.fillMaxWidth(), label = { Text("Hostname or IP") }, singleLine = true) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(username, { username = it }, Modifier.weight(1f), label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit) },
                        Modifier.weight(0.55f),
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            item {
                OutlinedButton(onClick = { keyPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream", "text/plain", "*/*")) }) {
                    Text(importedName?.let { "Key: $it" } ?: if (host == null) "Import private key" else "Replace private key")
                }
            }
            item {
                OutlinedTextField(
                    passphrase,
                    { passphrase = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (host == null) "Key passphrase (optional)" else "New passphrase (leave blank to keep)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            (error ?: importError)?.let { message -> item { InlineError(message) } }
            item {
                Button(
                    onClick = {
                        onSave(
                            HostDraft(host?.id, label, hostname, port, username, passphrase),
                            importedKey,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
            }
            item {
                Text(
                    "Private keys and passphrases are encrypted with a non-exportable Android Keystore key. The server fingerprint is pinned on first connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpdateScreen(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateRelease) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("App updates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        when (state) {
            UpdateState.Idle -> Text("Updates have not been checked yet.")
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Checking GitHub Releases…")
            }
            is UpdateState.Current -> Text("You have the latest version.", color = MaterialTheme.colorScheme.secondary)
            is UpdateState.Available -> UpdateAvailableCard(state.release) { onInstall(state.release) }
            is UpdateState.PermissionRequired -> Text("Waiting for Android's ‘install unknown apps’ permission…")
            is UpdateState.Downloading -> {
                Text("Downloading v${state.release.versionName}…")
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            UpdateState.Installing -> Text("APK verified. Installing update…", color = MaterialTheme.colorScheme.secondary)
            is UpdateState.Failed -> {
                InlineError(state.message)
                state.release?.let { release -> Button(onClick = { onInstall(release) }) { Text("Try update again") } }
            }
        }
        OutlinedButton(onClick = onCheck, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.size(8.dp))
            Text("Check now")
        }
        HorizontalDivider()
        Text(
            "The app downloads only the fixed android-latest release, verifies SHA-256, package name, version, and the installed signing certificate, then asks Android to update itself. Android may still require a final system confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateAvailableCard(release: UpdateRelease, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("v${release.versionName} available", fontWeight = FontWeight.Bold)
            if (release.notes.isNotBlank()) Text(release.notes)
            Text("${release.sizeBytes / 1024 / 1024} MB", style = MaterialTheme.typography.labelMedium)
            Button(onClick = onInstall) { Text("Download and update") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    connection: TerminalConnection,
    title: String,
    columns: Int,
    onBack: () -> Unit,
    onSwitchSession: (Int) -> Unit,
) {
    val status by connection.status.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The desktop owns the window width, so the phone shrinks its glyphs until that width
    // fits instead of resizing anything. Measure the real monospace advance: a guessed ratio
    // left the emulator a few columns narrower than tmux believed, so every full-width line
    // wrapped and redraws of the status line stacked into ghost copies.
    val textMeasurer = rememberTextMeasurer()
    val fontSize = remember(screenWidthDp, columns) {
        val target = if (columns > 0) columns else FALLBACK_COLUMNS
        val pxPerCharAt100Sp = textMeasurer.measure(
            AnnotatedString("0".repeat(20)),
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp),
        ).size.width / 20f
        val screenPx = with(density) { screenWidthDp.dp.toPx() }
        // 2% slack so rounding lands the emulator on target or slightly above, never below.
        (100f * screenPx / (target * pxPerCharAt100Sp) * 0.98f).coerceIn(4f, 14f).sp
    }
    // Pan the terminal up rather than shrinking it: resizing would renegotiate the PTY and
    // drag the desktop pane down with it.
    val keyboardHeightPx = WindowInsets.ime.getBottom(density)
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${connection.tmuxSessionName} · ${statusLabel(status)}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close terminal") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color(0xFF090B10)).clipToBounds()) {
            Terminal(
                terminalEmulator = connection.emulator,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -keyboardHeightPx) }
                    .horizontalSwipe(onSwitchSession),
                initialFontSize = fontSize,
                backgroundColor = Color(0xFF090B10),
                foregroundColor = Color(0xFFE5E7EB),
                keyboardEnabled = true,
                showSoftKeyboard = true,
                onPasteRequest = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(connection::paste)
                },
            )
            if (status is TerminalStatus.Connecting) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            if (status is TerminalStatus.Failed) {
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((status as TerminalStatus.Failed).message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Computer, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = action) { Text("Add VPS") }
    }
}

@Composable
private fun InlineError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "unknown activity"
    return DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

/**
 * Detects a sideways flick before the terminal sees it. The terminal consumes touches for
 * scrolling and selection, so this watches the initial pass and only claims the gesture once
 * the drag is clearly horizontal, leaving vertical scrolling untouched.
 */
private fun Modifier.horizontalSwipe(onSwipe: (Int) -> Unit) = pointerInput(onSwipe) {
    val threshold = 72.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed }
                ?: continue
            var totalX = 0f
            var totalY = 0f
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                if (!change.pressed) break
                totalX += change.positionChange().x
                totalY += change.positionChange().y
                if (!claimed && kotlin.math.abs(totalX) > threshold &&
                    kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 2
                ) {
                    claimed = true
                    onSwipe(if (totalX < 0) 1 else -1)
                }
                if (claimed) change.consume()
            }
        }
    }
}

/** Used only when tmux did not report a width for the session. */
private const val FALLBACK_COLUMNS = 100

private fun statusLabel(status: TerminalStatus): String = when (status) {
    TerminalStatus.Connecting -> "Connecting…"
    TerminalStatus.Connected -> "SSH · tmux attached"
    TerminalStatus.Closed -> "Closed"
    is TerminalStatus.Failed -> "Connection failed"
}

private fun Context.readSmallFile(uri: Uri, maxBytes: Int): ByteArray {
    val input = contentResolver.openInputStream(uri) ?: error("Could not open selected key")
    return input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "Selected key is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
