package dev.multiprompt.companion.ui

import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Image as OutlinedImage
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import android.text.format.DateUtils
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.multiprompt.companion.reader.TranscriptProtocol
import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
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
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.account.AccountHostMap
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import dev.multiprompt.companion.account.frequentSessions
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.isFavoriteSession
import dev.multiprompt.companion.account.isHiddenAsMuted
import dev.multiprompt.companion.account.isMutedSession
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.SessionBucket
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.NewSessionMode
import dev.multiprompt.companion.NewSessionPicker
import dev.multiprompt.companion.NewSessionStep
import dev.multiprompt.companion.R
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.AgentHarness
import dev.multiprompt.companion.model.DirectoryRanking
import dev.multiprompt.companion.model.SessionFinder
import dev.multiprompt.companion.model.HostDraft
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.DissolvedSession
import dev.multiprompt.companion.data.CrashReport
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.dictation.DictationState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.skills.NativeSkill
import dev.multiprompt.companion.skills.NativeSkills
import dev.multiprompt.companion.data.ReminderTimeParser
import dev.multiprompt.companion.data.UnreadCount
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.SessionSearch
import dev.multiprompt.companion.ssh.HostKeyWarning
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.terminal.TerminalStatus
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import dev.multiprompt.companion.upload.ScreencastUploader
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import dev.multiprompt.companion.terminal.TerminalInputTracker

@Composable
fun MultipromptApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updates.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updates.check(force = true)
            delay(UPDATE_POLL_INTERVAL_MS)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AppScreens(viewModel)
        if (state.signedIn) state.pendingDevice?.let { device ->
            DeviceApprovalPrompt(
                device = device,
                busy = state.deviceApprovalBusy,
                error = state.deviceApprovalError,
                onApprove = viewModel::approveDevice,
                onDeny = viewModel::denyDevice,
            )
        }
        UpdateBanner(
            state = updateState,
            onInstall = { release -> viewModel.updates.install(release) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun UpdateBanner(
    state: UpdateState,
    onInstall: (UpdateRelease) -> Unit,
    modifier: Modifier = Modifier,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.PermissionRequired -> state.release
        is UpdateState.Downloading -> state.release
        else -> null
    } ?: return
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (state) {
                    is UpdateState.Downloading -> "Downloading v${release.versionName}… " +
                        "${(state.progress * 100).toInt()}%"
                    is UpdateState.PermissionRequired -> "Allow installs to update to v${release.versionName}"
                    else -> "Version ${release.versionName} available"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state is UpdateState.Available) {
                Button(
                    onClick = { onInstall(release) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun AppScreens(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updates.state.collectAsState()
    val context = LocalContext.current
    var useWithoutAccount by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val sunlight = when (state.appTheme) {
        AppTheme.SYSTEM -> !systemDark
        AppTheme.DARK -> false
        AppTheme.SUNLIGHT -> true
    }
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

    if (!state.signedIn && !(BuildConfig.DEBUG && useWithoutAccount)) {
        SignInScreen(
            error = state.accountError,
            onSignIn = { viewModel.signInAccount { context.startActivity(it) } },
            onUseWithoutAccount = if (BuildConfig.DEBUG) ({ useWithoutAccount = true }) else null,
            onReleaseNotes = viewModel::openReleaseNotes,
        )
        return
    }

    BackHandler(
        enabled = state.terminal == null &&
            state.reader == null &&
            state.section != AppSection.SESSIONS,
        onBack = {
            viewModel.select(AppSection.SESSIONS)
        },
    )

    val terminal = state.terminal
    if (terminal != null) {
        TerminalScreen(
            connection = terminal,
            title = state.terminalSession?.displayName ?: terminal.tmuxSessionName,
            columns = state.terminalSession?.columns ?: 0,
            rows = state.terminalSession?.rows ?: 0,
            savedFontSize = state.shellFontSize,
            onFontSizeChange = viewModel::setShellFontSize,
            onBack = viewModel::closeTerminal,
            onReleaseNotes = viewModel::openReleaseNotes,
            onSessionInput = { state.terminalSession?.let(viewModel::stampSessionInput) },
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

            signedIn = state.signedIn,
            onSignIn = { viewModel.signInAccount { context.startActivity(it) } },
            onSignOut = viewModel::signOutAccount,
            session = readerSession,
            unread = SessionReadStore.key(readerSession.hostId, readerSession.name) in state.unreadSessionKeys,
            archived = SessionReadStore.key(readerSession.hostId, readerSession.name) in state.archivedSessionKeys,
            initialFontScale = viewModel.readerFontScale(readerSession),
            onBack = viewModel::closeReader,
            onReleaseNotes = viewModel::openReleaseNotes,
            onMarkRead = viewModel::markReaderRead,
            unreadMessageKey = state.readerUnreadMessageKey,
            onMarkUnreadFrom = viewModel::markReaderUnreadFrom,
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
            onOpenTerminal = { viewModel.openTerminal(readerSession, userInitiated = true) },
            onFontScaleChanged = { scale -> viewModel.saveReaderFontScale(readerSession, scale) },
            onRename = { name -> viewModel.renameSession(readerSession, name) },
            onDissolve = { viewModel.dissolveSession(readerSession) },
            onEnd = { viewModel.endSession(readerSession) },
            onSessionInteraction = { viewModel.noteSessionInteraction(readerSession) },
            onSessionInput = { viewModel.stampSessionInput(readerSession) },
            technicalMode = state.readerTechnicalMode,
            onTechnicalModeChanged = viewModel::setReaderTechnicalMode,
            sunlightMode = sunlight,
            onSetAppTheme = viewModel::setAppTheme,
            onResetFontScale = { viewModel.resetReaderFontScale(readerSession) },
            sessionActionError = state.sessionActionError,
            onClearSessionActionError = viewModel::clearSessionActionError,
            onForget = { viewModel.forgetSession(readerSession) },
            onShowEtaAccuracy = { viewModel.showEtaAccuracy(readerSession) },
            material3 = BuildConfig.MATERIAL3,
        )
        return
    }

    val newSession = state.newSession
    if (newSession != null && newSession.step == NewSessionStep.PATH) {
        NewSessionFolderScreen(
            picker = newSession,
            hosts = state.hosts,
            onBrowse = viewModel::browseNewSessionDirectory,
            onChoose = viewModel::chooseNewSessionFolder,
            onModeChange = viewModel::setNewSessionMode,
            onClose = viewModel::closeNewSession,
        )
        return
    }
    if (newSession != null && newSession.step == NewSessionStep.EXISTING) {
        ExistingSessionScreen(
            picker = newSession,
            hosts = state.hosts,
            archivedKeys = state.archivedSessionKeys,
            onModeChange = viewModel::setNewSessionMode,
            onNeedsAttentionChange = viewModel::setNewSessionNeedsAttentionOnly,
            onOpen = viewModel::openExistingSession,
            onClose = viewModel::closeNewSession,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = if (state.section == AppSection.SESSIONS)
            WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            Column(if (state.section == AppSection.SESSIONS && updateState !is UpdateState.Available) Modifier else Modifier.statusBarsPadding()) {
                if (state.section != AppSection.SESSIONS) {
                    Header(
                        refreshing = state.refreshing,
                        onRefresh = viewModel::refresh,
                        section = state.section,
                        onSelect = viewModel::select,
                        onReleaseNotes = viewModel::openReleaseNotes,
                    )
                }
                val available = updateState as? UpdateState.Available
                if (available != null) {
                    UpdateBanner(available.release) { viewModel.installUpdate(available.release) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)
            .then(if (state.section == AppSection.SESSIONS) Modifier.consumeWindowInsets(PaddingValues(top = padding.calculateTopPadding())) else Modifier)
            .fillMaxSize()) {
            when (state.section) {
                AppSection.SESSIONS -> SessionsScreen(
                    state = state,
                    signedIn = state.signedIn,
                    onSignIn = { viewModel.signInAccount { context.startActivity(it) } },
                    onSignOut = viewModel::signOutAccount,
                    onOpen = { viewModel.openReader(it, userInitiated = true) },
                    onOpenTerminal = { viewModel.openTerminal(it, userInitiated = true) },
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
                    onSetFavorite = { tab, value -> viewModel.editAccountLayout { it.setFavorite(tab.identity, value) } },
                    onRefresh = viewModel::refresh,
                    onSelectSection = viewModel::select,
                    onReleaseNotes = viewModel::openReleaseNotes,
                    onForgetSession = viewModel::forgetSession,
                    onStartNewSession = viewModel::startNewSession,
                    onSetNewestSessionsAtBottom = viewModel::setNewestSessionsAtBottom,
                    onSetAllSplitOnRight = viewModel::setAllSplitOnRight,
                    onSetSessionListSetting = viewModel::setSessionListSetting,
                    appTheme = state.appTheme,
                    onSetAppTheme = viewModel::setAppTheme,
                    onClearSessionActionError = viewModel::clearSessionActionError,
                    onSetReaderDefaultFontScale = viewModel::setReaderDefaultFontScale,
                    readerDefaultFontScale = state.readerDefaultFontScale,
                    crashReport = state.crashReport,
                    onClearCrashReport = viewModel::clearCrashReport,
                    material3 = BuildConfig.MATERIAL3,
                    onMapAccountHost = viewModel::mapAccountHost,
                )
                AppSection.UPDATE -> UpdateScreen(
                    state = updateState,
                    onCheck = { viewModel.updates.check(force = true) },
                    onInstall = viewModel::installUpdate,
                )
            }
        }
    }


    state.newSession?.let { picker ->
        when (picker.step) {
            NewSessionStep.HOST -> AlertDialog(
                onDismissRequest = viewModel::closeNewSession,
                title = { Text("New session · host") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.hosts.forEach { host ->
                            TextButton(
                                onClick = { viewModel.chooseNewSessionHost(host.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(host.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = viewModel::closeNewSession) { Text("Cancel") }
                },
            )

            NewSessionStep.PATH -> Unit

            NewSessionStep.EXISTING -> Unit

            NewSessionStep.HARNESS -> AlertDialog(
                onDismissRequest = viewModel::closeNewSession,
                title = { Text("Start in ${picker.chosenPath?.substringAfterLast('/').orEmpty()}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(picker.chosenPath.orEmpty(), style = MaterialTheme.typography.labelSmall)
                        state.sessionActionError?.let { message ->
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        AgentHarness.entries.forEach { harness ->
                            if (harness == AgentHarness.CLAUDE) {
                                Button(
                                    onClick = { viewModel.createNewSession(harness) },
                                    enabled = !state.creatingSession,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(harness.label) }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.createNewSession(harness) },
                                    enabled = !state.creatingSession,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(harness.label) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = viewModel::backToNewSessionFolder) { Text("Back") }
                },
            )
        }
    }

    state.etaAccuracy?.let { rows ->
        val accuracyError = state.etaAccuracyError
        val accuracyLoading = state.etaAccuracyLoading
        AlertDialog(
            onDismissRequest = viewModel::dismissEtaAccuracy,
            title = { Text("Estimate accuracy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        accuracyLoading -> Text("Reading the estimate log…")
                        accuracyError != null -> Text(
                            accuracyError,
                            color = MaterialTheme.colorScheme.error,
                        )
                        rows.isEmpty() -> Text(
                            "No finished estimates yet. A finished turn counts when it ran " +
                                "two minutes or more, and an agent needs three of them.",
                        )
                        else -> rows.forEach { row ->
                            Text(
                                "${row.agent}: tasks take " +
                                    String.format(java.util.Locale.US, "%.1f", row.ratio) +
                                    "x the estimate (last ${row.samples})",
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissEtaAccuracy) { Text("Close") }
            },
        )
    }

    state.pendingHostKeys.entries.firstOrNull()?.let { (hostId, key) ->
        val host = state.hosts.firstOrNull { it.id == hostId }
        val warning = HostKeyWarning.text(host, key)
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(warning.title)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(warning.body)
                    Text(
                        warning.server,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    warning.saved?.let { saved ->
                        Text(
                            saved,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        warning.presented,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.trustHostKey(hostId) }) {
                    Text(warning.confirm)
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

@Composable
private fun SignInScreen(
    error: String?,
    onSignIn: () -> Unit,
    onUseWithoutAccount: (() -> Unit)?,
    onReleaseNotes: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("multiprompt", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Sign in to see your servers, workspaces and sessions.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onSignIn) { Text("Sign in") }
                if (BuildConfig.DEBUG && onUseWithoutAccount != null) {
                    TextButton(onClick = onUseWithoutAccount) { Text("Use without account (debug)") }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
            VersionLabel(onClick = onReleaseNotes, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Header(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    onReleaseNotes: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(
                    when (section) {
                        AppSection.SESSIONS -> "Sessions"
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
            VersionLabel(onClick = onReleaseNotes)
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
private fun PickerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    placeholder: String,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
        )
        TextButton(onClick = onClose) { Text("Cancel") }
    }
}

@Composable
private fun PickerModeRow(mode: NewSessionMode, onModeChange: (NewSessionMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val newSelected = mode == NewSessionMode.NEW
        val newClick = { if (!newSelected) onModeChange(NewSessionMode.NEW) }
        if (newSelected) {
            Button(onClick = newClick, modifier = Modifier.weight(1f)) { Text("New session") }
        } else {
            OutlinedButton(onClick = newClick, modifier = Modifier.weight(1f)) { Text("New session") }
        }
        val existingSelected = mode == NewSessionMode.EXISTING
        val existingClick = { if (!existingSelected) onModeChange(NewSessionMode.EXISTING) }
        if (existingSelected) {
            Button(onClick = existingClick, modifier = Modifier.weight(1f)) { Text("Existing") }
        } else {
            OutlinedButton(onClick = existingClick, modifier = Modifier.weight(1f)) { Text("Existing") }
        }
    }
}

@Composable
private fun ExistingSessionScreen(
    picker: NewSessionPicker,
    hosts: List<HostProfile>,
    archivedKeys: Set<String>,
    onModeChange: (NewSessionMode) -> Unit,
    onNeedsAttentionChange: (Boolean) -> Unit,
    onOpen: (TmuxSession) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember(picker.hostId) { mutableStateOf("") }
    val hostLabel = hosts.firstOrNull { it.id == picker.hostId }?.label ?: "Host"
    val ranked = remember(picker.sessions, picker.openSessionKeys, picker.needsAttentionOnly, query) {
        SessionFinder.rank(
            sessions = picker.sessions,
            isOpen = { session ->
                SessionReadStore.key(session.hostId, session.name) in picker.openSessionKeys
            },
            needsAttentionOnly = picker.needsAttentionOnly,
            query = query,
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onClose)
    LaunchedEffect(picker.hostId) {
        focusRequester.requestFocus()
        delay(120)
        keyboard?.show()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PickerSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { ranked.firstOrNull()?.let(onOpen) },
            focusRequester = focusRequester,
            placeholder = "Find a session",
            onClose = onClose,
        )
        PickerModeRow(mode = picker.mode, onModeChange = onModeChange)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$hostLabel · ${picker.sessions.size} session${if (picker.sessions.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Needs attention", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = picker.needsAttentionOnly,
                onCheckedChange = onNeedsAttentionChange,
            )
        }
        if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        picker.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        if (ranked.isEmpty() && !picker.loading && picker.error == null) {
            Text(
                if (picker.needsAttentionOnly && picker.sessions.isNotEmpty()) {
                    "Nothing needs attention. Turn the switch off to see connected sessions."
                } else {
                    "No tmux sessions on this host."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(ranked, key = { SessionReadStore.key(it.hostId, it.name) }) { session ->
                val key = SessionReadStore.key(session.hostId, session.name)
                val openInApp = key in picker.openSessionKeys
                val archived = key in archivedKeys
                TextButton(
                    onClick = { onOpen(session) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AgentIcon(session.agent, Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                session.workingDirectory.ifBlank { session.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (session.attachedClients > 0) append("desktop attached")
                                    if (openInApp) {
                                        if (isNotEmpty()) append(" · ")
                                        append("open here")
                                    }
                                    if (archived) {
                                        if (isNotEmpty()) append(" · ")
                                        append("archived")
                                    }
                                    if (isEmpty()) append("detached")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSessionFolderScreen(
    picker: NewSessionPicker,
    hosts: List<HostProfile>,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    onModeChange: (NewSessionMode) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember(picker.hostId) { mutableStateOf("") }
    val ranked = remember(picker.directories, picker.usage, query) {
        DirectoryRanking.rank(picker.directories, picker.usage, query)
    }
    val hostLabel = hosts.firstOrNull { it.id == picker.hostId }?.label ?: "Host"
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onClose)
    LaunchedEffect(picker.hostId) {
        focusRequester.requestFocus()
        delay(120)
        keyboard?.show()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PickerSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSubmit = {
                ranked.firstOrNull { it.isRepo }?.let { entry ->
                    onChoose(joinRemotePath(picker.path, entry.name))
                }
            },
            focusRequester = focusRequester,
            placeholder = "Find a repo",
            onClose = onClose,
        )
        PickerModeRow(mode = picker.mode, onModeChange = onModeChange)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$hostLabel · ${picker.path.ifBlank { "…" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onChoose(picker.path) },
                enabled = picker.path.isNotBlank(),
            ) { Text("Use this folder") }
        }
        if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        picker.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (query.isBlank() && picker.path.isNotBlank() && picker.path != "/") {
                item {
                    TextButton(
                        onClick = { onBrowse(parentRemotePath(picker.path)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("..", Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
            if (query.startsWith("/")) {
                val typed = query.trim()
                if (typed.length > 1) {
                    item {
                        TextButton(
                            onClick = { onBrowse(typed) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Go to $typed", Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                }
            }
            items(ranked, key = { it.name }) { entry ->
                val full = joinRemotePath(picker.path, entry.name)
                TextButton(
                    onClick = { if (entry.isRepo) onChoose(full) else onBrowse(full) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(full)
                                if (entry.isRepo) append(" · git")
                                if (entry.uses > 0) append(" · used ${entry.uses}×")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private enum class SettingsPage { HIDDEN, PREFERENCES, MAPPING }

@Composable
internal fun SessionsScreen(
    state: AppUiState,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
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
    onCreateWorkspace: (String) -> String?,
    onMoveSession: (TmuxSession, Workspace) -> Unit,
    onRenameSession: (TmuxSession, String) -> String?,
    onSetFavorite: (AccountTab, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelectSection: (AppSection) -> Unit,
    onReleaseNotes: () -> Unit = { onSelectSection(AppSection.UPDATE) },
    onForgetSession: (TmuxSession) -> Unit,
    onStartNewSession: () -> Unit,
    onSetNewestSessionsAtBottom: (Boolean) -> Unit,
    onSetAllSplitOnRight: (Boolean) -> Unit,
    appTheme: AppTheme,
    onSetAppTheme: (AppTheme) -> Unit,
    onClearSessionActionError: () -> Unit,
    readerDefaultFontScale: Float,
    onSetReaderDefaultFontScale: (Float) -> Unit,
    crashReport: CrashReport?,
    onClearCrashReport: () -> Unit,
    onMapAccountHost: (Int, String?) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    onSetSessionListSetting: (String, Boolean) -> Unit = { _, _ -> },
    material3: Boolean = LocalMaterial3.current,
) = MultipromptTheme(
    sunlight = when (appTheme) {
        AppTheme.SYSTEM -> !isSystemInDarkTheme()
        AppTheme.DARK -> false
        AppTheme.SUNLIGHT -> true
    },
    material3 = material3,
    readerStyle = !material3,
) {
    val sunlight = appTheme == AppTheme.SUNLIGHT || (appTheme == AppTheme.SYSTEM && !isSystemInDarkTheme())
    var workspaceDialogVisible by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
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
    var bucketMenuExpanded by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf(SettingsPage.HIDDEN) }
    var crashReportVisible by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val splitScrollState = rememberScrollState()
    val hostLabels = state.hosts.associate { it.id to it.label }
    val displayedWorkspaceSplitIds = state.visibleWorkspaceSplitIds
    val arrangedWorkspaceSplitIds = displayedWorkspaceSplitIds.filterNot { it == FAVORITES_SPLIT_ID || it == FREQUENT_SPLIT_ID }
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
    if (settingsPage != SettingsPage.HIDDEN) {
        BackHandler { settingsPage = SettingsPage.HIDDEN }
        MultipromptTheme(sunlight = sunlight, material3 = false) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { settingsPage = SettingsPage.HIDDEN }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Text("Session list settings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        VersionLabel(onClick = {
                            settingsPage = SettingsPage.HIDDEN
                            onReleaseNotes()
                        })
                    }
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KitListGroup {
                        KitListRow(if (signedIn) "Switch / renew login" else "Sign in", onClick = onSignIn)
                        if (signedIn) KitListRow("Sign out", destructive = true, onClick = onSignOut)
                        KitListRow("Host mapping", onClick = { settingsPage = SettingsPage.MAPPING })
                        KitListRow("Update", onClick = { settingsPage = SettingsPage.HIDDEN; onSelectSection(AppSection.UPDATE) })
                    }
                    listOf(
                        Triple("frequent", "Show Frequent split", state.showFrequentSplit),
                        Triple("frequent_first", "Frequent as first tab", state.frequentFirst),
                        Triple("favorites", "Show Favorites split", state.showFavoritesSplit),
                        Triple("all", "Show All split", state.showAllSplit),
                    ).forEach { (key, label, checked) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(label, Modifier.weight(1f))
                            Switch(
                                checked = checked,
                                onCheckedChange = { onSetSessionListSetting(key, it) },
                                enabled = key != "frequent_first" || state.showFrequentSplit,
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                        }
                    }
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
                    Text("Theme")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            AppTheme.SYSTEM to "System",
                            AppTheme.DARK to "Dark",
                            AppTheme.SUNLIGHT to "Sunlight",
                        ).forEach { (theme, label) ->
                            if (appTheme == theme) {
                                FilledTonalButton(
                                    onClick = { onSetAppTheme(theme) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetAppTheme(theme) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                    Text("Default Reader font size")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(0.9f to "Small", 1f to "Normal", 1.15f to "Large").forEach { (scale, label) ->
                            if (kotlin.math.abs(readerDefaultFontScale - scale) < 0.01f) {
                                FilledTonalButton(
                                    onClick = { onSetReaderDefaultFontScale(scale) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetReaderDefaultFontScale(scale) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
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
                            OutlinedButton(onClick = { crashReportVisible = true }) { Text("View report") }
                            TextButton(onClick = onClearCrashReport) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
    if (crashReportVisible) {
        MultipromptTheme(sunlight = sunlight, material3 = false) {
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
    }
    if (settingsPage == SettingsPage.MAPPING && state.pendingHostKeys.isEmpty()) {
        HostMappingDialog(state, onMapAccountHost, onDismiss = { settingsPage = SettingsPage.PREFERENCES })
    }
    if (settingsPage != SettingsPage.HIDDEN) return@MultipromptTheme
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
                    arrangedWorkspaceSplitIds.forEachIndexed { index, splitId ->
                        val workspace = splitId?.let { id ->
                            state.workspaces.firstOrNull { it.id == id }
                        }
                        val splitName = workspace?.name ?: "All"
                        val sessionCount = if (splitId == null) {
                            state.inboxSessions.count { !isMutedSession(it, state.accountLayout, state.accountHosts) }
                        } else {
                            state.inboxSessions.count { !isMutedSession(it, state.accountLayout, state.accountHosts) && state.sessionWorkspaceIds[SessionReadStore.key(it.hostId, it.name)] == splitId }
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
                                enabled = index < arrangedWorkspaceSplitIds.lastIndex,
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
                    workspaceError?.let { InlineError(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    workspaceError = onCreateWorkspace(workspaceName)
                    if (workspaceError == null) {
                        workspaceDialogVisible = false
                        workspaceName = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { workspaceDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
    val anyEta = state.sessionEtas.isNotEmpty() || state.selectedWorkspaceId == FREQUENT_SPLIT_ID
    var etaNowMillis by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(anyEta) {
        while (anyEta) {
            etaNowMillis = nowMillis()
            delay(1_000)
        }
    }
    val normalizedQuery = searchQuery.trim()
    val workspaceNames = state.workspaces.associate { it.id to it.name }
    val matchingSessions = state.inboxSessions
        .filterNot { isHiddenAsMuted(it, state.accountLayout, state.accountHosts, state.selectedWorkspaceId) }
        .filter { session ->
            val key = SessionReadStore.key(session.hostId, session.name)
            state.sessionBucket != SessionBucket.ARCHIVE &&
                (key in state.archivedSessionKeys) == (state.sessionBucket == SessionBucket.WAITING) &&
                (when (state.selectedWorkspaceId) {
                    FAVORITES_SPLIT_ID -> isFavoriteSession(session, state.accountLayout, state.accountHosts)
                    FREQUENT_SPLIT_ID -> true
                    else -> {
                        normalizedQuery.isNotBlank() || state.selectedWorkspaceId == null ||
                            state.sessionWorkspaceIds[key] == state.selectedWorkspaceId
                    }
                }) &&
                SessionSearch.matches(
                    session = session,
                    query = normalizedQuery,
                    hostLabel = hostLabels[session.hostId].orEmpty(),
                    workspaceName = workspaceNames[state.sessionWorkspaceIds[key]].orEmpty(),
                )
        }
    val visibleSessions = if (state.selectedWorkspaceId == FREQUENT_SPLIT_ID) {
        frequentSessions(matchingSessions, state.accountLayout, state.accountHosts, etaNowMillis)
    } else SessionSearch.newestFirst(matchingSessions, state.sessionInteractionEpochSeconds)
    val pinnedSessions = if (state.sessionBucket == SessionBucket.OPEN && normalizedQuery.isBlank() &&
        state.selectedWorkspaceId != FAVORITES_SPLIT_ID && state.selectedWorkspaceId != FREQUENT_SPLIT_ID
    ) visibleSessions.filter { isFavoriteSession(it, state.accountLayout, state.accountHosts) } else emptyList()
    val rowSessions = visibleSessions.filterNot { it in pinnedSessions }
    val visibleDissolvedSessions = if (state.sessionBucket == SessionBucket.ARCHIVE && state.selectedWorkspaceId != FAVORITES_SPLIT_ID && state.selectedWorkspaceId != FREQUENT_SPLIT_ID) {
        state.dissolvedSessions.filter { session ->
            normalizedQuery.isBlank() ||
                session.displayName.contains(normalizedQuery, ignoreCase = true) ||
                session.tmuxSessionName.contains(normalizedQuery, ignoreCase = true) ||
                session.workspaceName.contains(normalizedQuery, ignoreCase = true)
        }
    } else {
        emptyList()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopFade {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (LocalMaterial3.current) Modifier.height(64.dp) else Modifier)
                        .padding(horizontal = 8.dp, vertical = if (LocalReaderStyle.current) ReaderScreenPadding else if (LocalMaterial3.current) 0.dp else 2.dp)
                        .then(if (LocalReaderStyle.current) Modifier.offset(y = (-6).dp) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(
                            onClick = { bucketMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = if (LocalReaderStyle.current) Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier,
                        ) {
                            Text(
                                when (state.sessionBucket) {
                                    SessionBucket.OPEN -> "Inbox"
                                    SessionBucket.WAITING -> "Waiting"
                                    SessionBucket.ARCHIVE -> "Archive"
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                style = if (LocalMaterial3.current) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                fontWeight = if (LocalMaterial3.current) FontWeight.Normal else FontWeight.SemiBold,
                            )
                            Icon(Icons.Default.ExpandMore, "Switch session bucket", tint = MaterialTheme.colorScheme.onSurface)
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
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                        }
                        IconButton(
                            onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) {
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.roundOutlined(),
                        ) {
                            Icon(
                                if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                if (searchVisible) "Close session search" else "Search sessions",
                            )
                        }
                        IconButton(
                            enabled = !state.creatingSession,
                            onClick = onStartNewSession,
                            modifier = Modifier.roundOutlined(),
                        ) {
                            if (state.creatingSession) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Add, "New session")
                            }
                        }
                        InboxOverflowMenu(
                            onSettings = { settingsPage = SettingsPage.PREFERENCES },
                            onArrangeSplits = { splitOrderDialogVisible = true },
                            onNewWorkspace = {
                                workspaceError = null
                                workspaceDialogVisible = true
                            },
                            onRefresh = onRefresh,
                            onReleaseNotes = onReleaseNotes,
                        )
                    }
                }
                if (searchVisible) {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .then(if (LocalReaderStyle.current) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), ReaderComposerShape) else Modifier)
                                .focusRequester(searchFocusRequester),
                            filled = true,
                            search = true,
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
                if (LocalMaterial3.current) {
                    ScrollableTabRow(
                        selectedTabIndex = displayedWorkspaceSplitIds.indexOf(state.selectedWorkspaceId).coerceAtLeast(0),
                        edgePadding = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        indicator = { positions ->
                            val index = displayedWorkspaceSplitIds.indexOf(state.selectedWorkspaceId).coerceAtLeast(0)
                            Box(
                                Modifier.tabIndicatorOffset(positions[index]).height(3.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                            )
                        },
                        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                    ) {
                        displayedWorkspaceSplitIds.forEach { id ->
                            Tab(
                                selected = state.selectedWorkspaceId == id,
                                onClick = { onSelectWorkspace(id) },
                                modifier = Modifier.height(48.dp),
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (id == FAVORITES_SPLIT_ID) {
                                            FavoriteDot()
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            when (id) {
                                                null -> "All"
                                                FAVORITES_SPLIT_ID -> "Favorites"
                                                FREQUENT_SPLIT_ID -> "Frequent"
                                                else -> state.workspaces.firstOrNull { it.id == id }?.name.orEmpty()
                                            },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                if (pinnedSessions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pinnedSessions.forEach { session ->
                            val key = SessionReadStore.key(session.hostId, session.name)
                            Column(
                                Modifier.width(80.dp).height(90.dp).clickable { onOpen(session) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box {
                                    BotAvatar(session.name, 56.dp, workspaceColor = session.workspaceColor)
                                    if (key in state.unreadSessionKeys) {
                                        Badge(
                                            Modifier.align(Alignment.TopEnd).semantics {
                                                contentDescription = "${(state.unreadCounts[key] ?: 1).coerceAtLeast(1)} unread messages"
                                            },
                                            containerColor = Color(0xFF89B4F8),
                                            contentColor = Color(0xFF121212),
                                        ) { Text(UnreadCount.label(state.unreadCounts[key] ?: 1)) }
                                    }
                                }
                                Text(session.displayName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (state.updatingHostIds.isNotEmpty()) {
                    UpdatingSessionsNotice(hostLabels = state.updatingHostIds.mapNotNull { hostLabels[it] })
                }
                if (state.cachedHostIds.isNotEmpty()) {
                    CachedSessionsNotice(
                        hostLabels = state.cachedHostIds.mapNotNull { hostLabels[it] },
                        onRetry = onRefresh,
                        retryEnabled = !state.refreshing,
                    )
                }
                state.sessionActionError?.let { error ->
                    DismissibleError(message = error, onDismiss = onClearSessionActionError)
                }
            }
        },
        bottomBar = {
            if (!LocalMaterial3.current) {
                BottomFade {
                    Row(
                        Modifier.padding(horizontal = ReaderScreenPadding, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .horizontalScroll(splitScrollState).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        displayedWorkspaceSplitIds.forEach { id ->
                            val name = when (id) {
                                null -> "All"
                                FAVORITES_SPLIT_ID -> "Favorites"
                                FREQUENT_SPLIT_ID -> "Frequent"
                                else -> state.workspaces.firstOrNull { it.id == id }?.name.orEmpty()
                            }
                            val label: @Composable RowScope.() -> Unit = {
                                Text(name, color = if (state.selectedWorkspaceId == id) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.selectedWorkspaceId == id) {
                                androidx.compose.material3.FilledTonalButton(onClick = { onSelectWorkspace(id) }, modifier = Modifier.semantics { selected = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = if (sunlight) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFF353535), contentColor = MaterialTheme.colorScheme.onSurface), content = label)
                            } else {
                                TextButton(onClick = { onSelectWorkspace(id) }, modifier = Modifier.semantics { selected = false }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), content = label)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("No sessions yet", "Configure servers through the CLI, then check Host mapping in Settings.") { settingsPage = SettingsPage.MAPPING }
            }
        } else LazyColumn(
            Modifier
                .fillMaxSize()
                .horizontalSwipe(onSwitchWorkspace, PointerEventPass.Final),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 4.dp),
            reverseLayout = normalizedQuery.isBlank() && state.newestSessionsAtBottom,
        ) {
            state.hostErrors.forEach { (hostId, error) ->
                if (hostId !in state.cachedHostIds) {
                    item(key = "error-$hostId") { InlineError(error) }
                }
            }
            items(rowSessions, key = { "${it.hostId}:${it.name}" }) { session ->
                val key = SessionReadStore.key(session.hostId, session.name)
                val tab = state.accountLayout?.let { AccountHostMap.tabFor(session, it, state.accountHosts) }
                val starred = tab?.favorite == true
                SessionCard(
                    session = session,
                    workspaces = state.workspaces,
                    unread = key in state.unreadSessionKeys,
                    unreadCount = state.unreadCounts[key] ?: 1,
                    archived = key in state.archivedSessionKeys,
                    starred = starred,
                    onToggleFavorite = if (tab != null && state.signedIn) {
                        { onSetFavorite(tab, !starred) }
                    } else null,
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
                    onForget = { onForgetSession(session) },
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
                            state.selectedWorkspaceId == FAVORITES_SPLIT_ID -> "No starred sessions"
                            state.selectedWorkspaceId == FREQUENT_SPLIT_ID -> "No frequent sessions"
                            state.sessionBucket == SessionBucket.ARCHIVE -> "No archived sessions"
                            state.sessionBucket == SessionBucket.WAITING -> "No waiting sessions"
                            else -> "This split is clear"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
internal fun Modifier.roundOutlined(): Modifier = if (LocalReaderStyle.current) {
    size(ReaderControlSize)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
} else {
    this
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
        AgentKind.HAX -> "▌"
        AgentKind.CURSOR -> "▲"
        AgentKind.OTHER -> "›"
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            glyph,
            color = agentAccent(agent),
            fontFamily = FontFamily.SansSerif,
            fontSize = if (LocalMaterial3.current) MaterialTheme.typography.titleMedium.fontSize else 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun agentAccent(agent: AgentKind): Color = if (LocalMaterial3.current || MaterialTheme.colorScheme.background.luminance() > 0.5f) MaterialTheme.colorScheme.primary else when (agent) {
    AgentKind.CLAUDE -> Color(0xFFD97757)
    AgentKind.CODEX -> Color(0xFF10A37F)
    AgentKind.PI -> Color(0xFFA78BFA)
    AgentKind.KIMI -> Color(0xFF4C8DFF)
    AgentKind.HAX -> Color(0xFFE0A458)
    AgentKind.CURSOR -> Color(0xFF34D399)
    AgentKind.OTHER -> Color(0xFFAFB8C8)
}

@Composable
private fun AgentBadge(agent: AgentKind) {
    Row(
        Modifier
            .background(
                agentAccent(agent).copy(alpha = 0.16f),
                chromeShape(RoundedCornerShape(percent = 50)),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: TmuxSession,
    workspaces: List<Workspace>,
    unread: Boolean,
    unreadCount: Int,
    archived: Boolean,
    starred: Boolean,
    onToggleFavorite: (() -> Unit)?,
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
    onForget: () -> Unit,
) {
    var menuExpanded by remember(session.hostId, session.name) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (!archived) {
                when (value) {
                    SwipeToDismissBoxValue.EndToStart -> onArchive()
                    SwipeToDismissBoxValue.StartToEnd -> onRemind()
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
            false
        },
    )
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
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClickLabel = "Session actions",
                        onLongClick = { menuExpanded = true },
                    )
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotAvatar(session.name, workspaceColor = session.workspaceColor)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        session.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        SessionPreview.line(session.preview),
                        modifier = Modifier.testTag("Session preview"),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    sessionActivityLabel(session.lastActivityEpochSeconds, System.currentTimeMillis() / 1000),
                    modifier = Modifier.testTag("Session activity"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                )
                if (unread) {
                    Badge(
                        modifier = Modifier.semantics {
                            contentDescription = "${unreadCount.coerceAtLeast(1)} unread messages"
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(UnreadCount.label(unreadCount))
                    }
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    onToggleFavorite?.let { toggle ->
                        DropdownMenuItem(
                            text = { Text(if (starred) "Unfavorite" else "Favorite") },
                            onClick = {
                                menuExpanded = false
                                toggle()
                            },
                        )
                    }
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
                    DropdownMenuItem(
                        text = { Text("Remove from list") },
                        onClick = {
                            menuExpanded = false
                            onForget()
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
                        workspaces.forEach { workspace ->
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

internal enum class ReaderMenuPage(val title: String) {
    MAIN("Session actions"),
    MODEL("Model and effort"),
    DISPLAY("Display"),
    MORE("More"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    session: TmuxSession,
    modelLabel: String?,
    statusLabel: String? = null,
    onModelCommand: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    onReleaseNotes: () -> Unit,
    onMenuClick: () -> Unit,
    menuContent: @Composable () -> Unit,
    onBack: () -> Unit = {},
) {
    var modelMenuExpanded by remember(session.hostId, session.name) { mutableStateOf(false) }
    if (LocalReaderStyle.current) {
        TopFade {
            Row(
                Modifier.fillMaxWidth().testTag("reader-floating-controls")
                    .padding(ReaderScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.roundOutlined()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(session.name, 28.dp, workspaceColor = session.workspaceColor)
                        Text(
                            session.displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = onMenuClick, modifier = Modifier.roundOutlined()) {
                        Icon(Icons.Default.MoreVert, "Session actions")
                    }
                    menuContent()
                }
            }
        }
        return
    }
    TopAppBar(
        expandedHeight = if (LocalMaterial3.current) 64.dp else androidx.compose.material3.TopAppBarDefaults.TopAppBarExpandedHeight,
        navigationIcon = {
            if (LocalMaterial3.current) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { onClick(label = "Back", action = null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            }
        },
        modifier = Modifier.testTag("reader-top-bar").statusBarsPadding().pointerInput(onOpenTerminal) {
            detectTapGestures(onDoubleTap = { onOpenTerminal() })
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BotAvatar(session.name, 28.dp, workspaceColor = session.workspaceColor)
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { modelMenuExpanded = true }
                            .then(if (LocalMaterial3.current) Modifier.heightIn(min = 48.dp) else Modifier),
                    ) {
                        Text(
                            session.displayName,
                            style = if (LocalMaterial3.current) MaterialTheme.typography.titleLarge else androidx.compose.material3.LocalTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(modelLabel, statusLabel).joinToString(" · ").ifEmpty { "Model / effort" },
                            style = if (LocalMaterial3.current) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
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
                            text = { Text("Current: ${modelLabel ?: "Unknown"}") },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("Open model picker (/model)") },
                            onClick = {
                                modelMenuExpanded = false
                                onModelCommand("/model")
                            },
                        )
                        if (session.agent == AgentKind.CLAUDE) {
                            DropdownMenuItem(
                                text = { Text("Use Claude Opus") },
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model opus")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Use Claude Sonnet") },
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model sonnet")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Use Claude Fable") },
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model fable")
                                },
                            )
                            listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                DropdownMenuItem(
                                    text = { Text("Set effort: $level") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        onModelCommand("/effort $level")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        actions = {
            VersionLabel(onClick = onReleaseNotes)
            Box {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.MoreVert, "Session actions")
                }
                menuContent()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreen(
    connection: SessionReaderConnection,
    dictation: DeepgramDictation,
    screencast: ScreencastUploader,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    session: TmuxSession,
    unread: Boolean,
    archived: Boolean,
    initialFontScale: Float,
    onBack: () -> Unit,
    onMarkRead: () -> Unit,
    onArchiveToggle: () -> Unit,
    onRemind: (Long?) -> Unit,
    onOpenTerminal: () -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onRename: (String) -> String?,
    onDissolve: () -> Unit,
    onEnd: () -> Unit,
    onSessionInteraction: () -> Unit,
    onSessionInput: () -> Unit,
    technicalMode: Boolean,
    onTechnicalModeChanged: (Boolean) -> Unit,
    sunlightMode: Boolean,
    onSetAppTheme: (AppTheme) -> Unit,
    onResetFontScale: () -> Float,
    sessionActionError: String?,
    onClearSessionActionError: () -> Unit,
    onForget: () -> Unit,
    onShowEtaAccuracy: () -> Unit,
    onReleaseNotes: () -> Unit,
    material3: Boolean = LocalMaterial3.current,
    unreadMessageKey: String? = null,
    onMarkUnreadFrom: (String) -> Unit = {},
) = key(connection) {
    MultipromptTheme(sunlight = sunlightMode, material3 = material3, readerStyle = true) {
    val reader by connection.state.collectAsState()
    val working = reader.isWorking(session.agent)
    LaunchedEffect(connection, working) {
        android.util.Log.i("SessionWorking", "session=${session.name} visible=$working hook=${reader.hookState?.active} status=${reader.status} sending=${reader.sending}")
    }
    val transcript = reader.transcript.takeUnless { technicalMode }
    val historyAgent = TranscriptProtocol.supports(session.agent)
    val historyChat = historyAgent && !technicalMode
    val chatListState = key(connection) { rememberLazyListState() }
    var etaNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(reader.eta) {
        while (reader.eta != null) {
            etaNowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sessionKey = SessionReadStore.key(session.hostId, session.name)
    val sharedDictationState by dictation.state.collectAsState()
    val dictationState = sharedDictationState.takeIf { it.sessionKey == sessionKey }
        ?: DictationState(configured = sharedDictationState.configured)
    var promptField by remember(connection) { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember(connection, session.hostId, session.name) { mutableStateOf<String?>(null) }
    var replyAuthor by remember(connection, session.hostId, session.name) { mutableStateOf("") }
    val replyGate = remember(connection, session.hostId, session.name) { ReplySwipeGate() }
    val promptFocusRequester = remember { FocusRequester() }
    val chooseReply: (String, Boolean) -> Unit = { text, isUser ->
        replyTo = text
        replyAuthor = if (isUser) "You" else session.agent.label
        promptFocusRequester.requestFocus()
    }
    val prompt = promptField.text
    val skillQuery = if (session.agent == AgentKind.CLAUDE && promptField.selection.collapsed) {
        NativeSkills.query(prompt, promptField.selection.start)
    } else null
    val setPrompt: (String) -> Unit = { value ->
        promptField = TextFieldValue(value, selection = TextRange(value.length))
    }
    var dictationPrefix by remember(connection) { mutableStateOf("") }
    val dictationSubmissionStarted = remember(connection) { AtomicBoolean(false) }
    var pendingPromptAction by remember(connection) { mutableStateOf<Long?>(null) }
    var sendMarkPending by remember(connection) { mutableStateOf(false) }
    var sentDraft by remember(connection) { mutableStateOf<SentDraft?>(null) }
    var menuExpanded by remember(connection) { mutableStateOf(false) }
    var menuPage by remember(connection) { mutableStateOf(ReaderMenuPage.MAIN) }
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
    var attachMenuExpanded by remember(connection) { mutableStateOf(false) }
    var attachMenuPage by remember(connection) { mutableStateOf(ComposerMenuPage.MAIN) }
    var historyDialogOpen by remember(connection) { mutableStateOf(false) }
    var endDialogVisible by remember(connection) { mutableStateOf(false) }
    var renameDialogVisible by remember(connection) { mutableStateOf(false) }
    var reminderDialogVisible by remember(connection) { mutableStateOf(false) }
    var renameDraft by remember(connection) { mutableStateOf(session.displayName) }
    var renameError by remember(connection) { mutableStateOf<String?>(null) }
    var transcriptZoom by remember(connection, session.hostId, session.name) {
        mutableFloatStateOf(initialFontScale)
    }
    val previewRuntimeDetails = remember(session.preview) { TmuxText.runtimeDetails(session.preview) }
    val runtimeDetails = reader.runtimeDetails.takeIf { it.model != null } ?: previewRuntimeDetails
    val transcriptTransform = rememberTransformableState { _, zoomChange, _, _ ->
        val nextScale = (transcriptZoom * zoomChange).coerceIn(0.75f, 5f)
        transcriptZoom = nextScale
        onFontScaleChanged(nextScale)
    }
    val scrollState = rememberReaderScrollState(connection)
    var followRequest by remember(connection) { mutableStateOf(0) }
    val scrollLive: () -> Unit = {
        onSessionInteraction()
        followRequest++
        connection.scrollLive()
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val readerScope = rememberCoroutineScope()
    var loadingOlder by remember(connection) { mutableStateOf(false) }
    var noOlderHistory by remember(connection) { mutableStateOf(false) }
    val loadOlder: () -> Unit = {
        if (!historyAgent && transcript == null && reader.alternateOn && !loadingOlder && !noOlderHistory) {
            loadingOlder = true
            readerScope.launch {
                val loaded = connection.loadOlderIntoTranscript()
                if (!loaded) noOlderHistory = true
                loadingOlder = false
            }
        }
    }
    val loadOlderRef = rememberUpdatedState(loadOlder)
    val pullThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollLoader = remember(connection, scrollState, pullThresholdPx) {
        object : NestedScrollConnection {
            private var pulledPx = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (scrollState.value == 0 && available.y > 0f) {
                    pulledPx += available.y
                    if (pulledPx >= pullThresholdPx) {
                        pulledPx = 0f
                        loadOlderRef.value()
                    }
                } else {
                    pulledPx = 0f
                }
                return Offset.Zero
            }
        }
    }
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
        val previousKeepScreenOn = readerView.keepScreenOn
        if (dictationActive) readerView.keepScreenOn = true
        onDispose {
            if (dictationActive) readerView.keepScreenOn = previousKeepScreenOn
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            microphoneError = null
            dictation.start(sessionKey)
        } else {
            microphoneError = "Microphone permission is required for voice input"
        }
    }
    val startDictation = {
        dictationPrefix = prompt.trimEnd()
        dictationSubmissionStarted.set(false)
        microphoneError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            dictation.start(sessionKey)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(reader.completedActions) {
        val pending = pendingPromptAction
        if (pending != null && reader.completedActions > pending) {
            sentDraft = null
            pendingPromptAction = null
        }
    }
    LaunchedEffect(reader.failedActions) {
        if (reader.failedActions > 0) {
            pendingPromptAction = null
            sentDraft?.let { draft ->
                setPrompt(draft.restoredPrompt(prompt))
                imageAttachments = draft.restoredImages(imageAttachments)
                replyTo = replyTo ?: draft.replyTo
            }
            sentDraft = null
        }
    }
    LaunchedEffect(dictationState.transcript) {
        val spoken = dictationState.transcript.trim()
        if (spoken.isNotBlank()) {
            setPrompt(PromptComposer.appendDictation(dictationPrefix, spoken))
        }
    }
    DisposableEffect(connection) {
        onDispose {
            if (dictation.state.value.sessionKey == sessionKey) {
                if (dictation.state.value.status in setOf(DictationStatus.CONNECTING, DictationStatus.LISTENING, DictationStatus.FINISHING)) {
                    android.widget.Toast.makeText(context, "Dictation stopped: session changed", android.widget.Toast.LENGTH_SHORT).show()
                }
                dictation.discard()
            }
        }
    }
    LaunchedEffect(connection, skillQuery != null) {
        if (skillQuery != null) {
            connection.loadSkills(context.assets.open("native_skills.py").use { it.readBytes() })
        }
    }
    val sendCurrentPrompt = {
        val composedPrompt = PromptComposer.composeMessage(prompt, imageAttachments, replyTo, reader.nativeSkills)
        if (composedPrompt.isNotBlank() &&
            !reader.sending &&
            pendingPromptAction == null
        ) {
            val actionCount = reader.completedActions
            val draft = SentDraft(prompt, imageAttachments, replyTo)
            if (connection.sendPrompt(composedPrompt)) {
                SendTiming.mark("message_added")
                sentDraft = draft
                setPrompt("")
                imageAttachments = emptyList()
                replyTo = null
                sendMarkPending = true
                onSessionInteraction()
                onSessionInput()
                pendingPromptAction = actionCount
            }
        }
    }
    val sendModelCommand: (String) -> Unit = { command ->
        if (!reader.sending && pendingPromptAction == null) {
            val actionCount = reader.completedActions
            if (connection.sendPrompt(command)) {
                onSessionInteraction()
                onSessionInput()
                pendingPromptAction = actionCount
            }
        }
    }
    val submitPrompt = {
        if (!reader.sending && pendingPromptAction == null) {
            SendTiming.tap()
            if (dictationActive) {
                if (dictationSubmissionStarted.compareAndSet(false, true)) {
                    sendAfterDictation = true
                    dictation.stop()
                }
            } else {
                sendCurrentPrompt()
            }
        }
    }
    LaunchedEffect(dictationState.status, sendAfterDictation) {
        if (!sendAfterDictation) return@LaunchedEffect
        when (dictationState.status) {
            DictationStatus.IDLE -> {
                if (!dictationSubmissionStarted.compareAndSet(true, false)) return@LaunchedEffect
                val finalPrompt = PromptComposer.appendDictation(
                    dictationPrefix,
                    dictationState.transcript,
                )
                setPrompt(finalPrompt)
                sendAfterDictation = false
                val composedPrompt = PromptComposer.composeMessage(finalPrompt, imageAttachments, replyTo, reader.nativeSkills)
                if (composedPrompt.isNotBlank() &&
                    !reader.sending &&
                    pendingPromptAction == null
                ) {
                    val actionCount = reader.completedActions
                    val draft = SentDraft(finalPrompt, imageAttachments, replyTo)
                    if (connection.sendPrompt(composedPrompt)) {
                        sentDraft = draft
                        setPrompt("")
                        imageAttachments = emptyList()
                        onSessionInteraction()
                        onSessionInput()
                        pendingPromptAction = actionCount
                        replyTo = null
                    }
                }
            }
            DictationStatus.FAILED -> {
                dictationSubmissionStarted.set(false)
                sendAfterDictation = false
            }
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
    if (historyDialogOpen) {
        AgentHistoryDialog(connection) {
            historyDialogOpen = false
            readerScope.launch { connection.exitHistory() }
        }
    }
    val statusLabel = listOfNotNull(
        reader.eta?.let { "ETA ${it.label(etaNowMillis)}" },
        "waiting for input".takeIf { reader.waitingForInput },
    ).joinToString(" · ").ifEmpty { null }
    BackHandler(onBack = onBack)
    Scaffold(
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        floatingActionButton = {
            if (if (historyChat) chatListState.canScrollBackward else scrollState.canScrollForward) {
                FloatingActionButton(onClick = scrollLive, modifier = Modifier.size(ReaderJumpSize), shape = CircleShape, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Icon(Icons.Default.KeyboardArrowDown, "Jump to newest")
                }
            }
        },
        topBar = {
            ReaderTopBar(
                onBack = onBack,
                session = session,
                modelLabel = runtimeDetails.label,
                statusLabel = statusLabel,
                onModelCommand = sendModelCommand,
                onOpenTerminal = onOpenTerminal,
                onReleaseNotes = onReleaseNotes,
                onMenuClick = { menuPage = ReaderMenuPage.MAIN; menuExpanded = true },
                menuContent = {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (menuPage != ReaderMenuPage.MAIN) {
                            DropdownMenuItem(
                                text = { Text("‹ Back") },
                                onClick = { menuPage = ReaderMenuPage.MAIN },
                            )
                            HorizontalDivider()
                        }
                        when (menuPage) {
                            ReaderMenuPage.MAIN -> {
                                DropdownMenuItem(
                                    text = { Text(listOfNotNull(runtimeDetails.label, statusLabel).joinToString(" · ").ifEmpty { "Model / effort" }) },
                                    enabled = false,
                                    onClick = {},
                                )
                                DropdownMenuItem(
                                    text = { Text("Enter / approve") },
                                    enabled = !reader.sending,
                                    onClick = {
                                        menuExpanded = false
                                        onSessionInteraction()
                                        onSessionInput()
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
                                if (reader.alternateOn) {
                                    DropdownMenuItem(
                                        text = { Text("Session history") },
                                        onClick = {
                                            menuExpanded = false
                                            onSessionInteraction()
                                            readerScope.launch {
                                                connection.enterHistory()
                                                historyDialogOpen = true
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Open live terminal") },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenTerminal()
                                    },
                                )
                                HorizontalDivider()
                                if (unread) {
                                    DropdownMenuItem(
                                        text = { Text("Mark read") },
                                        onClick = {
                                            menuExpanded = false
                                            onMarkRead()
                                        },
                                    )
                                }
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
                                    text = { Text("Remind me") },
                                    onClick = { menuExpanded = false; reminderDialogVisible = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (archived) "Return to open" else "Waiting") },
                                    onClick = { menuExpanded = false; onArchiveToggle() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Archive session") },
                                    onClick = { menuExpanded = false; onDissolve() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Model and effort ›") },
                                    onClick = { menuPage = ReaderMenuPage.MODEL },
                                )
                                DropdownMenuItem(
                                    text = { Text("Display ›") },
                                    onClick = { menuPage = ReaderMenuPage.DISPLAY },
                                )
                                DropdownMenuItem(
                                    text = { Text("More ›") },
                                    onClick = { menuPage = ReaderMenuPage.MORE },
                                )
                                DropdownMenuItem(
                                    text = { Text("Resync chat") },
                                    onClick = {
                                        menuExpanded = false
                                        readerScope.launch {
                                            val confirmed = connection.resyncChat()
                                            followRequest++
                                            android.widget.Toast.makeText(context, if (confirmed) "Synced" else "Some messages could not be confirmed", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("End session", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        endDialogVisible = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove from list") },
                                    onClick = {
                                        menuExpanded = false
                                        onForget()
                                    },
                                )
                            }
                            ReaderMenuPage.MODEL -> {
                                DropdownMenuItem(
                                    text = { Text("Current: ${runtimeDetails.label ?: "Unknown"}") },
                                    enabled = false,
                                    onClick = {},
                                )
                                DropdownMenuItem(
                                    text = { Text("Open model picker (/model)") },
                                    onClick = { menuExpanded = false; sendModelCommand("/model") },
                                )
                                if (session.agent == AgentKind.CLAUDE) {
                                    listOf("Opus", "Sonnet", "Fable").forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text("Use Claude $model") },
                                            onClick = { menuExpanded = false; sendModelCommand("/model ${model.lowercase()}") },
                                        )
                                    }
                                    HorizontalDivider()
                                    listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text("Set effort: $level") },
                                            onClick = { menuExpanded = false; sendModelCommand("/effort $level") },
                                        )
                                    }
                                }
                            }
                            ReaderMenuPage.DISPLAY -> {
                                DropdownMenuItem(
                                    text = { Text(if (technicalMode) "Hide terminal details" else "Show terminal details") },
                                    onClick = {
                                        menuExpanded = false
                                        onTechnicalModeChanged(!technicalMode)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (sunlightMode) "Sunlight mode off" else "Sunlight mode on") },
                                    onClick = {
                                        menuExpanded = false
                                        onSetAppTheme(if (sunlightMode) AppTheme.DARK else AppTheme.SUNLIGHT)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Use default font size") },
                                    onClick = {
                                        menuExpanded = false
                                        transcriptZoom = onResetFontScale()
                                    },
                                )
                            }
                            ReaderMenuPage.MORE -> {
                                DropdownMenuItem(
                                    text = { Text("Release notes") },
                                    onClick = { menuExpanded = false; onReleaseNotes() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Estimate accuracy") },
                                    onClick = {
                                        menuExpanded = false
                                        onShowEtaAccuracy()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Copy raw transcript (${reader.output.count { it == '\n' } + 1} lines · " +
                                                "alt ${if (reader.alternateOn) 1 else 0} · " +
                                                "view ${scrollState.value}/${scrollState.maxValue})",
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("multiprompt transcript", reader.output),
                                        )
                                    },
                                )
                                HorizontalDivider()
                                AppMenuItems(
                                    signedIn = signedIn,
                                    onSignIn = onSignIn,
                                    onSignOut = onSignOut,
                                    onDismiss = { menuExpanded = false },
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
                    }
                },
            )
        },
        bottomBar = {
            BottomFade(
                Modifier.imePadding().padding(horizontal = ReaderScreenPadding, vertical = ReaderControlSpacing),
                verticalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
            ) {
                if (skillQuery != null && !dictationActive) {
                    NativeSkillPicker(
                        skills = NativeSkills.matches(reader.nativeSkills, skillQuery),
                        loading = reader.skillsLoading,
                        error = reader.skillsError,
                        onSelect = { skill ->
                            promptField = TextFieldValue(NativeSkills.insert(prompt, skill.name), selection = TextRange(skill.name.length + 2))
                            promptFocusRequester.requestFocus()
                        },
                    )
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
                                    Icon(Icons.Default.Image, "Image attachment", Modifier.size(if (LocalMaterial3.current) 20.dp else 18.dp))
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
                                        modifier = Modifier.size(if (LocalMaterial3.current) 48.dp else 30.dp),
                                    ) {
                                        Icon(Icons.Default.Close, "Remove image ${index + 1}")
                                    }
                                }
                            }
                        }
                    }
                }
                replyTo?.let { quoted ->
                    ReplyQuoteBar(message = quoted, author = replyAuthor, onCancel = { replyTo = null })
                }
                if (dictationActive) {
                    DictationBar(
                        level = dictation.level,
                        sending = reader.sending || sendAfterDictation || pendingPromptAction != null,
                        onStop = { dictation.stop() },
                        onSend = submitPrompt,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
                    ) {
                        Box {
                            IconButton(
                                onClick = { attachMenuPage = ComposerMenuPage.MAIN; attachMenuExpanded = true },
                                enabled = !reader.sending && !dictationActive && !imageUploading,
                                modifier = Modifier.size(ReaderControlSize)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                    .border(ReaderOutlineWidth, ReaderOutline, CircleShape),
                            ) {
                                if (imageUploading) CircularProgressIndicator(Modifier.size(ReaderBadgeSize), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Add, "Attach")
                            }
                            DropdownMenu(
                                expanded = attachMenuExpanded,
                                onDismissRequest = { attachMenuExpanded = false },
                                shape = ReaderMenuShape,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(ReaderOutlineWidth, ReaderOutline),
                            ) {
                                when (attachMenuPage) {
                                    ComposerMenuPage.MAIN -> {
                                        DropdownMenuItem(
                                            text = { Text("Add image") },
                                            leadingIcon = { Icon(Icons.Outlined.OutlinedImage, null) },
                                            onClick = {
                                                attachMenuExpanded = false
                                                if (screencast.configured) imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                else imageKeyDialogVisible = true
                                            },
                                        )
                                        if (session.agent == AgentKind.CLAUDE) {
                                            DropdownMenuItem(
                                                text = { Text("Skill") },
                                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                                onClick = {
                                                    attachMenuExpanded = false
                                                    promptField = TextFieldValue(NativeSkills.open(prompt), selection = TextRange(1))
                                                    promptFocusRequester.requestFocus()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Model") },
                                                trailingIcon = { Text(runtimeDetails.model ?: "Unknown") },
                                                onClick = { attachMenuPage = ComposerMenuPage.MODEL },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Effort") },
                                                trailingIcon = { Text(runtimeDetails.effort ?: "Unknown") },
                                                onClick = { attachMenuPage = ComposerMenuPage.EFFORT },
                                            )
                                        }
                                    }
                                    ComposerMenuPage.MODEL -> {
                                        DropdownMenuItem(text = { Text("‹ Back") }, onClick = { attachMenuPage = ComposerMenuPage.MAIN })
                                        DropdownMenuItem(text = { Text("Current: ${runtimeDetails.model ?: "Unknown"}") }, enabled = false, onClick = {})
                                        listOf("Opus", "Sonnet", "Fable").forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text("Use Claude $model") },
                                                onClick = { attachMenuExpanded = false; sendModelCommand("/model ${model.lowercase()}") },
                                            )
                                        }
                                    }
                                    ComposerMenuPage.EFFORT -> {
                                        DropdownMenuItem(text = { Text("‹ Back") }, onClick = { attachMenuPage = ComposerMenuPage.MAIN })
                                        DropdownMenuItem(text = { Text("Current: ${runtimeDetails.effort ?: "Unknown"}") }, enabled = false, onClick = {})
                                        listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                            DropdownMenuItem(
                                                text = { Text("Set effort: $level") },
                                                onClick = { attachMenuExpanded = false; sendModelCommand("/effort $level") },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = promptField,
                            onValueChange = { promptField = it },
                            modifier = Modifier.weight(1f).heightIn(min = ReaderComposerHeight)
                                .border(1.dp, MaterialTheme.colorScheme.outline, ReaderComposerShape).focusRequester(promptFocusRequester)
                                .drawWithContent {
                                    drawContent()
                                    if (sendMarkPending && prompt.isEmpty()) {
                                        SendTiming.mark("box_cleared_frame")
                                        sendMarkPending = false
                                    }
                                },
                            filled = true,
                            placeholder = {
                                Text("Message ${session.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            minLines = 1,
                            maxLines = 6,
                            shape = ReaderComposerShape,
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
                                            .minimumInteractiveComponentSize().size(ReaderSendSize)
                                            .background(
                                                if (sunlightMode) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFF353535),
                                                CircleShape,
                                            ),
                                    ) {
                                        if (reader.sending || sendAfterDictation) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                "Send prompt",
                                                tint = if (sendEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                (microphoneError ?: dictationState.error ?: imageUploadError)?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                sessionActionError?.let { message ->
                    DismissibleError(message = message, onDismiss = onClearSessionActionError)
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
        if (historyChat) {
            ChatHistoryView(
                connection = connection,
                reader = reader,
                agent = session.agent,
                workingDirectory = session.workingDirectory,
                fontScale = transcriptZoom,
                sunlight = sunlightMode,
                replyGate = replyGate,
                onReply = chooseReply,
                onReaction = { message, emoji ->
                    val line = dev.multiprompt.companion.reader.ChatReactions.format(emoji, message.requestId, message.text)
                    if (line != null && connection.sendPrompt(line)) {
                        onSessionInteraction()
                        onSessionInput()
                    } else {
                        android.widget.Toast.makeText(context, "Could not send the reaction", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                sessionName = session.displayName,
                unreadMessageKey = unreadMessageKey,
                onMarkUnread = onMarkUnreadFrom,
                onSessionUsed = { onSessionInteraction(); onSessionInput() },
                listState = chatListState,
                followRequest = followRequest,
                bottomPadding = padding.calculateBottomPadding(),
                modifier = Modifier.padding(horizontal = ReaderScreenPadding)
                    .transformable(state = transcriptTransform, canPan = { false })
                    .pointerInput(onOpenTerminal) {
                        detectTapGestures(onDoubleTap = { onOpenTerminal() })
                    },
            )
        } else Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(overscrollLoader)
                .readerScroll(scrollState, followRequest)
                .pointerInput(onOpenTerminal) {
                    detectTapGestures(onDoubleTap = { onOpenTerminal() })
                }
                .padding(horizontal = ReaderScreenPadding).padding(top = ReaderFloatingInset + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), bottom = ReaderMessageSpacing + padding.calculateBottomPadding()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(ReaderMessageSpacing),
        ) {
            if (!historyAgent && transcript == null && reader.alternateOn) {
                OutlinedButton(
                    onClick = loadOlder,
                    enabled = !loadingOlder && !noOlderHistory && !reader.sending,
                ) {
                    Text(
                        when {
                            loadingOlder -> "Loading older messages…"
                            noOlderHistory -> "No older messages"
                            else -> "Load older messages"
                        },
                    )
                }
            }
            if (reader.status == ReaderStatus.Connecting && reader.output.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column {
                        Text("Connecting to live output…")
                        reader.lastFailure?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            val failure = reader.status as? ReaderStatus.Failed
            if (failure != null) {
                Row(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            chromeShape(RoundedCornerShape(8.dp)),
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
            val waitingForInput = reader.hookState?.isReady(System.currentTimeMillis())
                ?: reader.waitingForInput
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
            val readerBlocks = remember(displayedOutput, session.agent, reader.sentPrompts) {
                TmuxText.readerBlocks(displayedOutput, session.agent, reader.sentPrompts)
            }
            val visibleReaderBlocks = if (transcript != null) emptyList() else readerBlocks.filterNot { block ->
                !technicalMode && !TmuxText.containsLink(block.text) && (
                    block.kind == TmuxText.ReaderBlockKind.PROGRESS ||
                        block.kind == TmuxText.ReaderBlockKind.CODE
                    )
            }
            var expandedReaderBlocks by remember(connection) { mutableStateOf(emptySet<String>()) }
            val transcriptFontSize = chatFontSize((14f * transcriptZoom).sp, transcriptZoom)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, chromeShape(RoundedCornerShape(10.dp)))
                    .padding(6.dp)
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
                        when {
                            technicalMode -> "Terminal detail"
                            transcript != null -> "Transcript chat · recent messages"
                            else -> "Clean chat"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier
                            .background(connectionColor, chromeShape(RoundedCornerShape(percent = 50)))
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
                if (working) WorkingDots()
                if (reader.modelPickerOptions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Choose model or effort",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "Select an option from the active agent picker.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            reader.modelPickerOptions.forEach { option ->
                                OutlinedButton(
                                    onClick = {
                                        if (connection.selectModelPickerOption(option.index)) {
                                            onSessionInteraction()
                                            onSessionInput()
                                        }
                                    },
                                    enabled = !reader.sending,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        buildString {
                                            append(option.label)
                                            if (option.current) append(" · current")
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                transcript?.forEach { message ->
                    val isUser = message.role == "user"
                    val bodyColor = chatTextColor(sunlightMode, isUser)
                    key(connection, message.role, message.timestamp, message.text) {
                        if (unreadMessageKey == "transcript:${message.role}:${message.timestamp}:${message.text.hashCode()}") Text("New", color = MaterialTheme.colorScheme.primary)
                        ReplyableBubble(
                            isUser = isUser,
                            sunlight = sunlightMode,
                            text = message.text,
                            onReply = { chooseReply(it, isUser) },
                            gate = replyGate,
                            sessionName = session.displayName,
                            onMarkUnread = { onMarkUnreadFrom("transcript:${message.role}:${message.timestamp}:${message.text.hashCode()}") },
                        ) {
                            if (isUser) {
                                ChatLinkText(message.text) { line ->
                                    Text(
                                        terminalLinks(line, sunlightMode, linkColor = chatLinkColor(terminalBlue(sunlightMode))),
                                        fontFamily = chatFontFamily(),
                                        fontSize = transcriptFontSize,
                                        lineHeight = chatLineHeight((transcriptFontSize.value * 1.5f).sp, transcriptZoom),
                                        color = bodyColor,
                                    )
                                }
                            } else {
                                MarkdownText(
                                    text = message.text,
                                    fontSize = transcriptFontSize,
                                    color = bodyColor,
                                    linkColor = chatLinkColor(terminalBlue(sunlightMode)),
                                    imagePreviews = true,
                                )
                            }
                        }
                    }
                }
                if (transcript?.isEmpty() ?: visibleReaderBlocks.isEmpty()) {
                    Text(
                        "No recent output",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = transcriptFontSize,
                    )
                }
                visibleReaderBlocks.forEachIndexed { index, block ->
                    val blockKey = readerBlockKey(block)
                    when (block.kind) {
                        TmuxText.ReaderBlockKind.PROSE -> {
                            if (index > 0 && visibleReaderBlocks[index - 1].kind == TmuxText.ReaderBlockKind.PROSE) {
                                return@forEachIndexed
                            }
                            val run = visibleReaderBlocks.drop(index)
                                .takeWhile { it.kind == TmuxText.ReaderBlockKind.PROSE }
                            val text = run.joinToString("\n\n") { it.text }
                            key(connection, blockKey) {
                                if (unreadMessageKey == "block:${blockKey.hashCode()}") Text("New", color = MaterialTheme.colorScheme.primary)
                                ReplyableBubble(
                                    isUser = false,
                                    sunlight = sunlightMode,
                                    text = text,
                                    onReply = { chooseReply(it, false) },
                                    gate = replyGate,
                                    sessionName = session.displayName,
                                    onMarkUnread = { onMarkUnreadFrom("block:${blockKey.hashCode()}") },
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        run.forEach { part ->
                                            ChatLinkText(part.text) { line ->
                                                Text(
                                                    terminalLinks(line, sunlightMode, linkColor = chatLinkColor(terminalBlue(sunlightMode))),
                                                    fontFamily = chatFontFamily(),
                                                    fontSize = transcriptFontSize,
                                                    lineHeight = chatLineHeight((transcriptFontSize.value * 1.5f).sp, transcriptZoom),
                                                    color = chatTextColor(sunlightMode),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        TmuxText.ReaderBlockKind.USER_PROMPT -> key(connection, blockKey) {
                            if (unreadMessageKey == "block:${blockKey.hashCode()}") Text("New", color = MaterialTheme.colorScheme.primary)
                            ReplyableBubble(
                                isUser = true,
                                sunlight = sunlightMode,
                                text = block.text,
                                onReply = { chooseReply(it, true) },
                                gate = replyGate,
                                sessionName = session.displayName,
                                onMarkUnread = { onMarkUnreadFrom("block:${blockKey.hashCode()}") },
                            ) {
                                ChatLinkText(block.text) { line ->
                                    Text(
                                        terminalLinks(line, sunlightMode, linkColor = chatLinkColor(terminalBlue(sunlightMode))),
                                        fontFamily = chatFontFamily(),
                                        fontSize = transcriptFontSize,
                                        lineHeight = chatLineHeight((transcriptFontSize.value * 1.5f).sp, transcriptZoom),
                                        color = chatTextColor(sunlightMode),
                                    )
                                }
                            }
                        }
                        TmuxText.ReaderBlockKind.CODE,
                        TmuxText.ReaderBlockKind.PROGRESS -> {
                            ReaderCollapsibleBlock(
                                block = block,
                                expanded = blockKey in expandedReaderBlocks,
                                fontScale = transcriptZoom,
                                sunlightMode = sunlightMode,
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
}

@Composable
private fun ReaderCollapsibleBlock(
    block: TmuxText.ReaderBlock,
    expanded: Boolean,
    fontScale: Float,
    sunlightMode: Boolean,
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
        shape = chatShape(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
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
                            terminalLinks(block.text, sunlightMode),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = FontFamily.Default,
                            fontSize = chatFontSize((12f * fontScale).sp, fontScale),
                            lineHeight = chatLineHeight((1.45f * 12f * fontScale).sp, fontScale),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

internal fun isTransientProgress(value: String): Boolean = value.lineSequence().all { line ->
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
private fun UpdateScreen(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateRelease) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
            is UpdateState.Current -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have the latest version.", color = MaterialTheme.colorScheme.secondary)
                ChangelogNotes(state.notes)
            }
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
        val history = when (state) {
            is UpdateState.Current -> state.history
            is UpdateState.Available -> state.release.history
            is UpdateState.PermissionRequired -> state.release.history
            is UpdateState.Downloading -> state.release.history
            is UpdateState.Failed -> state.release?.history.orEmpty()
            else -> emptyList()
        }
        if (history.isNotEmpty()) {
            HorizontalDivider()
            Text("Release history", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            history.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        listOfNotNull("v${entry.versionName}", entry.date.ifBlank { null })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.notes.lineSequence().map(String::trim).filter(String::isNotBlank)
                        .forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
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
private fun ChangelogNotes(notes: String) {
    if (notes.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        notes.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateAvailableCard(release: UpdateRelease, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("v${release.versionName} available", fontWeight = FontWeight.Bold)
            ChangelogNotes(release.notes)
            Text("${release.sizeBytes / 1024 / 1024} MB", style = MaterialTheme.typography.labelMedium)
            Button(onClick = onInstall) { Text("Download and update") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onReleaseNotes: () -> Unit,
    onOpenHistory: () -> Unit,
    onLiveView: () -> Unit,
    onShowKeyboard: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier
            .statusBarsPadding()
            .pointerInput(onBack) { detectTapGestures(onDoubleTap = { onBack() }) },
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            VersionLabel(onClick = onReleaseNotes)
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open shell history")
            }
            IconButton(onClick = onLiveView) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back to the live view")
            }
            IconButton(onClick = onShowKeyboard) {
                Icon(Icons.Default.Keyboard, contentDescription = "Show keyboard")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    connection: TerminalConnection,
    title: String,
    columns: Int,
    rows: Int,
    savedFontSize: Float?,
    onFontSizeChange: (Float) -> Unit,
    onBack: () -> Unit,
    onSessionInput: () -> Unit,
    onReleaseNotes: () -> Unit,
) {
    val status by connection.status.collectAsState()
    val currentOnSessionInput by rememberUpdatedState(onSessionInput)
    val inputTracker = remember(connection) { TerminalInputTracker { currentOnSessionInput() } }
    val context = LocalContext.current
    val density = LocalDensity.current
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular) ?: Typeface.MONOSPACE
    }
    val fontSize = savedFontSize?.sp ?: defaultShellFontSize(MaterialTheme.typography.bodyMedium.fontSize)
    var historyVisible by remember(connection) { mutableStateOf(false) }
    val keyboardHeightPx = WindowInsets.ime.getBottom(density)
    val lineHeightPx = remember(fontSize, terminalTypeface, density) {
        val paint = Paint().apply {
            typeface = terminalTypeface
            textSize = with(density) { fontSize.toPx() }
        }
        val metrics = paint.fontMetrics
        kotlin.math.ceil((metrics.descent - metrics.ascent).toDouble()).toFloat()
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TerminalTopBar(
                title = title,
                subtitle = "${connection.tmuxSessionName} · ${statusLabel(status)}",
                onBack = onBack,
                onReleaseNotes = onReleaseNotes,
                onOpenHistory = { historyVisible = true },
                onLiveView = { historyVisible = false },
                onShowKeyboard = { focusRequester.requestFocus(); keyboard?.show() },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(TerminalBackground)
                .clipToBounds()
                .pointerInput(onBack) { detectPassiveDoubleTap(onBack) },
        ) {
            val gridHeightPx = if (rows > 0 && lineHeightPx > 0) (rows * lineHeightPx).toInt() else 0
            val viewportPx = with(density) { maxHeight.toPx() }.toInt()
            val panPx = if (gridHeightPx in 1..viewportPx) {
                viewportPx - gridHeightPx
            } else {
                0
            }
            val charWidthPx = remember(fontSize, terminalTypeface, density) {
                Paint().apply {
                    typeface = terminalTypeface
                    textSize = with(density) { fontSize.toPx() }
                }.measureText("M")
            }
            ShellContent(
                historyVisible = historyVisible,
                onOpenHistory = { historyVisible = true },
                onJumpToLive = { historyVisible = false },
                loadHistory = connection::history,
                fontSize = fontSize,
                onFontSizeChange = onFontSizeChange,
                contentWidth = with(density) { ((columns.takeIf { it > 0 } ?: FALLBACK_COLUMNS) * charWidthPx).toDp() },
                onLiveTap = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
            ) { liveModifier ->
                Terminal(
                    terminalEmulator = connection.emulator,
                    modifier = liveModifier
                        .fillMaxSize()
                        .offset { IntOffset(0, panPx - keyboardHeightPx) },
                    initialFontSize = fontSize,
                    minFontSize = fontSize,
                    maxFontSize = fontSize,
                    forcedSize = if (rows > 0 && columns > 0) rows to columns else null,
                    focusRequester = focusRequester,
                    onTerminalTap = {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                    typeface = terminalTypeface,
                    backgroundColor = TerminalBackground,
                    foregroundColor = TerminalForeground,
                    keyboardEnabled = true,
                    showSoftKeyboard = true,
                    modifierManager = inputTracker,
                    onPasteRequest = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text ->
                            if (text.isNotEmpty()) onSessionInput()
                            connection.paste(text)
                        }
                    },
                )
            }
            if (status is TerminalStatus.Connecting) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            if (status is TerminalStatus.Failed) {
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((status as TerminalStatus.Failed).message, color = MaterialTheme.colorScheme.error)
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
        Button(onClick = action) { Text("Host mapping") }
    }
}

@Composable
private fun InlineError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DismissibleError(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss error", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun UpdatingSessionsNotice(hostLabels: List<String>) {
    Text(
        text = if (hostLabels.isEmpty()) "Updating sessions…" else "Updating · ${hostLabels.joinToString(", ")}",
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CachedSessionsNotice(
    hostLabels: List<String>,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val message = when {
            hostLabels.size == 1 -> "Cached · ${hostLabels.first()} unreachable"
            hostLabels.isEmpty() -> "Cached · your VPS is unreachable"
            else -> "Cached · ${hostLabels.joinToString(", ")} unreachable"
        }
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry connection", modifier = Modifier.size(16.dp))
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

internal fun Modifier.horizontalSwipe(
    onSwipe: (Int) -> Unit,
    eventPass: PointerEventPass = PointerEventPass.Initial,
    suppressRight: () -> Boolean = { false },
) = pointerInput(eventPass, suppressRight) {
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
                if (!claimed && !change.isConsumed &&
                    kotlin.math.abs(totalX) > threshold &&
                    kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 2 &&
                    (totalX < 0 || !suppressRight())
                ) {
                    claimed = true
                    onSwipe(if (totalX < 0) 1 else -1)
                }
                if (claimed) change.consume()
            }
        }
    }
}

private const val FALLBACK_COLUMNS = 100

private fun joinRemotePath(parent: String, name: String): String = when {
    parent.isBlank() || parent == "/" -> "/$name"
    else -> "${parent.trimEnd('/')}/$name"
}

private fun parentRemotePath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isBlank() || trimmed == "/") return "/"
    return trimmed.substringBeforeLast('/', "").ifBlank { "/" }
}
private const val DOUBLE_TAP_TIMEOUT_MS = 320L
private const val UPDATE_POLL_INTERVAL_MS = 5 * 60 * 1000L

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

internal fun terminalLinks(
    value: String,
    sunlight: Boolean,
    onDark: Boolean = false,
    linkColor: Color = if (onDark) LinkOnDark else terminalBlue(sunlight),
): AnnotatedString {
    val builder = AnnotatedString.Builder(value)
    value.lineSequence().fold(0) { offset, line ->
        val trimmed = line.trimStart()
        val color = when {
            TERMINAL_ERROR.containsMatchIn(trimmed) -> terminalRed(sunlight)
            TERMINAL_WARNING.containsMatchIn(trimmed) -> terminalYellow(sunlight)
            TERMINAL_SUCCESS.containsMatchIn(trimmed) -> terminalGreen(sunlight)
            TERMINAL_PROMPT.containsMatchIn(trimmed) -> if (onDark) LinkOnDark else terminalBlue(sunlight)
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
                            color = linkColor,
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

private suspend fun PointerInputScope.detectPassiveDoubleTap(onDoubleTap: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        waitForUpOrCancellation() ?: return@awaitEachGesture
        withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        waitForUpOrCancellation() ?: return@awaitEachGesture
        onDoubleTap()
    }
}

private enum class ComposerMenuPage { MAIN, MODEL, EFFORT }

@Composable
private fun NativeSkillPicker(
    skills: List<NativeSkill>,
    loading: Boolean,
    error: String?,
    onSelect: (NativeSkill) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ReaderMenuShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(ReaderOutlineWidth, ReaderOutline),
    ) {
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            when {
                loading -> DropdownMenuItem(text = { Text("Loading session skills…") }, enabled = false, onClick = {})
                error != null -> DropdownMenuItem(text = { Text(error) }, enabled = false, onClick = {})
                skills.isEmpty() -> DropdownMenuItem(text = { Text("No matching session skills") }, enabled = false, onClick = {})
                else -> skills.forEach { skill ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("/${skill.name}", style = MaterialTheme.typography.bodyMedium)
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        onClick = { onSelect(skill) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentHistoryDialog(connection: SessionReaderConnection, onDismiss: () -> Unit) {
    val pages by connection.historyPages.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun loadOlder() {
        if (loading || exhausted) return
        loading = true
        scope.launch {
            val got = connection.loadOlderHistoryPage()
            if (!got) exhausted = true
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session history") },
        text = {
            Column {
                if (pages.isEmpty()) {
                    Text("Capturing…", style = MaterialTheme.typography.bodySmall)
                } else if (exhausted) {
                    Text("No older history available.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(pages.asReversed()) { _, page ->
                        SelectionContainer {
                            Text(
                                page,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    item {
                        TextButton(onClick = ::loadOlder, enabled = !loading && !exhausted) {
                            Text(if (loading) "Loading…" else "Load older")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Back to live") }
        },
    )
}
