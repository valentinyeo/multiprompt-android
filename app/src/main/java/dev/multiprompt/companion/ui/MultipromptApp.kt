package dev.multiprompt.companion.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.SessionBucket
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.R
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.DissolvedSession
import dev.multiprompt.companion.data.CrashReport
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.data.ReminderTimeParser
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.SessionSearch
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.terminal.TerminalStatus
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.update.UpdateState
import dev.multiprompt.companion.upload.ScreencastUploader
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal

@Composable
fun MultipromptApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updates.state.collectAsState()
    val context = LocalContext.current
    var newSessionWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var permissionLaunchVersion by remember { mutableStateOf<Long?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pending = viewModel.updates.state.value as? UpdateState.PermissionRequired
        if (pending != null) viewModel.updates.resumeAfterPermission(pending.release)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(updateState) {
        val required = updateState as? UpdateState.PermissionRequired ?: return@LaunchedEffect
        if (permissionLaunchVersion != required.release.versionCode) {
            permissionLaunchVersion = required.release.versionCode
            permissionLauncher.launch(viewModel.updates.unknownSourcesSettingsIntent())
        }
    }

    BackHandler(
        enabled = state.editorVisible,
        onBack = viewModel::hideHostEditor,
    )
    BackHandler(
        enabled = state.terminal == null &&
            state.reader == null &&
            !state.editorVisible &&
            state.section != AppSection.SESSIONS,
        onBack = { viewModel.select(AppSection.SESSIONS) },
    )

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
            screencast = viewModel.screencast,
            session = readerSession,
            unread = SessionReadStore.key(readerSession.hostId, readerSession.name) in state.unreadSessionKeys,
            archived = SessionReadStore.key(readerSession.hostId, readerSession.name) in state.archivedSessionKeys,
            initialFontScale = viewModel.readerFontScale(readerSession),
            onBack = viewModel::closeReader,
            onMarkRead = viewModel::markReaderRead,
            onArchiveToggle = {
                if (SessionReadStore.key(readerSession.hostId, readerSession.name) in
                    state.archivedSessionKeys
                ) {
                    viewModel.restoreSession(readerSession)
                } else {
                    viewModel.archiveReaderAndOpenNext()
                }
            },
            onRemind = viewModel::archiveReaderUntilAndOpenNext,
            onOpenTerminal = { viewModel.openTerminal(readerSession) },
            onSwitchSession = viewModel::openAdjacentReaderSession,
            onFontScaleChanged = { scale -> viewModel.saveReaderFontScale(readerSession, scale) },
            onRename = { name -> viewModel.renameSession(readerSession, name) },
            onDissolve = { viewModel.dissolveSession(readerSession) },
            onEnd = { viewModel.endSession(readerSession) },
            onSessionInteraction = { viewModel.noteSessionInteraction(readerSession) },
            technicalMode = state.readerTechnicalMode,
            onTechnicalModeChanged = viewModel::setReaderTechnicalMode,
            onResetFontScale = { viewModel.resetReaderFontScale(readerSession) },
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
                if (state.section != AppSection.SESSIONS) {
                    Header(
                        refreshing = state.refreshing,
                        onRefresh = viewModel::refresh,
                        section = state.section,
                        onSelect = viewModel::select,
                        onBack = { viewModel.select(AppSection.SESSIONS) },
                    )
                }
                val available = updateState as? UpdateState.Available
                if (available != null) {
                    UpdateBanner(available.release) { viewModel.installUpdate(available.release) }
                }
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
                AppSection.SESSIONS -> SessionsScreen(
                    state = state,
                    onOpen = viewModel::openReader,
                    onOpenTerminal = viewModel::openTerminal,
                    onArchive = viewModel::archiveSession,
                    onArchiveUntil = viewModel::archiveSessionUntil,
                    onRestore = viewModel::restoreSession,
                    onDissolve = viewModel::dissolveSession,
                    onEnd = viewModel::endSession,
                    onRestoreDissolved = viewModel::restoreDissolvedSession,
                    onForgetDissolved = viewModel::forgetDissolvedSession,
                    onMarkUnread = viewModel::markSessionUnread,
                    onToggleArchived = viewModel::toggleArchivedSessions,
                    onSelectSessionBucket = viewModel::selectSessionBucket,
                    onSelectWorkspace = viewModel::selectWorkspace,
                    onSwitchWorkspace = viewModel::openAdjacentWorkspace,
                    onMoveWorkspaceSplit = viewModel::moveWorkspaceSplit,
                    onResetWorkspaceSplitOrder = viewModel::resetWorkspaceSplitOrder,
                    onCreateWorkspace = viewModel::createWorkspace,
                    onMoveSession = viewModel::moveSession,
                    onRenameSession = viewModel::renameSession,
                    onRefresh = viewModel::refresh,
                    onSelectSection = viewModel::select,
                    onNewSession = { newSessionWorkspace = it },
                    onSetNewestSessionsAtBottom = viewModel::setNewestSessionsAtBottom,
                    onSetAllSplitOnRight = viewModel::setAllSplitOnRight,
                    onSetReaderDefaultFontScale = viewModel::setReaderDefaultFontScale,
                    readerDefaultFontScale = state.readerDefaultFontScale,
                    crashReport = state.crashReport,
                    onClearCrashReport = viewModel::clearCrashReport,
                    onAddHost = {
                        viewModel.select(AppSection.HOSTS)
                        viewModel.showHostEditor()
                    },
                )
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

    newSessionWorkspace?.let { workspace ->
        AlertDialog(
            onDismissRequest = { newSessionWorkspace = null },
            title = { Text("New session in ${workspace.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${workspace.remotePath}\nChoose how this tmux session should start.")
                    Button(
                        onClick = {
                            newSessionWorkspace = null
                            viewModel.createClaudeSession(workspace)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Claude Code") }
                    OutlinedButton(
                        onClick = {
                            newSessionWorkspace = null
                            viewModel.createShellSession(workspace)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Full terminal") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { newSessionWorkspace = null }) { Text("Cancel") }
            },
        )
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
private fun Header(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    onBack: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = if (section != AppSection.SESSIONS) {
            {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sessions")
                }
            }
        } else {
            {}
        },
        title = {
            Column {
                Text(
                    when (section) {
                        AppSection.SESSIONS -> "Sessions"
                        AppSection.HOSTS -> "Hosts"
                        AppSection.UPDATE -> "Update"
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text("multiprompt", style = MaterialTheme.typography.labelSmall)
            }
        },
        actions = {
            if (refreshing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "Open navigation")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    listOf(
                        AppSection.SESSIONS to "Sessions",
                        AppSection.HOSTS to "Hosts",
                        AppSection.UPDATE to "Update",
                    ).forEach { (destination, label) ->
                        DropdownMenuItem(
                            text = { Text(if (section == destination) "✓ $label" else label) },
                            onClick = {
                                menuExpanded = false
                                onSelect(destination)
                            },
                        )
                    }
                }
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
    onOpenTerminal: (TmuxSession) -> Unit,
    onArchive: (TmuxSession) -> Unit,
    onArchiveUntil: (TmuxSession, Long?) -> Unit,
    onRestore: (TmuxSession) -> Unit,
    onDissolve: (TmuxSession) -> Unit,
    onEnd: (TmuxSession) -> Unit,
    onRestoreDissolved: (DissolvedSession) -> Unit,
    onForgetDissolved: (DissolvedSession) -> Unit,
    onMarkUnread: (TmuxSession) -> Unit,
    onToggleArchived: () -> Unit,
    onSelectSessionBucket: (SessionBucket) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onSwitchWorkspace: (Int) -> Unit,
    onMoveWorkspaceSplit: (String?, Int) -> Unit,
    onResetWorkspaceSplitOrder: () -> Unit,
    onCreateWorkspace: (String, String, String) -> String?,
    onMoveSession: (TmuxSession, Workspace) -> Unit,
    onRenameSession: (TmuxSession, String) -> String?,
    onRefresh: () -> Unit,
    onSelectSection: (AppSection) -> Unit,
    onNewSession: (Workspace) -> Unit,
    onSetNewestSessionsAtBottom: (Boolean) -> Unit,
    onSetAllSplitOnRight: (Boolean) -> Unit,
    readerDefaultFontScale: Float,
    onSetReaderDefaultFontScale: (Float) -> Unit,
    crashReport: CrashReport?,
    onClearCrashReport: () -> Unit,
    onAddHost: () -> Unit,
) {
    if (state.hosts.isEmpty()) {
        EmptyState("Connect your first VPS", "Import an SSH key, then the app will discover tmux sessions.", onAddHost)
        return
    }
    var workspaceDialogVisible by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
    var workspacePath by remember { mutableStateOf("") }
    var workspaceHostId by remember(state.hosts) { mutableStateOf(state.hosts.first().id) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var splitOrderDialogVisible by remember { mutableStateOf(false) }
    var reminderPromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var dissolvePromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var endPromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var renamePromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var overflowExpanded by remember { mutableStateOf(false) }
    var bucketMenuExpanded by remember { mutableStateOf(false) }
    var newSessionWorkspacePickerVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var crashReportVisible by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val splitScrollState = rememberScrollState()
    val displayedWorkspaceSplitIds = if (state.allSplitOnRight) {
        state.workspaceSplitIds.asReversed()
    } else {
        state.workspaceSplitIds
    }
    LaunchedEffect(searchVisible) {
        if (searchVisible) searchFocusRequester.requestFocus()
    }
    LaunchedEffect(state.allSplitOnRight, state.workspaceSplitIds) {
        snapshotFlow { splitScrollState.maxValue }.collect { maximum ->
            splitScrollState.scrollTo(if (state.allSplitOnRight) maximum else 0)
        }
    }
    renamePromptSession?.let { session ->
        AlertDialog(
            onDismissRequest = {
                renamePromptSession = null
                renameError = null
            },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = {
                        renameDraft = it
                        renameError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    supportingText = renameError?.let { message -> { Text(message) } },
                    isError = renameError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        renameError = onRenameSession(session, renameDraft)
                        if (renameError == null) renamePromptSession = null
                    }),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameError = onRenameSession(session, renameDraft)
                    if (renameError == null) renamePromptSession = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = {
                    renamePromptSession = null
                    renameError = null
                }) { Text("Cancel") }
            },
        )
    }
    if (settingsVisible) {
        AlertDialog(
            onDismissRequest = { settingsVisible = false },
            title = { Text("Session list settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Newest sessions at bottom", Modifier.weight(1f))
                        Switch(
                            checked = state.newestSessionsAtBottom,
                            onCheckedChange = onSetNewestSessionsAtBottom,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("All split on right", Modifier.weight(1f))
                        Switch(
                            checked = state.allSplitOnRight,
                            onCheckedChange = onSetAllSplitOnRight,
                        )
                    }
                    Text("Default Reader font size")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(0.9f to "Small", 1f to "Normal", 1.15f to "Large").forEach { (scale, label) ->
                            if (kotlin.math.abs(readerDefaultFontScale - scale) < 0.01f) {
                                FilledTonalButton(
                                    onClick = { onSetReaderDefaultFontScale(scale) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetReaderDefaultFontScale(scale) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }
                    crashReport?.let {
                        HorizontalDivider()
                        Text(
                            "A crash report from the previous launch is available.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { crashReportVisible = true }) {
                                Text("View report")
                            }
                            TextButton(onClick = onClearCrashReport) { Text("Dismiss") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsVisible = false }) { Text("Done") }
            },
        )
    }
    if (crashReportVisible) {
        crashReport?.let { report ->
            AlertDialog(
                onDismissRequest = { crashReportVisible = false },
                title = { Text("Crash report") },
                text = {
                    SelectionContainer {
                        Text(
                            report.asText(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            fontSize = 11.sp,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("multiprompt crash report", report.asText()))
                        crashReportVisible = false
                    }) { Text("Copy report") }
                },
                dismissButton = {
                    TextButton(onClick = { crashReportVisible = false }) { Text("Close") }
                },
            )
        }
    }
    if (newSessionWorkspacePickerVisible) {
        AlertDialog(
            onDismissRequest = { newSessionWorkspacePickerVisible = false },
            title = { Text("New session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Choose a workspace.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.workspaces.forEach { workspace ->
                        TextButton(
                            onClick = {
                                newSessionWorkspacePickerVisible = false
                                onNewSession(workspace)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                workspace.name,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { newSessionWorkspacePickerVisible = false }) { Text("Cancel") }
            },
        )
    }
    reminderPromptSession?.let { session ->
        ReminderDialog(
            session = session,
            onDismiss = { reminderPromptSession = null },
            onRemind = { dueAt ->
                reminderPromptSession = null
                onArchiveUntil(session, dueAt)
            },
        )
    }
    dissolvePromptSession?.let { session ->
        AlertDialog(
            onDismissRequest = { dissolvePromptSession = null },
            title = { Text("Archive ${session.displayName}?") },
            text = { Text("This ends its tmux session but saves the name and resume command so it can be restored later.") },
            confirmButton = {
                Button(onClick = {
                    dissolvePromptSession = null
                    onDissolve(session)
                }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { dissolvePromptSession = null }) { Text("Cancel") }
            },
        )
    }
    endPromptSession?.let { session ->
        AlertDialog(
            onDismissRequest = { endPromptSession = null },
            title = { Text("End ${session.displayName} completely?") },
            text = { Text("This kills the coding agent and tmux session without saving a restore record.") },
            confirmButton = {
                Button(onClick = {
                    endPromptSession = null
                    onEnd(session)
                }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { endPromptSession = null }) { Text("Cancel") } },
        )
    }
    if (splitOrderDialogVisible) {
        AlertDialog(
            onDismissRequest = { splitOrderDialogVisible = false },
            title = { Text("Arrange splits") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.allSplitOnRight) {
                            "Automatic order keeps All on the right, beside the most recently active splits."
                        } else {
                            "Automatic order keeps All on the left, then the most recently active splits."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    displayedWorkspaceSplitIds.forEachIndexed { index, splitId ->
                        val workspace = splitId?.let { id ->
                            state.workspaces.firstOrNull { it.id == id }
                        }
                        val splitName = workspace?.name ?: "All"
                        val sessionCount = if (splitId == null) {
                            state.sessions.size
                        } else {
                            state.sessionWorkspaceIds.values.count { it == splitId }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(splitName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "$sessionCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = {
                                    onMoveWorkspaceSplit(splitId, if (state.allSplitOnRight) 1 else -1)
                                },
                                enabled = index > 0,
                            ) { Text("↑") }
                            IconButton(
                                onClick = {
                                    onMoveWorkspaceSplit(splitId, if (state.allSplitOnRight) -1 else 1)
                                },
                                enabled = index < displayedWorkspaceSplitIds.lastIndex,
                            ) { Text("↓") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { splitOrderDialogVisible = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = onResetWorkspaceSplitOrder) { Text("Automatic order") }
            },
        )
    }
    if (workspaceDialogVisible) {
        AlertDialog(
            onDismissRequest = { workspaceDialogVisible = false },
            title = { Text("New workspace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        workspaceName,
                        { workspaceName = it; workspaceError = null },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        workspacePath,
                        { workspacePath = it; workspaceError = null },
                        label = { Text("VPS project path") },
                        placeholder = { Text("/home/valentin/projects/project") },
                        singleLine = true,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        state.hosts.forEach { host ->
                            TextButton(onClick = { workspaceHostId = host.id }) {
                                Text(if (workspaceHostId == host.id) "✓ ${host.label}" else host.label)
                            }
                        }
                    }
                    workspaceError?.let { InlineError(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    workspaceError = onCreateWorkspace(workspaceName, workspaceHostId, workspacePath)
                    if (workspaceError == null) {
                        workspaceDialogVisible = false
                        workspaceName = ""
                        workspacePath = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { workspaceDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
    val normalizedQuery = searchQuery.trim()
    val hostLabels = state.hosts.associate { it.id to it.label }
    val workspaceNames = state.workspaces.associate { it.id to it.name }
    val visibleSessions = SessionSearch.newestFirst(state.sessions
        .filter { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            state.sessionBucket != SessionBucket.ARCHIVE &&
                (key in state.archivedSessionKeys) == (state.sessionBucket == SessionBucket.WAITING) &&
                (normalizedQuery.isNotBlank() ||
                    state.selectedWorkspaceId == null ||
                    state.sessionWorkspaceIds[key] == state.selectedWorkspaceId) &&
                SessionSearch.matches(
                    session = session,
                    query = normalizedQuery,
                    hostLabel = hostLabels[session.hostId].orEmpty(),
                    workspaceName = workspaceNames[state.sessionWorkspaceIds[key]].orEmpty(),
                )
        }, state.sessionInteractionEpochSeconds)
    val visibleDissolvedSessions = if (state.sessionBucket == SessionBucket.ARCHIVE) {
        state.dissolvedSessions.filter { session ->
            normalizedQuery.isBlank() ||
                session.displayName.contains(normalizedQuery, ignoreCase = true) ||
                session.tmuxSessionName.contains(normalizedQuery, ignoreCase = true) ||
                session.workspaceName.contains(normalizedQuery, ignoreCase = true)
        }
    } else {
        emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(
                    onClick = { bucketMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        when (state.sessionBucket) {
                            SessionBucket.OPEN -> "Inbox"
                            SessionBucket.WAITING -> "Waiting"
                            SessionBucket.ARCHIVE -> "Archive"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(Icons.Default.ExpandMore, "Switch session bucket")
                }
                DropdownMenu(
                    expanded = bucketMenuExpanded,
                    onDismissRequest = { bucketMenuExpanded = false },
                ) {
                    listOf(
                        SessionBucket.OPEN to "Inbox",
                        SessionBucket.WAITING to "Waiting",
                        SessionBucket.ARCHIVE to "Archive",
                    ).forEach { (bucket, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                bucketMenuExpanded = false
                                onSelectSessionBucket(bucket)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(
                enabled = state.workspaces.isNotEmpty() && !state.creatingSession,
                onClick = {
                    val selected = state.selectedWorkspaceId?.let { selectedId ->
                        state.workspaces.firstOrNull { it.id == selectedId }
                    }
                    if (selected != null) onNewSession(selected) else newSessionWorkspacePickerVisible = true
                },
            ) {
                if (state.creatingSession) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, "New session")
                }
            }
            IconButton(onClick = {
                searchVisible = !searchVisible
                if (!searchVisible) {
                    searchQuery = ""
                    focusManager.clearFocus()
                }
            }) {
                Icon(
                    if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                    if (searchVisible) "Close session search" else "Search sessions",
                )
            }
            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "Session list actions")
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            overflowExpanded = false
                            settingsVisible = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Arrange splits") },
                        onClick = {
                            overflowExpanded = false
                            splitOrderDialogVisible = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New workspace") },
                        onClick = {
                            overflowExpanded = false
                            workspaceError = null
                            workspaceDialogVisible = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = {
                            overflowExpanded = false
                            onRefresh()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Hosts") },
                        onClick = {
                            overflowExpanded = false
                            onSelectSection(AppSection.HOSTS)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Update") },
                        onClick = {
                            overflowExpanded = false
                            onSelectSection(AppSection.UPDATE)
                        },
                    )
                }
            }
        }
        if (searchVisible) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    placeholder = { Text("Search all sessions") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                if (normalizedQuery.isNotBlank()) {
                    Text(
                        "${visibleSessions.size} ${if (visibleSessions.size == 1) "result" else "results"} across all splits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalSwipe(onSwitchWorkspace, PointerEventPass.Final),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            reverseLayout = state.newestSessionsAtBottom,
        ) {
            if (state.cachedHostIds.isNotEmpty()) {
                item(key = "cached-sessions-notice") {
                    CachedSessionsNotice(
                        hostLabels = state.cachedHostIds.mapNotNull { hostLabels[it] },
                        onRetry = onRefresh,
                        retryEnabled = !state.refreshing,
                    )
                }
            }
            state.hostErrors.forEach { (hostId, error) ->
                if (hostId !in state.cachedHostIds) {
                    item(key = "error-$hostId") { InlineError(error) }
                }
            }
            state.sessionActionError?.let { error ->
                item(key = "session-action-error") { InlineError(error) }
            }
            items(visibleSessions, key = { "${it.hostId}:${it.name}" }) { session ->
                val key = SessionReadStore.key(session.hostId, session.name)
                SessionCard(
                    session = session,
                    workspaces = state.workspaces,
                    unread = key in state.unreadSessionKeys,
                    archived = key in state.archivedSessionKeys,
                    onClick = { onOpen(session) },
                    onOpenTerminal = { onOpenTerminal(session) },
                    onArchive = { onArchive(session) },
                    onRemind = { reminderPromptSession = session },
                    onArchiveToggle = {
                        if (key in state.archivedSessionKeys) onRestore(session) else onArchive(session)
                    },
                    onMarkUnread = { onMarkUnread(session) },
                    onMove = { workspace -> onMoveSession(session, workspace) },
                    onRename = {
                        renameDraft = session.displayName
                        renameError = null
                        renamePromptSession = session
                    },
                    onDissolve = { dissolvePromptSession = session },
                    onEnd = { endPromptSession = session },
                )
            }
            items(visibleDissolvedSessions, key = { "dissolved:${it.key}" }) { session ->
                DissolvedSessionCard(
                    session = session,
                    onRestore = { onRestoreDissolved(session) },
                    onForget = { onForgetDissolved(session) },
                )
            }
            if (visibleSessions.isEmpty() && visibleDissolvedSessions.isEmpty() && !state.refreshing) {
                item(key = "empty-sessions") {
                    Text(
                        when {
                            normalizedQuery.isNotBlank() -> "No sessions match “$normalizedQuery”"
                            state.sessionBucket == SessionBucket.ARCHIVE -> "No archived sessions"
                            state.sessionBucket == SessionBucket.WAITING -> "No waiting sessions"
                            else -> "This split is clear"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(splitScrollState).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                displayedWorkspaceSplitIds.forEach { id ->
                    val name = id?.let { workspaceId ->
                        state.workspaces.firstOrNull { it.id == workspaceId }?.name
                    } ?: "All"
                    if (state.selectedWorkspaceId == id) {
                        FilledTonalButton(onClick = { onSelectWorkspace(id) }) { Text(name) }
                    } else {
                        TextButton(onClick = { onSelectWorkspace(id) }) { Text(name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderDialog(
    session: TmuxSession,
    onDismiss: () -> Unit,
    onRemind: (Long) -> Unit,
) {
    val now = remember(session.hostId, session.name) { ZonedDateTime.now() }
    var customTime by remember(session.hostId, session.name) { mutableStateOf("") }
    var customError by remember(session.hostId, session.name) { mutableStateOf<String?>(null) }
    val later = remember(now) { ReminderTimeParser.laterToday(now) }
    val presets = remember(now) {
        listOf(
            "Later today · ${ReminderTimeParser.format(later)}" to later,
            "In 30 minutes" to now.plusMinutes(30),
            "In 1 hour" to now.plusHours(1),
            "In 2 hours" to now.plusHours(2),
            "In 5 hours" to now.plusHours(5),
            "Tomorrow · 9:00 AM" to ReminderTimeParser.tomorrowMorning(now),
            "In 2 days · 9:00 AM" to ReminderTimeParser.inDaysMorning(2, now),
        )
    }
    val submitCustom = {
        val parsed = ReminderTimeParser.parse(customTime, ZonedDateTime.now())
        if (parsed == null) {
            customError = "Try “in 30 minutes”, “tomorrow at 3pm”, or “Friday 9:30”."
        } else {
            onRemind(parsed.toEpochSecond())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    session.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                presets.forEach { (label, dueAt) ->
                    TextButton(
                        onClick = { onRemind(dueAt.toEpochSecond()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                OutlinedTextField(
                    value = customTime,
                    onValueChange = {
                        customTime = it
                        customError = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    label = { Text("Custom time") },
                    placeholder = { Text("tomorrow at 3pm") },
                    supportingText = customError?.let { message -> { Text(message) } },
                    isError = customError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitCustom() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submitCustom, enabled = customTime.isNotBlank()) {
                Text("Set reminder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AgentIcon(
    agent: AgentKind,
    modifier: Modifier = Modifier.size(20.dp),
) {
    val glyph = when (agent) {
        AgentKind.CLAUDE -> "✳"
        AgentKind.CODEX -> "⬡"
        AgentKind.PI -> "π"
        AgentKind.KIMI -> "☾"
        AgentKind.OTHER -> "›"
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            glyph,
            color = agentAccent(agent),
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun agentAccent(agent: AgentKind): Color = when (agent) {
    AgentKind.CLAUDE -> Color(0xFFD97757)
    AgentKind.CODEX -> Color(0xFF10A37F)
    AgentKind.PI -> Color(0xFFA78BFA)
    AgentKind.KIMI -> Color(0xFF4C8DFF)
    AgentKind.OTHER -> Color(0xFFAFB8C8)
}

@Composable
private fun AgentBadge(agent: AgentKind) {
    Row(
        Modifier
            .background(
                agentAccent(agent).copy(alpha = 0.16f),
                RoundedCornerShape(percent = 50),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AgentIcon(agent, Modifier.size(18.dp))
        Text(
            agent.label,
            modifier = Modifier.padding(end = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = agentAccent(agent),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SessionCard(
    session: TmuxSession,
    workspaces: List<Workspace>,
    unread: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onOpenTerminal: () -> Unit,
    onArchive: () -> Unit,
    onRemind: () -> Unit,
    onArchiveToggle: () -> Unit,
    onMarkUnread: () -> Unit,
    onMove: (Workspace) -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onEnd: () -> Unit,
) {
    var menuExpanded by remember(session.hostId, session.name) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.settledValue, archived) {
        if (!archived) {
            when (dismissState.settledValue) {
                SwipeToDismissBoxValue.EndToStart -> onArchive()
                SwipeToDismissBoxValue.StartToEnd -> onRemind()
                SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
            }
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !archived,
        enableDismissFromEndToStart = !archived,
        backgroundContent = {
            val reminding = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = if (reminding) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (reminding) Icons.Default.Schedule else Icons.Default.Archive,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (reminding) "Remind me" else "Archive",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 12.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (unread) MaterialTheme.colorScheme.primary else Color.Transparent,
                            CircleShape,
                        ),
                )
                AgentIcon(session.agent)
                Text(
                    session.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Session actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (archived) "Return to open" else "Waiting") },
                            onClick = {
                                menuExpanded = false
                                onArchiveToggle()
                            },
                        )
                        if (!archived) {
                            DropdownMenuItem(
                                text = { Text("Remind me") },
                                onClick = {
                                    menuExpanded = false
                                    onRemind()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("End session", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onEnd()
                            },
                        )
                        if (!unread && !archived) {
                            DropdownMenuItem(
                                text = { Text("Mark unread") },
                                onClick = {
                                    menuExpanded = false
                                    onMarkUnread()
                                },
                            )
                        }
                        if (!archived && session.agent != dev.multiprompt.companion.model.AgentKind.OTHER) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                },
                            )
                        }
                        if (!archived) {
                            workspaces.filter { it.hostId == session.hostId }.forEach { workspace ->
                                DropdownMenuItem(
                                    text = { Text("Move to ${workspace.name}") },
                                    onClick = {
                                        menuExpanded = false
                                        onMove(workspace)
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Pure terminal output") },
                            onClick = {
                                menuExpanded = false
                                onOpenTerminal()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Archive session", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDissolve()
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun DissolvedSessionCard(
    session: DissolvedSession,
    onRestore: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentIcon(session.agent)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(session.agent.label, session.workspaceName.takeIf(String::isNotBlank), "Archived")
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                if (session.resumeCommand.isBlank()) {
                    "Resume command unavailable for this agent"
                } else {
                    "Ready to resume with ${session.resumeCommand}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onForget) { Text("Forget") }
                TextButton(onClick = onRestore, enabled = session.resumeCommand.isNotBlank()) { Text("Resume") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    connection: SessionReaderConnection,
    dictation: DeepgramDictation,
    screencast: ScreencastUploader,
    session: TmuxSession,
    unread: Boolean,
    archived: Boolean,
    initialFontScale: Float,
    onBack: () -> Unit,
    onMarkRead: () -> Unit,
    onArchiveToggle: () -> Unit,
    onRemind: (Long?) -> Unit,
    onOpenTerminal: () -> Unit,
    onSwitchSession: (Int) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onRename: (String) -> String?,
    onDissolve: () -> Unit,
    onEnd: () -> Unit,
    onSessionInteraction: () -> Unit,
    technicalMode: Boolean,
    onTechnicalModeChanged: (Boolean) -> Unit,
    onResetFontScale: () -> Float,
) {
    val reader by connection.state.collectAsState()
    val dictationState by dictation.state.collectAsState()
    var promptField by remember(connection) { mutableStateOf(TextFieldValue("")) }
    val prompt = promptField.text
    val setPrompt: (String) -> Unit = { value ->
        promptField = TextFieldValue(value, selection = TextRange(value.length))
    }
    var dictationPrefix by remember(connection) { mutableStateOf("") }
    fun appendCurrentDictation(transcript: String): String {
        if (dictationPrefix.isBlank() && prompt.isNotBlank()) {
            // Recover safely if a state update raced the microphone button. Never let a
            // transcript replace an existing draft, including a pasted image URL.
            dictationPrefix = prompt.trimEnd()
        }
        return PromptComposer.appendDictation(dictationPrefix, transcript)
    }
    var pendingPromptAction by remember(connection) { mutableStateOf<Long?>(null) }
    var menuExpanded by remember(connection) { mutableStateOf(false) }
    var modelMenuExpanded by remember(connection) { mutableStateOf(false) }
    var apiKeyDialogVisible by remember(connection) { mutableStateOf(false) }
    var apiKeyDraft by remember(connection) { mutableStateOf("") }
    var apiKeyError by remember(connection) { mutableStateOf<String?>(null) }
    var microphoneError by remember(connection) { mutableStateOf<String?>(null) }
    var sendAfterDictation by remember(connection) { mutableStateOf(false) }
    var imageKeyDialogVisible by remember(connection) { mutableStateOf(false) }
    var imageKeyDraft by remember(connection) { mutableStateOf("") }
    var imageKeyError by remember(connection) { mutableStateOf<String?>(null) }
    var imageUploading by remember(connection) { mutableStateOf(false) }
    var imageUploadError by remember(connection) { mutableStateOf<String?>(null) }
    var imageAttachments by remember(connection) { mutableStateOf(emptyList<String>()) }
    var dissolveDialogVisible by remember(connection) { mutableStateOf(false) }
    var endDialogVisible by remember(connection) { mutableStateOf(false) }
    var renameDialogVisible by remember(connection) { mutableStateOf(false) }
    var reminderDialogVisible by remember(connection) { mutableStateOf(false) }
    var renameDraft by remember(connection) { mutableStateOf(session.displayName) }
    var renameError by remember(connection) { mutableStateOf<String?>(null) }
    var transcriptZoom by remember(connection, session.hostId, session.name) {
        mutableFloatStateOf(initialFontScale)
    }
    val runtimeDetails = remember(session.preview) { TmuxText.runtimeDetails(session.preview) }
    val transcriptTransform = rememberTransformableState { _, zoomChange, _, _ ->
        val nextScale = (transcriptZoom * zoomChange).coerceIn(0.75f, 5f)
        transcriptZoom = nextScale
        onFontScaleChanged(nextScale)
    }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var previousScrollMax by remember(connection) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val readerScope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_IMAGE_SELECTION),
    ) { uris ->
        if (uris.isNotEmpty()) {
            imageUploading = true
            imageUploadError = null
            readerScope.launch {
                val uploadedUrls = buildList {
                    uris.forEach { uri ->
                        runCatching { screencast.upload(uri) }
                            .onSuccess(::add)
                            .onFailure { imageUploadError = it.message ?: "Image upload failed" }
                    }
                }
                if (uploadedUrls.isNotEmpty()) {
                    imageAttachments = imageAttachments + uploadedUrls
                }
                imageUploading = false
            }
        }
    }
    val dictationActive = dictationState.status == DictationStatus.CONNECTING ||
        dictationState.status == DictationStatus.LISTENING ||
        dictationState.status == DictationStatus.FINISHING
    val readerView = LocalView.current
    DisposableEffect(readerView, dictationActive) {
        val keepScreenOnBeforeDictation = readerView.keepScreenOn
        if (dictationActive) readerView.keepScreenOn = true
        onDispose {
            if (dictationActive) readerView.keepScreenOn = keepScreenOnBeforeDictation
        }
    }
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

    LaunchedEffect(connection, scrollState, density) {
        val bottomThresholdPx = with(density) { 56.dp.roundToPx() }
        snapshotFlow { scrollState.maxValue }.collect { newMaximum ->
            // Compare against the previous layout height. This keeps the transcript pinned
            // while output arrives or the keyboard resizes the viewport, but preserves a
            // deliberate scroll into history.
            val wasAtBottom = previousScrollMax - scrollState.value <= bottomThresholdPx
            previousScrollMax = newMaximum
            if (wasAtBottom) scrollState.scrollTo(newMaximum)
        }
    }
    LaunchedEffect(reader.completedActions) {
        val pending = pendingPromptAction
        if (pending != null && reader.completedActions > pending) {
            setPrompt("")
            imageAttachments = emptyList()
            pendingPromptAction = null
        }
    }
    LaunchedEffect(reader.actionError) {
        if (reader.actionError != null) pendingPromptAction = null
    }
    LaunchedEffect(dictationState.transcript) {
        val spoken = dictationState.transcript.trim()
        if (spoken.isNotBlank()) {
            // Deepgram emits cumulative interim/final transcripts. Rebuild from the
            // original draft instead of appending the cumulative text on every update.
            setPrompt(PromptComposer.appendDictation(dictationPrefix, spoken))
        }
    }
    DisposableEffect(connection) {
        // Dictation belongs to this chat. Cancelling and clearing it prevents a late Deepgram
        // result from becoming the draft of the next chat.
        onDispose { dictation.discard() }
    }
    val sendCurrentPrompt = {
        val composedPrompt = PromptComposer.composeMessage(prompt, imageAttachments)
        if (composedPrompt.isNotBlank() &&
            !reader.sending &&
            pendingPromptAction == null
        ) {
            val actionCount = reader.completedActions
            if (connection.sendPrompt(composedPrompt)) {
                onSessionInteraction()
                pendingPromptAction = actionCount
            }
        }
    }
    val submitPrompt = {
        if (!reader.sending && pendingPromptAction == null) {
            if (dictationActive) {
                sendAfterDictation = true
                dictation.stop()
            } else {
                sendCurrentPrompt()
            }
        }
    }
    LaunchedEffect(dictationState.status, sendAfterDictation) {
        if (!sendAfterDictation) return@LaunchedEffect
        when (dictationState.status) {
            DictationStatus.IDLE -> {
                val finalPrompt = appendCurrentDictation(dictationState.transcript)
                setPrompt(finalPrompt)
                sendAfterDictation = false
                val composedPrompt = PromptComposer.composeMessage(finalPrompt, imageAttachments)
                if (composedPrompt.isNotBlank() &&
                    !reader.sending &&
                    pendingPromptAction == null
                ) {
                    val actionCount = reader.completedActions
                    if (connection.sendPrompt(composedPrompt)) {
                        onSessionInteraction()
                        pendingPromptAction = actionCount
                    }
                }
            }
            DictationStatus.FAILED -> sendAfterDictation = false
            else -> Unit
        }
    }
    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                renameDialogVisible = false
                renameError = null
            },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = {
                        renameDraft = it
                        renameError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    supportingText = renameError?.let { message -> { Text(message) } },
                    isError = renameError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        renameError = onRename(renameDraft)
                        if (renameError == null) renameDialogVisible = false
                    }),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameError = onRename(renameDraft)
                    if (renameError == null) renameDialogVisible = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameDialogVisible = false
                    renameError = null
                }) { Text("Cancel") }
            },
        )
    }
    if (dissolveDialogVisible) {
        AlertDialog(
            onDismissRequest = { dissolveDialogVisible = false },
            title = { Text("Archive ${session.displayName}?") },
            text = { Text("This ends its tmux session but saves the name and resume command so it can be restored later.") },
            confirmButton = {
                Button(onClick = {
                    dissolveDialogVisible = false
                    onDissolve()
                }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { dissolveDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
    if (endDialogVisible) {
        AlertDialog(
            onDismissRequest = { endDialogVisible = false },
            title = { Text("End ${session.displayName} completely?") },
            text = { Text("This kills the coding agent and tmux session without saving a restore record.") },
            confirmButton = {
                Button(onClick = {
                    endDialogVisible = false
                    onEnd()
                }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { endDialogVisible = false }) { Text("Cancel") } },
        )
    }
    if (reminderDialogVisible) {
        ReminderDialog(
            session = session,
            onDismiss = { reminderDialogVisible = false },
            onRemind = { dueAt ->
                reminderDialogVisible = false
                onRemind(dueAt)
            },
        )
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
    if (imageKeyDialogVisible) {
        AlertDialog(
            onDismissRequest = { imageKeyDialogVisible = false },
            title = { Text("Screencast2 uploads") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The upload key is encrypted with Android Keystore and stays on this device.")
                    OutlinedTextField(
                        value = imageKeyDraft,
                        onValueChange = {
                            imageKeyDraft = it
                            imageKeyError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Screencast2 upload key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = imageKeyError != null,
                        supportingText = imageKeyError?.let { message -> { Text(message) } },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = imageKeyDraft.isNotBlank(),
                    onClick = {
                        if (screencast.saveSecret(imageKeyDraft)) {
                            imageKeyDialogVisible = false
                            imageKeyDraft = ""
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        } else {
                            imageKeyError = "Enter a valid upload key"
                        }
                    },
                ) { Text("Save and choose image") }
            },
            dismissButton = {
                TextButton(onClick = { imageKeyDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.horizontalSwipe(onSwitchSession),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentIcon(session.agent, Modifier.size(22.dp))
                        Box(Modifier.weight(1f)) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { modelMenuExpanded = true },
                            ) {
                                Text(
                                    session.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    runtimeDetails.label ?: "Model / effort",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open model picker (/model)") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        setPrompt("/model")
                                    },
                                )
                                if (session.agent == AgentKind.CLAUDE) {
                                    DropdownMenuItem(
                                        text = { Text("Use Claude Opus") },
                                        onClick = {
                                            modelMenuExpanded = false
                                            setPrompt("/model opus")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Use Claude Sonnet") },
                                        onClick = {
                                            modelMenuExpanded = false
                                            setPrompt("/model sonnet")
                                        },
                                    )
                                    listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text("Set effort: $level") },
                                            onClick = {
                                                modelMenuExpanded = false
                                                setPrompt("/effort $level")
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
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
                                    onSessionInteraction()
                                    connection.sendEnter()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Interrupt session") },
                                enabled = !reader.sending,
                                onClick = {
                                    menuExpanded = false
                                    onSessionInteraction()
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
                            if (session.agent != dev.multiprompt.companion.model.AgentKind.OTHER) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuExpanded = false
                                        renameDraft = session.displayName
                                        renameError = null
                                        renameDialogVisible = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (archived) "Return to open" else "Waiting and open next") },
                                onClick = {
                                    menuExpanded = false
                                    onArchiveToggle()
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
                                text = { Text(if (technicalMode) "Hide terminal details" else "Show terminal details") },
                                onClick = {
                                    menuExpanded = false
                                    onTechnicalModeChanged(!technicalMode)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Use default font size") },
                                onClick = {
                                    menuExpanded = false
                                    transcriptZoom = onResetFontScale()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Archive session", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    dissolveDialogVisible = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("End session", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    endDialogVisible = true
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
                            DropdownMenuItem(
                                text = { Text("Screencast2 upload key") },
                                onClick = {
                                    menuExpanded = false
                                    imageKeyDraft = ""
                                    imageKeyError = null
                                    imageKeyDialogVisible = true
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDissolve,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Archive, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Archive", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { reminderDialogVisible = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Remind me", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onArchiveToggle,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(if (archived) "Open" else "Wait", maxLines = 1, softWrap = false)
                    }
                }
                if (imageAttachments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        imageAttachments.forEachIndexed { index, url ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ) {
                                Row(
                                    Modifier.padding(start = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Image, "Image attachment", Modifier.size(18.dp))
                                    Text(
                                        "Image ${index + 1}",
                                        modifier = Modifier.padding(horizontal = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                    IconButton(
                                        onClick = {
                                            imageAttachments = imageAttachments.filterNot { it == url }
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.Close, "Remove image ${index + 1}")
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = promptField,
                    onValueChange = { promptField = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message this session") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitPrompt() }),
                    readOnly = dictationActive,
                    trailingIcon = {
                        val sendEnabled = !reader.sending &&
                            pendingPromptAction == null &&
                            !sendAfterDictation &&
                            (prompt.isNotBlank() || imageAttachments.isNotEmpty() || dictationActive)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (screencast.configured) {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    } else {
                                        imageKeyDialogVisible = true
                                    }
                                },
                                enabled = !reader.sending && !dictationActive && !imageUploading,
                            ) {
                                if (imageUploading) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Image, "Upload image")
                                }
                            }
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
                                if (reader.sending || sendAfterDictation) {
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
                (microphoneError ?: dictationState.error ?: imageUploadError)?.let { message ->
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
                Row(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                    Text(
                        "Disconnected · retrying",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            val displayedOutput = reader.output.ifBlank {
                if (failure == null && reader.status != ReaderStatus.Connecting) {
                    "No recent output"
                } else {
                    ""
                }
            }
            val waitingForInput = TmuxText.isWaitingForInput(displayedOutput)
            val connectionLabel = when {
                failure != null -> "Disconnected · retrying"
                reader.status == ReaderStatus.Connecting -> "Connecting"
                reader.sending -> "Live · Sending"
                reader.output.isBlank() -> "Live"
                waitingForInput -> "Live · Ready"
                else -> "Live · Working"
            }
            val connectionColor = when {
                failure != null -> MaterialTheme.colorScheme.errorContainer
                reader.status == ReaderStatus.Connecting -> MaterialTheme.colorScheme.surfaceVariant
                waitingForInput -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val connectionTextColor = when {
                failure != null -> MaterialTheme.colorScheme.onErrorContainer
                reader.status == ReaderStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
                waitingForInput -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            val readerBlocks = remember(displayedOutput, session.agent) {
                TmuxText.readerBlocks(displayedOutput, session.agent)
            }
            val working = reader.sending || readerBlocks.any { block ->
                block.kind == TmuxText.ReaderBlockKind.PROGRESS && isTransientProgress(block.text)
            }
            val visibleReaderBlocks = readerBlocks.filterNot { block ->
                !technicalMode && block.kind == TmuxText.ReaderBlockKind.PROGRESS &&
                    isTransientProgress(block.text)
            }
            var expandedReaderBlocks by remember(connection) { mutableStateOf(emptySet<String>()) }
            val transcriptFontSize = (14f * transcriptZoom).sp
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .transformable(state = transcriptTransform, canPan = { false }),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentBadge(session.agent)
                    Text(
                        if (technicalMode) "Terminal detail" else "Clean chat",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier
                            .background(connectionColor, RoundedCornerShape(percent = 50))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (reader.status == ReaderStatus.Connecting || reader.sending) {
                            CircularProgressIndicator(
                                Modifier.size(11.dp),
                                strokeWidth = 1.5.dp,
                                color = connectionTextColor,
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(connectionTextColor, CircleShape),
                            )
                        }
                        Text(
                            connectionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = connectionTextColor,
                            maxLines = 1,
                        )
                    }
                }
                if (working) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Working…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (visibleReaderBlocks.isEmpty()) {
                    Text(
                        "No recent output",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = transcriptFontSize,
                    )
                }
                visibleReaderBlocks.forEachIndexed { index, block ->
                    val blockKey = readerBlockKey(block)
                    when (block.kind) {
                        TmuxText.ReaderBlockKind.PROSE -> Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            SelectionContainer {
                                Text(
                                    terminalLinks(block.text),
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    fontFamily = FontFamily.Default,
                                    fontSize = transcriptFontSize,
                                    lineHeight = (transcriptFontSize.value * 1.5f).sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        TmuxText.ReaderBlockKind.USER_PROMPT -> Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Text(
                                terminalLinks(block.text),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                fontFamily = FontFamily.Default,
                                fontSize = transcriptFontSize,
                                lineHeight = (transcriptFontSize.value * 1.45f).sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        TmuxText.ReaderBlockKind.CODE,
                        TmuxText.ReaderBlockKind.PROGRESS -> {
                            ReaderCollapsibleBlock(
                                block = block,
                                expanded = blockKey in expandedReaderBlocks,
                                fontScale = transcriptZoom,
                                onToggle = {
                                    expandedReaderBlocks = if (blockKey in expandedReaderBlocks) {
                                        expandedReaderBlocks - blockKey
                                    } else {
                                        expandedReaderBlocks + blockKey
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderCollapsibleBlock(
    block: TmuxText.ReaderBlock,
    expanded: Boolean,
    fontScale: Float,
    onToggle: () -> Unit,
) {
    val isCode = block.kind == TmuxText.ReaderBlockKind.CODE
    val lineCount = block.text.lineSequence().count()
    val preview = block.text.lineSequence().firstOrNull().orEmpty().trim()
    val metadata = listOfNotNull(
        if (isCode) "Code" else "Activity",
        block.language,
        block.filePath,
        "$lineCount lines",
    ).joinToString(" · ")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isCode) {
                                if (expanded) "Hide code" else "Show code"
                            } else {
                                if (expanded) "Hide activity" else "Show activity"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "$metadata · $preview",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (expanded) "▲" else "▼")
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SelectionContainer {
                    if (isCode) {
                        Text(
                            syntaxHighlightCode(block),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = ReaderFontFamily,
                            fontSize = (10f * fontScale).sp,
                            lineHeight = (1.35f * 10f * fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            terminalLinks(block.text),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = FontFamily.Default,
                            fontSize = (12f * fontScale).sp,
                            lineHeight = (1.45f * 12f * fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun isTransientProgress(value: String): Boolean = value.lineSequence().all { line ->
    val trimmed = line.trim()
    trimmed.isBlank() || listOf(
        "working", "running", "thinking", "reading", "searching", "esc to cancel",
    ).any { trimmed.startsWith(it, ignoreCase = true) }
}

private fun readerBlockKey(block: TmuxText.ReaderBlock): String = listOf(
    block.kind.name,
    block.language.orEmpty(),
    block.filePath.orEmpty(),
    block.text,
).joinToString("\u0000")

private const val MAX_IMAGE_SELECTION = 8

private fun syntaxHighlightCode(block: TmuxText.ReaderBlock): AnnotatedString = buildAnnotatedString {
    block.text.lineSequence().forEachIndexed { index, line ->
        val color = when {
            block.language == "diff" && line.startsWith("+") -> Color(0xFF86EFAC)
            block.language == "diff" && line.startsWith("-") -> Color(0xFFFCA5A5)
            block.language == "diff" && line.startsWith("@@") -> Color(0xFF93C5FD)
            else -> null
        }
        if (color != null) {
            withStyle(SpanStyle(color = color)) { append(line) }
        } else {
            appendHighlightedCodeLine(line)
        }
        if (index < block.text.lineSequence().count() - 1) append('\n')
    }
}

private fun AnnotatedString.Builder.appendHighlightedCodeLine(line: String) {
    val tokenPattern = Regex("\\b(fun|class|interface|object|val|var|const|return|if|else|for|while|import|package|true|false|null)\\b")
    var cursor = 0
    tokenPattern.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = Color(0xFF93C5FD), fontWeight = FontWeight.SemiBold)) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
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
    BackHandler(onBack = onCancel)
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
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular) ?: Typeface.MONOSPACE
    }
    // The desktop owns the window width, so the phone shrinks its glyphs until that width
    // fits instead of resizing anything. Measure the real monospace advance: a guessed ratio
    // left the emulator a few columns narrower than tmux believed, so every full-width line
    // wrapped and redraws of the status line stacked into ghost copies.
    val textMeasurer = rememberTextMeasurer()
    val fontSize = remember(screenWidthDp, columns) {
        val target = if (columns > 0) columns else FALLBACK_COLUMNS
        val pxPerCharAt100Sp = textMeasurer.measure(
            AnnotatedString("0".repeat(20)),
            TextStyle(fontFamily = ReaderFontFamily, fontSize = 100.sp),
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
        Box(Modifier.padding(padding).fillMaxSize().background(TerminalBackground).clipToBounds()) {
            Terminal(
                terminalEmulator = connection.emulator,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -keyboardHeightPx) }
                    .horizontalSwipe(onSwitchSession),
                initialFontSize = fontSize,
                typeface = terminalTypeface,
                backgroundColor = TerminalBackground,
                foregroundColor = TerminalForeground,
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

@Composable
private fun CachedSessionsNotice(
    hostLabels: List<String>,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Showing cached sessions", fontWeight = FontWeight.SemiBold)
                val hosts = hostLabels.joinToString(", ").ifBlank { "your VPS" }
                val connectionMessage = when {
                    hostLabels.size == 1 -> "$hosts is unreachable."
                    hostLabels.size > 1 -> "$hosts are unreachable."
                    else -> "Your VPS is unreachable."
                }
                Text(
                    "$connectionMessage Last known sessions are shown until the connection returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetry, enabled = retryEnabled) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry connection")
                Spacer(Modifier.size(4.dp))
                Text("Retry")
            }
        }
    }
}

private fun tomorrowMorningEpochSeconds(): Long = ZonedDateTime.now()
    .plusDays(1)
    .withHour(8)
    .withMinute(0)
    .withSecond(0)
    .withNano(0)
    .toEpochSecond()

/**
 * Detects a sideways flick before the terminal sees it. The terminal consumes touches for
 * scrolling and selection, so this watches the initial pass and only claims the gesture once
 * the drag is clearly horizontal, leaving vertical scrolling untouched.
 */
private fun Modifier.horizontalSwipe(
    onSwipe: (Int) -> Unit,
    eventPass: PointerEventPass = PointerEventPass.Initial,
) = pointerInput(eventPass) {
    val threshold = 72.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent(eventPass).changes.firstOrNull { it.pressed }
                ?: continue
            var totalX = 0f
            var totalY = 0f
            var lastPosition = first.position
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(eventPass)
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                if (!change.pressed) break
                val positionDelta = change.position - lastPosition
                lastPosition = change.position
                totalX += positionDelta.x
                totalY += positionDelta.y
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

private fun terminalLinks(value: String): AnnotatedString {
    val builder = AnnotatedString.Builder(value)
    value.lineSequence().fold(0) { offset, line ->
        val trimmed = line.trimStart()
        val color = when {
            TERMINAL_ERROR.containsMatchIn(trimmed) -> TerminalRed
            TERMINAL_WARNING.containsMatchIn(trimmed) -> TerminalYellow
            TERMINAL_SUCCESS.containsMatchIn(trimmed) -> TerminalGreen
            TERMINAL_PROMPT.containsMatchIn(trimmed) -> TerminalBlue
            else -> null
        }
        if (color != null && line.isNotEmpty()) {
            builder.addStyle(SpanStyle(color = color), offset, offset + line.length)
        }
        offset + line.length + 1
    }
    TERMINAL_URL.findAll(value).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
        if (url.isNotEmpty()) {
            builder.addLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(
                        style = SpanStyle(
                            color = TerminalBlue,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
                match.range.first,
                match.range.first + url.length,
            )
        }
    }
    return builder.toAnnotatedString()
}

private val TERMINAL_URL = Regex("https?://[^\\s<>()\\[\\]{}]+")
private val TERMINAL_ERROR = Regex("(?i)(^|\\b)(error|failed|failure|fatal|exception|denied)(\\b|:)")
private val TERMINAL_WARNING = Regex("(?i)(^|\\b)(warning|warn)(\\b|:)")
private val TERMINAL_SUCCESS = Regex("(?i)(^|\\b)(success|successful|passed|complete|completed)(\\b|:)")
private val TERMINAL_PROMPT = Regex("^[>$❯›#]")
