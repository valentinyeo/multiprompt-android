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
import dev.multiprompt.companion.reader.ChatMention
import dev.multiprompt.companion.reader.MentionChat
import androidx.compose.foundation.layout.ColumnScope
import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.withLink
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
import androidx.compose.ui.unit.Dp
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
import dev.multiprompt.companion.computer.ComputerState
import dev.multiprompt.companion.computer.ComputerStatus
import dev.multiprompt.companion.computer.ComputerStore
import dev.multiprompt.companion.computer.ComputerTarget
import dev.multiprompt.companion.computer.ComputerViewer
import dev.multiprompt.companion.computer.ComputerViewerPage
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
import dev.multiprompt.companion.reader.HostChange
import dev.multiprompt.companion.reader.MessageActionResult
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.skills.NativeSkill
import dev.multiprompt.companion.skills.NativeSkills
import dev.multiprompt.companion.data.ReminderTimeParser
import dev.multiprompt.companion.data.UnreadCount
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.SessionSearch
import dev.multiprompt.companion.ssh.HostKeyWarning
import dev.multiprompt.companion.ssh.SshProblem
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
            dictation = viewModel.dictation,
            dictationKey = state.terminalSession?.let { SessionReadStore.key(it.hostId, it.name) }.orEmpty(),

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
            hostLabel = state.hosts.firstOrNull { it.id == readerSession.hostId }?.label ?: readerSession.hostId,
            mentionChats = ChatMention.chats(state.sessions, readerSession),
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
            onBotAppearance = viewModel::setAccountBotAppearance,
            onReattach = { viewModel.reattachReader(readerSession) },
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
        state.etaAccuracy?.let { rows ->
            EstimateAccuracySheet(
                rows, state.etaAccuracyLoading, state.etaAccuracyError, viewModel::dismissEtaAccuracy,
            )
        }
        return
    }

    val newSession = state.newSession
    if (newSession != null && newSession.step == NewSessionStep.PATH) {
        MultipromptTheme(sunlight = sunlight, readerStyle = true) { NewSessionFolderScreen(
            sunlight = sunlight,
            picker = newSession,
            hosts = state.hosts,
            onBrowse = viewModel::browseNewSessionDirectory,
            onChoose = viewModel::chooseNewSessionFolder,
            onModeChange = viewModel::setNewSessionMode,
            onClose = viewModel::closeNewSession,
        ) }
        return
    }
    if (newSession != null && newSession.step == NewSessionStep.EXISTING) {
        MultipromptTheme(sunlight = sunlight, readerStyle = true) { ExistingSessionScreen(
            sunlight = sunlight,
            picker = newSession,
            hosts = state.hosts,
            archivedKeys = state.archivedSessionKeys,
            onModeChange = viewModel::setNewSessionMode,
            onNeedsAttentionChange = viewModel::setNewSessionNeedsAttentionOnly,
            onOpen = viewModel::openExistingSession,
            onClose = viewModel::closeNewSession,
        ) }
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
                    dictation = viewModel.dictation,
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
                    onRefreshRooms = viewModel::refreshRooms,
                    onSelectSection = viewModel::select,
                    onReleaseNotes = viewModel::openReleaseNotes,
                    onOpenRoom = viewModel::openRoom,
                    onCloseRoom = viewModel::closeRoom,
                    onLoadOlderRoomEvents = viewModel::loadOlderRoomEvents,
                    onSendRoomMessage = viewModel::sendRoomMessage,
                    onForgetSession = viewModel::forgetSession,
                    onStartNewSession = viewModel::startNewSession,
                    onSetNewestSessionsAtBottom = viewModel::setNewestSessionsAtBottom,
                    onSetAllSplitOnRight = viewModel::setAllSplitOnRight,
                    onSetSessionListSetting = viewModel::setSessionListSetting,
                    onSetWorkspaceColor = viewModel::setAccountWorkspaceColor,
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelSmall)
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
                KitMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    listOf(
                        AppSection.SESSIONS to "Sessions",
                        AppSection.UPDATE to "Update",
                    ).forEach { (destination, label) ->
                        KitMenuRow(
                            label = if (section == destination) "✓ $label" else label,
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

internal val PickerControlHeight = ReaderControlSize

@Composable
private fun pickerSelectedFill(sunlight: Boolean): Color =
    if (sunlight) MaterialTheme.colorScheme.surfaceContainerHigh else ReaderSelected

@Composable
private fun Modifier.pickerSurface(shape: androidx.compose.ui.graphics.Shape = CircleShape): Modifier =
    background(MaterialTheme.colorScheme.surfaceContainer, shape)
        .border(ReaderOutlineWidth, MaterialTheme.colorScheme.outline, shape)
        .clip(shape)

@Composable
private fun PickerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    placeholder: String,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(PickerControlHeight)
                .pickerSurface()
                .clickable(role = Role.Button, onClick = onClose)
                .testTag("picker-back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp), tint = colors.onSurface)
        }
        Row(
            Modifier
                .weight(1f)
                .height(PickerControlHeight)
                .testTag("picker-search")
                .pickerSurface()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(placeholder, fontSize = 16.sp, color = colors.onSurfaceVariant, maxLines = 1)
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PickerModeRow(mode: NewSessionMode, sunlight: Boolean, onModeChange: (NewSessionMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selectedFill = pickerSelectedFill(sunlight)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(PickerControlHeight)
            .testTag("picker-mode")
            .pickerSurface()
            .padding(3.dp),
    ) {
        listOf(NewSessionMode.NEW to "New session", NewSessionMode.EXISTING to "Existing").forEach { (value, label) ->
            val isSelected = value == mode
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (isSelected) selectedFill else Color.Transparent, CircleShape)
                    .selectable(isSelected, role = Role.Tab) { if (!isSelected) onModeChange(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PickerToggle(checked: Boolean, sunlight: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .width(52.dp)
            .height(32.dp)
            .background(if (checked) pickerSelectedFill(sunlight) else colors.surfaceContainer, CircleShape)
            .border(ReaderOutlineWidth, colors.outline, CircleShape)
            .clip(CircleShape)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (checked) colors.onSurface else colors.onSurfaceVariant, CircleShape))
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun pickerEntrySubtitle(isRepo: Boolean, uses: Int): String =
    listOfNotNull(if (isRepo) "git" else null, if (uses > 0) "used ${uses}×" else null).joinToString(" · ")

@Composable
private fun ExistingSessionScreen(
    sunlight: Boolean,
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
        PickerModeRow(mode = picker.mode, sunlight = sunlight, onModeChange = onModeChange)
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
            Text("Needs attention", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            PickerToggle(
                checked = picker.needsAttentionOnly,
                sunlight = sunlight,
                onCheckedChange = onNeedsAttentionChange,
            )
        }
        if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, trackColor = MaterialTheme.colorScheme.surfaceContainer)
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
                PickerRow(
                    title = session.displayName,
                    subtitle = listOf(
                        session.workingDirectory.ifBlank { session.name },
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
                    ).joinToString(" · "),
                    onClick = { onOpen(session) },
                    leading = { AgentIcon(session.agent, Modifier.size(20.dp)) },
                )
            }
        }
    }
}

@Composable
internal fun NewSessionFolderScreen(
    sunlight: Boolean,
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
        PickerModeRow(mode = picker.mode, sunlight = sunlight, onModeChange = onModeChange)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$hostLabel · ${picker.path.ifBlank { "…" }}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onChoose(picker.path) },
                enabled = picker.path.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Use this folder", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, trackColor = MaterialTheme.colorScheme.surfaceContainer)
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
                    PickerRow(title = "..", subtitle = "", onClick = { onBrowse(parentRemotePath(picker.path)) })
                }
            }
            if (query.startsWith("/")) {
                val typed = query.trim()
                if (typed.length > 1) {
                    item {
                        PickerRow(title = "Go to $typed", subtitle = "", onClick = { onBrowse(typed) })
                    }
                }
            }
            items(ranked, key = { it.name }) { entry ->
                val full = joinRemotePath(picker.path, entry.name)
                PickerRow(
                    title = entry.name,
                    subtitle = pickerEntrySubtitle(entry.isRepo, entry.uses),
                    onClick = { if (entry.isRepo) onChoose(full) else onBrowse(full) },
                )
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
    onRefreshRooms: () -> Unit = {},
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
    onOpenRoom: (String) -> Unit = {},
    onCloseRoom: () -> Unit = {},
    onLoadOlderRoomEvents: () -> Unit = {},
    onSendRoomMessage: (String) -> Unit = {},
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
    onSetWorkspaceColor: (String, Int?) -> Unit = { _, _ -> },
    dictation: DeepgramDictation? = null,
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
    var moveSessionTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var endPromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var renamePromptSession by remember { mutableStateOf<TmuxSession?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var bucketMenuExpanded by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf(SettingsPage.HIDDEN) }
    var deepgramKeyOpen by remember { mutableStateOf(false) }
    var crashReportVisible by remember { mutableStateOf(false) }
    var openRoom by remember { mutableStateOf<RoomSummary?>(null) }
    var selectingBots by remember { mutableStateOf(false) }
    val chosenBots = remember { mutableStateListOf<String>() }
    var moveSelectionOpen by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { onRefreshRooms() }
    LaunchedEffect(state.allSplitOnRight, state.workspaceSplitIds) {
        snapshotFlow { splitScrollState.maxValue }.collect { maximum ->
            splitScrollState.scrollTo(if (state.allSplitOnRight) maximum else 0)
        }
    }
    renamePromptSession?.let { session ->
        KitSheet(onDismiss = {
            renamePromptSession = null
            renameError = null
        }) {
            Text("Rename session", style = Kit.Heading, color = Kit.Text)
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
            KitSecondaryButton("Cancel", onClick = {
                renamePromptSession = null
                renameError = null
            }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Rename", onClick = {
                renameError = onRenameSession(session, renameDraft)
                if (renameError == null) renamePromptSession = null
            }, modifier = Modifier.fillMaxWidth())
        }
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
                    verticalArrangement = Arrangement.spacedBy(Kit.ControlGap),
                ) {
                    KitListGroup {
                        KitListRow(if (signedIn) "Switch / renew login" else "Sign in", onClick = onSignIn)
                        if (signedIn) KitListRow("Sign out", destructive = true, onClick = onSignOut)
                        KitListRow("Host mapping", onClick = { settingsPage = SettingsPage.MAPPING })
                        if (dictation != null) {
                            val dictationConfigured = dictation.state.collectAsState().value.configured
                            KitListRow(
                                "Dictation key",
                                trailing = { Text(if (dictationConfigured) "Set" else "Not set", style = Kit.Body, color = Kit.TextMuted) },
                                onClick = { deepgramKeyOpen = true },
                            )
                            if (deepgramKeyOpen) {
                                DeepgramKeySheet(
                                    dictation = dictation,
                                    configured = dictationConfigured,
                                    saveLabel = "Save key",
                                    onDismiss = { deepgramKeyOpen = false },
                                    onSaved = { deepgramKeyOpen = false },
                                )
                            }
                        }
                        KitListRow("Update", onClick = { settingsPage = SettingsPage.HIDDEN; onSelectSection(AppSection.UPDATE) })
                    }
                    state.accountError?.let { error ->
                        KitListGroup { Text(error, Modifier.padding(Kit.SideChange), style = Kit.Body, color = Kit.Danger) }
                    }
                    KitListGroup {
                        listOf(
                            Triple("frequent", "Show Frequent split", state.showFrequentSplit),
                            Triple("frequent_first", "Frequent as first tab", state.frequentFirst),
                            Triple("favorites", "Show Favorites split", state.showFavoritesSplit),
                            Triple("all", "Show All split", state.showAllSplit),
                        ).forEach { (key, label, checked) ->
                            val enabled = key != "frequent_first" || state.showFrequentSplit
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = Kit.RowHeight).padding(horizontal = Kit.SideChange, vertical = Kit.ControlGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Kit.ScreenEdge),
                            ) {
                                Text(label, Modifier.weight(1f), style = Kit.Body, color = Kit.Text)
                                KitToggle(
                                    checked = checked,
                                    onCheckedChange = if (enabled) { value -> onSetSessionListSetting(key, value) } else null,
                                    modifier = Modifier.semantics {
                                        contentDescription = label
                                        if (!enabled) {
                                            role = Role.Switch
                                            toggleableState = ToggleableState(checked)
                                            disabled()
                                        }
                                    },
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Kit.RowHeight).padding(horizontal = Kit.SideChange, vertical = Kit.ControlGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Kit.ScreenEdge),
                        ) {
                            Text("Newest sessions at bottom", Modifier.weight(1f), style = Kit.Body, color = Kit.Text)
                            KitToggle(state.newestSessionsAtBottom, onSetNewestSessionsAtBottom)
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Kit.RowHeight).padding(horizontal = Kit.SideChange, vertical = Kit.ControlGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Kit.ScreenEdge),
                        ) {
                            Text("All split on right", Modifier.weight(1f), style = Kit.Body, color = Kit.Text)
                            KitToggle(state.allSplitOnRight, onSetAllSplitOnRight)
                        }
                    }
                    if (state.workspaces.isNotEmpty()) {
                        KitListGroup {
                            Text(
                                "Workspace colors",
                                Modifier.padding(horizontal = Kit.SideChange, vertical = 8.dp),
                                style = Kit.Label,
                                color = Kit.TextMuted,
                            )
                            state.workspaces.forEach { workspace ->
                                val picked = state.accountLayout?.workspaceColors?.get(workspace.name)
                                val shown = state.accountLayout?.workspaceColor(workspace.name) ?: 0
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = Kit.SideChange, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(workspace.name, style = Kit.Body, color = Kit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        (0 until BOT_COLORS).forEach { index ->
                                            val chosen = if (picked == null) shown == index else picked == index
                                            Box(
                                                Modifier
                                                    .size(28.dp)
                                                    .background(botColor(index), CircleShape)
                                                    .border(if (chosen) 3.dp else 1.dp, if (chosen) Kit.Text else Kit.Outline, CircleShape)
                                                    .clickable { onSetWorkspaceColor(workspace.name, index) },
                                            )
                                        }
                                        Text(
                                            "Auto",
                                            modifier = Modifier.clickable { onSetWorkspaceColor(workspace.name, null) }.padding(start = 4.dp),
                                            style = Kit.Label,
                                            color = if (picked == null) Kit.Text else Kit.TextMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    KitListGroup {
                        Column(Modifier.padding(vertical = Kit.SideChange), verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
                            Text("Theme", Modifier.padding(horizontal = Kit.SideChange), style = Kit.Body, color = Kit.Text)
                            KitSegmented(
                                listOf(AppTheme.SYSTEM to "System", AppTheme.DARK to "Dark", AppTheme.SUNLIGHT to "Sunlight"),
                                appTheme,
                                onSetAppTheme,
                            )
                            Text("Default Reader font size", Modifier.padding(horizontal = Kit.SideChange), style = Kit.Body, color = Kit.Text)
                            val fontOptions = listOf(0.9f to "Small", 1f to "Normal", 1.15f to "Large")
                            KitSegmented(
                                fontOptions,
                                fontOptions.firstOrNull { kotlin.math.abs(readerDefaultFontScale - it.first) < 0.01f }?.first
                                    ?: readerDefaultFontScale,
                                onSetReaderDefaultFontScale,
                            )
                        }
                    }
                    crashReport?.let {
                        KitListGroup {
                            Column(Modifier.padding(Kit.SideChange), verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
                                Text(
                                    "A crash report from the previous launch is available.",
                                    color = Kit.TextMuted,
                                    style = Kit.Hint,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
                                    KitSecondaryButton("View report", onClick = { crashReportVisible = true })
                                    KitSecondaryButton("Dismiss", onClick = onClearCrashReport)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (selectingBots) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${chosenBots.size} selected",
                modifier = Modifier.weight(1f),
                style = Kit.BodyStrong,
                color = Kit.Text,
            )
            KitSecondaryButton("Cancel", onClick = {
                selectingBots = false
                chosenBots.clear()
            })
            KitPrimaryButton("Move to…", onClick = { moveSelectionOpen = true }, enabled = chosenBots.isNotEmpty())
        }
    }
    if (moveSelectionOpen) {
        KitSheet(onDismiss = { moveSelectionOpen = false }) {
            Text("Move to", style = Kit.Heading, color = Kit.Text)
            state.workspaces.forEach { workspace ->
                KitMenuRow(
                    label = workspace.name,
                    onClick = {
                        val moving = state.inboxSessions.filter { SessionReadStore.key(it.hostId, it.name) in chosenBots }
                        moving.forEach { onMoveSession(it, workspace) }
                        moveSelectionOpen = false
                        selectingBots = false
                        chosenBots.clear()
                    },
                )
            }
            KitSecondaryButton("Cancel", onClick = { moveSelectionOpen = false }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (crashReportVisible) {
        crashReport?.let { report ->
            val displayName = stringResource(R.string.app_name)
            val reportText = report.asText(displayName)
            KitSheet(onDismiss = { crashReportVisible = false }) {
                Text("Crash report", style = Kit.BodyStrong, color = Kit.Text)
                SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        reportText,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        style = Kit.Body.copy(fontSize = 11.sp),
                        color = Kit.Text,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
                    KitSecondaryButton("Copy report", onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("$displayName crash report", reportText))
                        crashReportVisible = false
                    })
                    KitSecondaryButton("Close", onClick = { crashReportVisible = false })
                }
            }
        }
    }
    if (settingsPage == SettingsPage.MAPPING && state.pendingHostKeys.isEmpty()) {
        HostMappingDialog(state, onMapAccountHost, onDismiss = { settingsPage = SettingsPage.PREFERENCES })
    }
    if (settingsPage != SettingsPage.HIDDEN) return@MultipromptTheme
    openRoom?.let { room ->
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                RoomScreen(
                    room = room,
                    lines = roomLines(state.roomEvents, room.members),
                    loading = state.roomEventsLoading,
                    exhausted = state.roomEventsExhausted,
                    error = state.roomEventsError,
                    onLoadOlder = onLoadOlderRoomEvents,
                    onSend = onSendRoomMessage,
                    sending = state.roomSending,
                    sent = state.roomSent,
                    onBack = {
                        openRoom = null
                        onCloseRoom()
                    },
                )
            }
        }
        return@MultipromptTheme
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
    moveSessionTarget?.let { session ->
        KitSheet(onDismiss = { moveSessionTarget = null }) {
            Text("Move to", style = Kit.Heading, color = Kit.Text)
            state.workspaces.forEach { workspace ->
                KitMenuRow(
                    label = workspace.name,
                    onClick = {
                        moveSessionTarget = null
                        onMoveSession(session, workspace)
                    },
                )
            }
            KitSecondaryButton("Cancel", onClick = { moveSessionTarget = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    endPromptSession?.let { session ->
        DestructiveConfirmationSheet(
            title = "End ${session.displayName} completely?",
            warning = "This kills the coding agent and tmux session without saving a restore record.",
            confirmLabel = "End session",
            onDismiss = { endPromptSession = null },
            onConfirm = {
                endPromptSession = null
                onEnd(session)
            },
        )
    }
    if (splitOrderDialogVisible) {
        KitSheet(onDismiss = { splitOrderDialogVisible = false }) {
            Text("Arrange splits", style = Kit.Heading, color = Kit.Text)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (state.allSplitOnRight) {
                        "Automatic order keeps All on the right, beside the most recently active splits."
                    } else {
                        "Automatic order keeps All on the left, then the most recently active splits."
                    },
                    style = Kit.Hint,
                    color = Kit.TextMuted,
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
                        Text(splitName, style = Kit.Body, color = Kit.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "$sessionCount",
                            style = Kit.Label,
                            color = Kit.TextMuted,
                        )
                        IconButton(
                            onClick = {
                                onMoveWorkspaceSplit(splitId, if (state.allSplitOnRight) 1 else -1)
                            },
                            enabled = index > 0,
                        ) { Text("↑", color = Kit.Text) }
                        IconButton(
                            onClick = {
                                onMoveWorkspaceSplit(splitId, if (state.allSplitOnRight) -1 else 1)
                            },
                            enabled = index < arrangedWorkspaceSplitIds.lastIndex,
                        ) { Text("↓", color = Kit.Text) }
                    }
                }
            }
            KitSecondaryButton("Automatic order", onClick = onResetWorkspaceSplitOrder, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Done", onClick = { splitOrderDialogVisible = false }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (workspaceDialogVisible) {
        KitSheet(onDismiss = { workspaceDialogVisible = false }) {
            Text("New workspace", style = Kit.Heading, color = Kit.Text)
            OutlinedTextField(
                workspaceName,
                { workspaceName = it; workspaceError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
            )
            workspaceError?.let { InlineError(it) }
            KitSecondaryButton("Cancel", onClick = { workspaceDialogVisible = false }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Create", onClick = {
                workspaceError = onCreateWorkspace(workspaceName)
                if (workspaceError == null) {
                    workspaceDialogVisible = false
                    workspaceName = ""
                }
            }, modifier = Modifier.fillMaxWidth())
        }
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
    val roomsContext = LocalContext.current.applicationContext
    val drafts = remember(roomsContext) { SessionReadStore(roomsContext) }
    val rooms = remember(state.rooms) {
        state.rooms.map { room ->
            RoomSummary(
                room.id,
                room.name,
                room.members.mapIndexed { index, member -> RoomMember(member.id, member.alias, member.displayName, index) },
            )
        }
    }
    val botLooks = remember(state.inboxSessions, visibleSessions, state.readerSession, state.accountLayout, state.botPhotoRevision) {
        (state.inboxSessions + visibleSessions).associate { session ->
            SessionReadStore.key(session.hostId, session.name) to BotLook(
                picture = drafts.shownBotPicture(session),
                face = drafts.botFace(session),
                color = drafts.botColor(session),
            )
        }
    }
    val roomStrip: @Composable () -> Unit = {
        rooms.forEach { room ->
            RoomRow(room) {
                openRoom = room
                onOpenRoom(room.id)
            }
        }
    }
    val reversedSessions = normalizedQuery.isBlank() && state.newestSessionsAtBottom
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(Modifier.fillMaxWidth()) {
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
                        KitMenu(
                            expanded = bucketMenuExpanded,
                            onDismissRequest = { bucketMenuExpanded = false },
                        ) {
                            listOf(
                                SessionBucket.OPEN to "Inbox",
                                SessionBucket.WAITING to "Waiting",
                                SessionBucket.ARCHIVE to "Archive",
                            ).forEach { (bucket, label) ->
                                KitMenuRow(
                                    label = label,
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
                        if (state.refreshing && state.sessions.isEmpty()) {
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
            }
        },
        bottomBar = {
            if (!LocalMaterial3.current) {
                FloatingBottomBar {
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
            reverseLayout = reversedSessions,
        ) {
            if (rooms.isNotEmpty()) {
                item(key = "rooms") { roomStrip() }
            }
            state.hostErrors.forEach { (hostId, error) ->
                if (hostId !in state.cachedHostIds && !SshProblem.isKeyRejected(error)) {
                    item(key = "error-$hostId") { InlineError(error) }
                }
            }
            SessionGroups.split(visibleSessions) { session ->
                state.accountLayout?.let { AccountHostMap.tabFor(session, it, state.accountHosts) }?.group
            }.forEach { group ->
                val heading: () -> Unit = { group.name?.let { name -> item(key = "group:$name") { KitSectionLabel(name) } } }
                if (!reversedSessions) heading()
                items(group.items, key = { "${it.hostId}:${it.name}" }) { session ->
                    val key = SessionReadStore.key(session.hostId, session.name)
                    val tab = state.accountLayout?.let { AccountHostMap.tabFor(session, it, state.accountHosts) }
                    val starred = tab?.favorite == true
                    CompositionLocalProvider(
                        LocalOpenSession provides state.readerSession?.let { SessionReadStore.key(it.hostId, it.name) },
                        LocalBotSelection provides if (selectingBots) BotSelection(chosenBots.toSet()) { session ->
                            val key = SessionReadStore.key(session.hostId, session.name)
                            if (key in chosenBots) chosenBots.remove(key) else chosenBots.add(key)
                        } else null,
                        LocalBotLook provides botLooks[key],
                        LocalStartSelection provides { session ->
                            selectingBots = true
                            chosenBots.clear()
                            chosenBots.add(SessionReadStore.key(session.hostId, session.name))
                        },
                    ) {
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
                            onMove = { moveSessionTarget = session },
                            onRename = {
                                renameDraft = session.displayName
                                renameError = null
                                renamePromptSession = session
                            },
                            onDissolve = { onDissolve(session) },
                            onEnd = { endPromptSession = session },
                            onForget = { onForgetSession(session) },
                        )
                    }
                }
                if (reversedSessions) heading()
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
private fun DestructiveConfirmationSheet(
    title: String,
    warning: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    KitSheet(onDismiss = onDismiss) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Kit.ControlGap),
        ) {
            Text(title, style = Kit.Heading, color = Kit.Text)
            Text(warning, style = Kit.Body, color = Kit.Text)
            KitSecondaryButton("Cancel", onDismiss, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton(confirmLabel, onConfirm, modifier = Modifier.fillMaxWidth(), destructive = true)
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

    KitSheet(onDismiss = onDismiss) {
        Text("Remind me", style = Kit.Heading, color = Kit.Text)
        Text(
            session.displayName,
            style = Kit.Hint,
            color = Kit.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KitListGroup {
                presets.forEach { (label, dueAt) ->
                    KitListRow(label, onClick = { onRemind(dueAt.toEpochSecond()) })
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
        KitSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        KitPrimaryButton("Set reminder", onClick = submitCustom, modifier = Modifier.fillMaxWidth(), enabled = customTime.isNotBlank())
    }
}

@Composable
internal fun Modifier.roundOutlined(): Modifier = if (LocalReaderStyle.current) {
    minimumInteractiveComponentSize().size(ReaderControlSize)
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

private val LocalOpenSession = staticCompositionLocalOf<String?> { null }

private class BotSelection(val keys: Set<String>, val onToggle: (TmuxSession) -> Unit)

private class BotLook(val picture: String?, val face: Int?, val color: Int?)

private val LocalBotLook = staticCompositionLocalOf<BotLook?> { null }

private val LocalBotSelection = staticCompositionLocalOf<BotSelection?> { null }

private val LocalStartSelection = staticCompositionLocalOf<((TmuxSession) -> Unit)?> { null }

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
    onMove: () -> Unit,
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
        val selection = LocalBotSelection.current
        val rowKey = SessionReadStore.key(session.hostId, session.name)
        val chosen = selection?.keys?.contains(rowKey) == true
        Box(
            Modifier.fillMaxWidth().background(
                when {
                    chosen -> MaterialTheme.colorScheme.surfaceContainerHighest
                    LocalOpenSession.current == rowKey -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = if (selection != null) ({ selection.onToggle(session) }) else onClick,
                        onLongClickLabel = "Session actions",
                        onLongClick = { menuExpanded = true },
                    )
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotAvatar(
                    session.name,
                    workspaceColor = session.workspaceColor,
                    face = LocalBotLook.current?.face,
                    color = LocalBotLook.current?.color,
                    picture = LocalBotLook.current?.picture,
                )
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
                KitMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    onToggleFavorite?.let { toggle ->
                        KitMenuRow(
                            label = if (starred) "Unfavorite" else "Favorite",
                            onClick = {
                                menuExpanded = false
                                toggle()
                            },
                        )
                    }
                    if (!archived && workspaces.isNotEmpty()) {
                        KitMenuRow(
                            label = "Move to workspace",
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                        )
                    }
                    if (!unread && !archived) {
                        KitMenuRow(
                            label = "Mark as unread",
                            onClick = {
                                menuExpanded = false
                                onMarkUnread()
                            },
                        )
                    }
                    if (!archived && session.agent != dev.multiprompt.companion.model.AgentKind.OTHER) {
                        KitMenuRow(
                            label = "Rename",
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                    }
                    KitMenuRow(
                        label = "Hide from sidebar",
                        onClick = {
                            menuExpanded = false
                            onForget()
                        },
                    )
                    KitMenuRow(
                        label = if (archived) "Return to open" else "Waiting",
                        onClick = {
                            menuExpanded = false
                            onArchiveToggle()
                        },
                    )
                    if (!archived) {
                        KitMenuRow(
                            label = "Remind Me…",
                            onClick = {
                                menuExpanded = false
                                onRemind()
                            },
                        )
                    }
                    LocalStartSelection.current?.let { start ->
                        KitMenuRow(
                            label = "Select bots",
                            onClick = {
                                menuExpanded = false
                                start(session)
                            },
                        )
                    }
                    KitMenuRow(
                        label = "Pure terminal output",
                        onClick = {
                            menuExpanded = false
                            onOpenTerminal()
                        },
                    )
                    KitMenuRow(
                        label = "End tmux session",
                        onClick = {
                            menuExpanded = false
                            onEnd()
                        },
                    )
                    KitDivider()
                    KitMenuRow(
                        label = "Delete",
                        destructive = true,
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
    botFace: Int? = null,
    botColor: Int? = null,
    botPicture: String? = null,
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
                        Modifier.height(ReaderControlSize).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(session.name, 28.dp, workspaceColor = session.workspaceColor, face = botFace, color = botColor, picture = botPicture)
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
            detectTapGestures(onDoubleTap = { if (!session.liveChat) onOpenTerminal() })
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BotAvatar(session.name, 28.dp, workspaceColor = session.workspaceColor, face = botFace, color = botColor, picture = botPicture)
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
                    KitMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        KitMenuRow(
                            label = "Current: ${modelLabel ?: "Unknown"}",
                            enabled = false,
                            onClick = {},
                        )
                        KitMenuRow(
                            label = "Open model picker (/model)",
                            onClick = {
                                modelMenuExpanded = false
                                onModelCommand("/model")
                            },
                        )
                        if (session.agent == AgentKind.CLAUDE) {
                            KitMenuRow(
                                label = "Use Claude Opus",
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model opus")
                                },
                            )
                            KitMenuRow(
                                label = "Use Claude Sonnet",
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model sonnet")
                                },
                            )
                            KitMenuRow(
                                label = "Use Claude Fable",
                                onClick = {
                                    modelMenuExpanded = false
                                    onModelCommand("/model fable")
                                },
                            )
                            listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                KitMenuRow(
                                    label = "Set effort: $level",
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
    hostLabel: String = session.hostId,
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
    onBotAppearance: (String, Int?, Int?) -> Unit = { _, _, _ -> },
    onReattach: () -> Unit = {},
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
    mentionChats: List<MentionChat> = emptyList(),
) = key(connection) {
    MultipromptTheme(sunlight = sunlightMode, material3 = material3, readerStyle = true) {
    val reader by connection.state.collectAsState()
    val displayName = stringResource(R.string.app_name)
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
    val draftContext = LocalContext.current.applicationContext
    val draftStore = remember(draftContext) { SessionReadStore(draftContext) }
    var botLookOpen by remember(session.hostId, session.name) { mutableStateOf(false) }
    var pinnedPage by remember(session.hostId, session.name) { mutableStateOf(draftStore.pinnedPage(session)) }
    var pinPageOpen by remember(session.hostId, session.name) { mutableStateOf(false) }
    var pageOpen by remember(session.hostId, session.name) { mutableStateOf(false) }
    var promptField by remember(connection) {
        mutableStateOf(TextFieldValue(draftStore.composerDraft(session)))
    }
    LaunchedEffect(connection, session.hostId, session.name, promptField.text) {
        draftStore.setComposerDraft(session, promptField.text)
    }
    var replyTo by remember(connection, session.hostId, session.name) { mutableStateOf<String?>(null) }
    val replyGate = remember(connection, session.hostId, session.name) { ReplySwipeGate() }
    val promptFocusRequester = remember { FocusRequester() }
    val chooseReply: (String, Boolean) -> Unit = { text, _ ->
        replyTo = text
        promptFocusRequester.requestFocus()
    }
    val prompt = promptField.text
    val skillQuery = if (session.agent == AgentKind.CLAUDE && promptField.selection.collapsed) {
        NativeSkills.query(prompt, promptField.selection.start)
    } else null
    val setPrompt: (String) -> Unit = { value ->
        promptField = TextFieldValue(value, selection = TextRange(value.length))
    }
    var mentionPicks by remember(connection) { mutableStateOf(emptyList<MentionChat>()) }
    val mentionQuery = if (mentionChats.isNotEmpty() && skillQuery == null && promptField.selection.collapsed) {
        ChatMention.query(prompt, promptField.selection.start)
    } else null
    val mentionOptions = mentionQuery?.let { ChatMention.options(mentionChats, it.text) }.orEmpty()
    var dictationPrefix by remember(connection) { mutableStateOf("") }
    val dictationSubmissionStarted = remember(connection) { AtomicBoolean(false) }
    var pendingPromptAction by remember(connection) { mutableStateOf<Long?>(null) }
    var recordingPrompt by remember(connection) { mutableStateOf(false) }
    var sendMarkPending by remember(connection) { mutableStateOf(false) }
    var sentDraft by remember(connection) { mutableStateOf<SentDraft?>(null) }
    var menuExpanded by remember(connection) { mutableStateOf(false) }
    var menuPage by remember(connection) { mutableStateOf(ReaderMenuPage.MAIN) }
    var apiKeyDialogVisible by remember(connection) { mutableStateOf(false) }
    var microphoneError by remember(connection) { mutableStateOf<String?>(null) }
    var sendAfterDictation by remember(connection) { mutableStateOf(false) }
    var imageKeyDialogVisible by remember(connection) { mutableStateOf(false) }
    var imageKeyDraft by remember(connection) { mutableStateOf("") }
    var imageKeyError by remember(connection) { mutableStateOf<String?>(null) }
    var imageUploading by remember(connection) { mutableStateOf(false) }
    var imageUploadError by remember(connection) { mutableStateOf<String?>(null) }
    var imageAttachments by remember(connection) { mutableStateOf(emptyList<String>()) }
    var attachMenuExpanded by remember(connection) { mutableStateOf(false) }
    var attachMenuSection by remember(connection) { mutableStateOf<String?>(null) }
    val computerStore = remember(draftContext) { ComputerStore(draftContext) }
    var computerTarget by remember(session.hostId) { mutableStateOf(computerStore.target(session.hostId)) }
    var computerOpen by remember(session.hostId, session.name) {
        mutableStateOf(computerStore.showsComputer(session))
    }
    var computerState by remember(connection) { mutableStateOf(ComputerState()) }
    var computerTargetOpen by remember(session.hostId, session.name) { mutableStateOf(false) }
    val computerViewer = remember(connection) {
        ComputerViewer(
            opener = { target -> connection.openComputerTunnel(target) },
            publish = { computerState = it },
        )
    }
    DisposableEffect(computerViewer) { onDispose { computerViewer.close() } }
    LaunchedEffect(computerOpen, computerTarget) {
        if (computerOpen) computerViewer.wake(computerTarget) else computerViewer.sleep()
    }
    LaunchedEffect(reader.status, computerOpen) {
        if (!computerOpen) return@LaunchedEffect
        if (reader.status == ReaderStatus.Live) computerViewer.wake(computerTarget) else computerViewer.invalidate()
    }
    val computerHostContext = LocalContext.current
    val computerLifecycle = remember(computerHostContext) { computerHostContext.findLifecycle() }
    val computerScope = rememberCoroutineScope()
    DisposableEffect(computerLifecycle, computerOpen, computerTarget) {
        if (computerLifecycle == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> computerViewer.sleep()
                Lifecycle.Event.ON_START -> if (computerOpen) computerScope.launch { computerViewer.wake(computerTarget) }
                else -> Unit
            }
        }
        computerLifecycle.lifecycle.addObserver(observer)
        onDispose { computerLifecycle.lifecycle.removeObserver(observer) }
    }
    val setComputerOpen: (Boolean) -> Unit = { value ->
        computerOpen = value
        computerStore.setShowsComputer(session, value)
    }
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
    var speakableReplies by remember { mutableStateOf<List<dev.multiprompt.companion.reader.ReadAloud.Spoken>>(emptyList()) }
    var chatReady by remember(session.hostId, session.name) { mutableStateOf(false) }
    var waitingSend by remember(session.hostId, session.name) { mutableStateOf<String?>(null) }
    var replyCursor by remember { mutableStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var speakingKey by remember { mutableStateOf<String?>(null) }
    val speech = remember { android.speech.tts.TextToSpeech(context.applicationContext) { } }
    LaunchedEffect(session.hostId, session.name) {
        speech.stop()
        reading = false
        replyCursor = 0
    }
    DisposableEffect(speech) {
        onDispose {
            speech.stop()
            speech.shutdown()
        }
    }
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
    val botPicturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val folder = java.io.File(draftContext.filesDir, "bot-pictures").apply { mkdirs() }
                val target = java.io.File(folder, SessionReadStore.key(session.hostId, session.name).replace(Regex("[^A-Za-z0-9._-]"), "_"))
                draftContext.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                draftStore.setBotPicture(session, target.absolutePath)
            }.onFailure {
                android.widget.Toast.makeText(context, "Could not save that picture", android.widget.Toast.LENGTH_SHORT).show()
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
    val sendDraft: (String) -> Unit = { draftText ->
        val composedPrompt = PromptComposer.composeMessage(ChatMention.annotate(draftText, mentionPicks, mentionChats), imageAttachments, replyTo, reader.nativeSkills)
        if (composedPrompt.isNotBlank() &&
            !reader.sending &&
            pendingPromptAction == null && !recordingPrompt
        ) {
            val actionCount = reader.completedActions
            val draft = SentDraft(draftText, imageAttachments, replyTo)
            recordingPrompt = true
            readerScope.launch {
                when (val admission = connection.sendPrompt(composedPrompt)) {
                    is dev.multiprompt.companion.reader.PromptAdmission.Rejected -> {
                        val reason = admission.reason
                        if (reason == "Chat is disconnected" || (reason == "Chat is not ready" && !chatReady)) {
                            waitingSend = draftText
                            android.widget.Toast.makeText(context, "Will send when back online", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        SendTiming.mark("message_added")
                        if (admission is dev.multiprompt.companion.reader.PromptAdmission.LegacyEnqueued) {
                            sentDraft = draft
                            pendingPromptAction = actionCount
                        }
                        if (promptField.text == draft.prompt && imageAttachments == draft.images && replyTo == draft.replyTo) {
                            setPrompt("")
                            mentionPicks = emptyList()
                            imageAttachments = emptyList()
                            replyTo = null
                        }
                        sendMarkPending = true
                        onSessionInteraction()
                        onSessionInput()
                    }
                }
                recordingPrompt = false
            }
        }
    }
    LaunchedEffect(chatReady, waitingSend) {
        val text = waitingSend ?: return@LaunchedEffect
        if (!chatReady) return@LaunchedEffect
        delay(1_000)
        waitingSend = null
        sendDraft(text)
    }
    val sendModelCommand: (String) -> Unit = { command ->
        if (!reader.sending && pendingPromptAction == null) {
            if (connection.sendRuntimeCommand(command)) {
                onSessionInteraction()
                onSessionInput()
            } else {
                android.widget.Toast.makeText(context, "Could not send command", android.widget.Toast.LENGTH_SHORT).show()
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
                val renameTo = dev.multiprompt.companion.model.RenameCommand.parse(prompt)
                if (renameTo == null) {
                    sendDraft(prompt)
                } else {
                    val error = onRename(renameTo)
                    if (error == null) setPrompt("")
                    else android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                }
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
                sendDraft(finalPrompt)
            }
            DictationStatus.FAILED -> {
                dictationSubmissionStarted.set(false)
                sendAfterDictation = false
            }
            else -> Unit
        }
    }
    if (renameDialogVisible) {
        KitSheet(onDismiss = {
            renameDialogVisible = false
            renameError = null
        }) {
            Text("Rename session", style = Kit.Heading, color = Kit.Text)
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
            KitSecondaryButton("Cancel", onClick = {
                renameDialogVisible = false
                renameError = null
            }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Rename", onClick = {
                renameError = onRename(renameDraft)
                if (renameError == null) renameDialogVisible = false
            }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (endDialogVisible) {
        DestructiveConfirmationSheet(
            title = "End ${session.displayName} completely?",
            warning = "This kills the coding agent and tmux session without saving a restore record.",
            confirmLabel = "End session",
            onDismiss = { endDialogVisible = false },
            onConfirm = {
                endDialogVisible = false
                onEnd()
            },
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
        DeepgramKeySheet(
            dictation = dictation,
            configured = dictationState.configured,
            saveLabel = "Save and dictate",
            onDismiss = { apiKeyDialogVisible = false },
            onSaved = {
                apiKeyDialogVisible = false
                startDictation()
            },
        )
    }
    if (imageKeyDialogVisible) {
        KitSheet(onDismiss = { imageKeyDialogVisible = false }) {
            Text("Screencast2 uploads", style = Kit.Heading, color = Kit.Text)
            Text("The upload key is encrypted with Android Keystore and stays on this device.", style = Kit.Body, color = Kit.TextMuted)
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
            KitSecondaryButton("Cancel", onClick = { imageKeyDialogVisible = false }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Save and choose image", onClick = {
                if (screencast.saveSecret(imageKeyDraft)) {
                    imageKeyDialogVisible = false
                    imageKeyDraft = ""
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } else {
                    imageKeyError = "Enter a valid upload key"
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = imageKeyDraft.isNotBlank())
        }
    }
    if (computerTargetOpen) {
        var targetDraft by remember(session.hostId) { mutableStateOf(computerTarget.value) }
        var targetError by remember(session.hostId) { mutableStateOf<String?>(null) }
        KitSheet(onDismiss = { computerTargetOpen = false }) {
            Text("Viewer address", style = Kit.Heading, color = Kit.Text)
            Text(
                "Read on the server through this chat's own connection. Nothing opens to the internet.",
                style = Kit.Hint,
                color = Kit.TextMuted,
            )
            OutlinedTextField(
                value = targetDraft,
                onValueChange = { targetDraft = it; targetError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Address and port") },
                singleLine = true,
                isError = targetError != null,
                supportingText = targetError?.let { message -> { Text(message) } },
            )
            KitSecondaryButton("Cancel", onClick = { computerTargetOpen = false }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton(
                "Save address",
                onClick = {
                    val parsed = ComputerTarget.parse(targetDraft)
                    if (parsed == null) {
                        targetError = "Use an address like 127.0.0.1:6080"
                    } else {
                        computerStore.setTarget(session.hostId, parsed)
                        computerTarget = parsed
                        computerTargetOpen = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (pinPageOpen) {
        var pageDraft by remember(session.hostId, session.name) { mutableStateOf(pinnedPage.orEmpty()) }
        var pageError by remember(session.hostId, session.name) { mutableStateOf<String?>(null) }
        KitSheet(onDismiss = { pinPageOpen = false }) {
            Text("Pin a page", style = Kit.Heading, color = Kit.Text)
            Text("This chat keeps its own page. One tap opens it in your browser.", style = Kit.Hint, color = Kit.TextMuted)
            OutlinedTextField(
                value = pageDraft,
                onValueChange = { pageDraft = it; pageError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Page address") },
                singleLine = true,
                isError = pageError != null,
                supportingText = pageError?.let { message -> { Text(message) } },
            )
            KitSecondaryButton("Cancel", onClick = { pinPageOpen = false }, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton(
                "Pin page",
                onClick = {
                    val cleaned = pageDraft.trim()
                    if (!pinnablePage(cleaned)) {
                        pageError = "Use a full address that starts with https://"
                    } else {
                        draftStore.setPinnedPage(session, cleaned)
                        pinnedPage = cleaned
                        pinPageOpen = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (botLookOpen) {
        BotLookSheet(
            session = session,
            face = draftStore.botFace(session),
            color = draftStore.botColor(session),
            hasPicture = draftStore.botPicture(session) != null,
            onPickPicture = {
                botLookOpen = false
                botPicturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onClearPicture = {
                draftStore.botPicture(session)?.let { path -> runCatching { java.io.File(path).delete() } }
                draftStore.setBotPicture(session, null)
            },
            onPickFace = { face ->
                draftStore.setBotFace(session, face)
                onBotAppearance(session.hostId + ":" + session.name.lowercase(), face, draftStore.botColor(session))
            },
            onPickColor = { color ->
                draftStore.setBotColor(session, color)
                onBotAppearance(session.hostId + ":" + session.name.lowercase(), draftStore.botFace(session), color)
            },
            onDismiss = { botLookOpen = false },
        )
    }

    if (historyDialogOpen) {
        SessionHistorySheet(connection.historyPages, connection::loadOlderHistoryPage) {
            historyDialogOpen = false
            readerScope.launch { connection.exitHistory() }
        }
    }
    val statusLabel = listOfNotNull(
        reader.eta?.let { "ETA ${it.label(etaNowMillis)}" },
        "waiting for input".takeIf { reader.waitingForInput },
    ).joinToString(" · ").ifEmpty { null }
    BackHandler {
        when {
            pageOpen -> pageOpen = false
            computerOpen -> setComputerOpen(false)
            else -> onBack()
        }
    }
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
            if (!computerOpen) ReaderTopBar(
                onBack = onBack,
                botFace = draftStore.botFace(session),
                botColor = draftStore.botColor(session),
                botPicture = draftStore.shownBotPicture(session),
                session = session,
                modelLabel = runtimeDetails.label,
                statusLabel = statusLabel,
                onModelCommand = sendModelCommand,
                onOpenTerminal = onOpenTerminal,
                onReleaseNotes = onReleaseNotes,
                onMenuClick = { menuPage = ReaderMenuPage.MAIN; menuExpanded = true },
                menuContent = {
                    KitMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        contentResetKey = menuPage.name,
                    ) {
                        if (menuPage != ReaderMenuPage.MAIN) {
                            KitMenuRow(
                                label = "‹ Back",
                                onClick = { menuPage = ReaderMenuPage.MAIN },
                            )
                            KitDivider()
                        }
                        when (menuPage) {
                            ReaderMenuPage.MAIN -> {
                                KitMenuRow(
                                    label = listOfNotNull(runtimeDetails.label, statusLabel).joinToString(" · ").ifEmpty { "Model / effort" },
                                    enabled = false,
                                    onClick = {},
                                )
                                KitMenuRow(
                                    label = "Computer",
                                    onClick = {
                                        menuExpanded = false
                                        setComputerOpen(true)
                                    },
                                )
                                KitMenuRow(
                                    label = "Enter / approve",
                                    enabled = !reader.sending,
                                    onClick = {
                                        menuExpanded = false
                                        onSessionInteraction()
                                        onSessionInput()
                                        connection.sendEnter()
                                    },
                                )
                                KitMenuRow(
                                    label = "Interrupt session",
                                    enabled = !reader.sending,
                                    onClick = {
                                        menuExpanded = false
                                        onSessionInteraction()
                                        if (!connection.interruptHost()) connection.interrupt()
                                    },
                                )
                                if (reader.alternateOn) {
                                    KitMenuRow(
                                        label = "Session history",
                                        onClick = {
                                            menuExpanded = false
                                            onSessionInteraction()
                                            historyDialogOpen = true
                                            readerScope.launch { connection.enterHistory() }
                                        },
                                    )
                                }
                                KitMenuRow(
                                    label = "Open live terminal",
                                    onClick = {
                                        menuExpanded = false
                                        onOpenTerminal()
                                    },
                                )
                                KitDivider()
                                if (unread) {
                                    KitMenuRow(
                                        label = "Mark read",
                                        onClick = {
                                            menuExpanded = false
                                            onMarkRead()
                                        },
                                    )
                                }
                                if (session.agent != dev.multiprompt.companion.model.AgentKind.OTHER) {
                                    KitMenuRow(
                                        label = "Rename",
                                        onClick = {
                                            menuExpanded = false
                                            renameDraft = session.displayName
                                            renameError = null
                                            renameDialogVisible = true
                                        },
                                    )
                                }
                                KitMenuRow(
                                    label = "Remind Me…",
                                    onClick = { menuExpanded = false; reminderDialogVisible = true },
                                )
                                KitMenuRow(
                                    label = if (archived) "Return to open" else "Waiting",
                                    onClick = { menuExpanded = false; onArchiveToggle() },
                                )
                                KitMenuRow(
                                    label = "Delete",
                                    destructive = true,
                                    onClick = { menuExpanded = false; onDissolve() },
                                )
                                KitDivider()
                                KitMenuRow(
                                    label = "Model and effort ›",
                                    onClick = { menuPage = ReaderMenuPage.MODEL },
                                )
                                KitMenuRow(
                                    label = "Display ›",
                                    onClick = { menuPage = ReaderMenuPage.DISPLAY },
                                )
                                KitMenuRow(
                                    label = "More ›",
                                    onClick = { menuPage = ReaderMenuPage.MORE },
                                )
                                KitMenuRow(
                                    label = "Reset this chat",
                                    onClick = {
                                        menuExpanded = false
                                        val reset = if (session.agent == AgentKind.CLAUDE || session.agent == AgentKind.CODEX || session.agent == AgentKind.PI) connection.resetConversation() else null
                                        readerScope.launch {
                                            if (reset != null) {
                                                val result = reset.await()
                                                val message = when (result) {
                                                    dev.multiprompt.companion.reader.MessageActionResult.Applied -> "Chat reset"
                                                    dev.multiprompt.companion.reader.MessageActionResult.BlockedRecovery -> "Resolve unknown messages before reset"
                                                    dev.multiprompt.companion.reader.MessageActionResult.Unknown -> "Reset confirmation unknown"
                                                    is dev.multiprompt.companion.reader.MessageActionResult.Rejected -> result.reason
                                                }
                                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                val confirmed = connection.resyncChat()
                                                followRequest++
                                                android.widget.Toast.makeText(context, if (confirmed) "Synced" else "Some messages could not be confirmed", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                )
                                if (reader.status != dev.multiprompt.companion.reader.ReaderStatus.Live) {
                                    KitMenuRow(
                                        label = "Reattach session",
                                        onClick = {
                                            menuExpanded = false
                                            onReattach()
                                        },
                                    )
                                }
                                KitMenuRow(
                                    label = if (reading) "Stop reading" else "Read reply",
                                    onClick = {
                                        menuExpanded = false
                                        if (reading) {
                                            speech.stop()
                                            reading = false
                                            speakingKey = null
                                        } else {
                                            val next = dev.multiprompt.companion.reader.ReadAloud.next(speakableReplies, replyCursor)
                                            if (next != null) {
                                                speech.speak(next.first.text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "reply")
                                                speakingKey = next.first.key
                                                replyCursor = next.second
                                                reading = true
                                            } else {
                                                android.widget.Toast.makeText(context, "No reply to read yet", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                )
                                KitMenuRow(
                                    label = "Bot look",
                                    onClick = {
                                        menuExpanded = false
                                        botLookOpen = true
                                    },
                                )
                                KitMenuRow(
                                    label = if (pinnedPage == null) "Pin a page…" else "Change pinned page…",
                                    onClick = {
                                        menuExpanded = false
                                        pinPageOpen = true
                                    },
                                )
                                KitDivider()
                                KitMenuRow(
                                    label = "End tmux session",
                                    onClick = {
                                        menuExpanded = false
                                        endDialogVisible = true
                                    },
                                )
                                KitMenuRow(
                                    label = "Hide from sidebar",
                                    onClick = {
                                        menuExpanded = false
                                        onForget()
                                    },
                                )
                            }
                            ReaderMenuPage.MODEL -> {
                                KitMenuRow(
                                    label = "Current: ${runtimeDetails.label ?: "Unknown"}",
                                    enabled = false,
                                    onClick = {},
                                )
                                KitMenuRow(
                                    label = "Open model picker (/model)",
                                    onClick = { menuExpanded = false; sendModelCommand("/model") },
                                )
                                if (session.agent == AgentKind.CLAUDE) {
                                    listOf("Opus", "Sonnet", "Fable").forEach { model ->
                                        KitMenuRow(
                                            label = "Use Claude $model",
                                            onClick = { menuExpanded = false; sendModelCommand("/model ${model.lowercase()}") },
                                        )
                                    }
                                    KitDivider()
                                    listOf("low", "medium", "high", "xhigh", "max").forEach { level ->
                                        KitMenuRow(
                                            label = "Set effort: $level",
                                            onClick = { menuExpanded = false; sendModelCommand("/effort $level") },
                                        )
                                    }
                                }
                            }
                            ReaderMenuPage.DISPLAY -> {
                                KitMenuRow(
                                    label = if (technicalMode) "Hide terminal details" else "Show terminal details",
                                    onClick = {
                                        menuExpanded = false
                                        onTechnicalModeChanged(!technicalMode)
                                    },
                                )
                                KitMenuRow(
                                    label = if (sunlightMode) "Sunlight mode off" else "Sunlight mode on",
                                    onClick = {
                                        menuExpanded = false
                                        onSetAppTheme(if (sunlightMode) AppTheme.DARK else AppTheme.SUNLIGHT)
                                    },
                                )
                                KitMenuRow(
                                    label = "Use default font size",
                                    onClick = {
                                        menuExpanded = false
                                        transcriptZoom = onResetFontScale()
                                    },
                                )
                            }
                            ReaderMenuPage.MORE -> {
                                KitMenuRow(
                                    label = "Release notes",
                                    onClick = { menuExpanded = false; onReleaseNotes() },
                                )
                                KitMenuRow(
                                    label = "Estimate accuracy",
                                    onClick = {
                                        menuExpanded = false
                                        onShowEtaAccuracy()
                                    },
                                )
                                KitMenuRow(
                                    label = "Copy raw transcript (${reader.output.count { it == '\n' } + 1} lines · " +
                                        "alt ${if (reader.alternateOn) 1 else 0} · " +
                                        "view ${scrollState.value}/${scrollState.maxValue})",
                                    onClick = {
                                        menuExpanded = false
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("$displayName transcript", reader.output),
                                        )
                                    },
                                )
                                KitDivider()
                                AppMenuItems(
                                    signedIn = signedIn,
                                    onSignIn = onSignIn,
                                    onSignOut = onSignOut,
                                    onDismiss = { menuExpanded = false },
                                )
                                KitMenuRow(
                                    label = "Dictation API key",
                                    onClick = {
                                        menuExpanded = false
                                        apiKeyDialogVisible = true
                                    },
                                )
                                KitMenuRow(
                                    label = "Screencast2 upload key",
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
            FloatingBottomBar(
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
                if (mentionQuery != null && mentionOptions.isNotEmpty() && !dictationActive) {
                    ChatMentionPicker(
                        chats = mentionOptions,
                        onSelect = { chat ->
                            val (value, cursor) = ChatMention.pick(prompt, mentionQuery, promptField.selection.start, chat)
                            promptField = TextFieldValue(value, selection = TextRange(cursor))
                            mentionPicks = mentionPicks + chat
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
                pinnedPage?.let { page ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            pinnedPageLabel(page),
                            modifier = Modifier.weight(1f),
                            style = Kit.Hint,
                            color = Kit.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        KitSecondaryButton("Open", onClick = { pageOpen = true })
                        KitSecondaryButton("Remove", onClick = {
                            draftStore.setPinnedPage(session, null)
                            pinnedPage = null
                        }, destructive = true)
                    }
                }
                if (dictationActive) {
                    replyTo?.let { quoted ->
                        ReplyQuoteBar(message = quoted, sunlight = sunlightMode, onCancel = { replyTo = null })
                    }
                    DictationBar(
                        level = dictation.level,
                        sending = reader.sending || sendAfterDictation || pendingPromptAction != null || recordingPrompt,
                        onStop = { dictation.stop() },
                        onSend = submitPrompt,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
                    ) {
                        Box {
                            IconButton(
                                onClick = { attachMenuSection = null; attachMenuExpanded = true },
                                enabled = !reader.sending && !dictationActive && !imageUploading,
                                modifier = Modifier.minimumInteractiveComponentSize().size(ReaderControlSize)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                    .border(ReaderOutlineWidth, ReaderOutline, CircleShape),
                            ) {
                                if (imageUploading) CircularProgressIndicator(Modifier.size(ReaderBadgeSize), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Add, "Attach")
                            }
                            KitMenu(
                                expanded = attachMenuExpanded,
                                onDismissRequest = { attachMenuExpanded = false },
                                contentResetKey = attachMenuSection.orEmpty(),
                            ) {
                                val openSection = if (session.agent == AgentKind.CLAUDE) {
                                    HostChange.sections(connection.hostSettings() != null).firstOrNull { it.first == attachMenuSection }
                                } else null
                                if (openSection != null) {
                                    val hostSettings = connection.hostSettings()
                                    KitMenuRow(
                                        label = openSection.first,
                                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                                        onClick = { attachMenuSection = null },
                                    )
                                    KitDivider()
                                    openSection.second.forEach { change ->
                                        KitMenuRow(
                                            label = change.label,
                                            trailingText = if (change.active(hostSettings, runtimeDetails.model, runtimeDetails.effort)) "✓" else null,
                                            onClick = {
                                                attachMenuExpanded = false
                                                if (hostSettings == null) change.command?.let(sendModelCommand)
                                                else readerScope.launch {
                                                    val applied = connection.changeHost(change) == MessageActionResult.Applied
                                                    if (applied) onSessionInteraction()
                                                    android.widget.Toast.makeText(context, if (applied) change.notice else change.failure, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    KitMenuRow(
                                        label = "Add image",
                                        icon = Icons.Outlined.OutlinedImage,
                                        onClick = {
                                            attachMenuExpanded = false
                                            if (screencast.configured) imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            else imageKeyDialogVisible = true
                                        },
                                    )
                                    if (session.agent == AgentKind.CLAUDE) {
                                        KitMenuRow(
                                            label = "Skill",
                                            icon = Icons.Outlined.AutoAwesome,
                                            onClick = {
                                                attachMenuExpanded = false
                                                promptField = TextFieldValue(NativeSkills.open(prompt), selection = TextRange(1))
                                                promptFocusRequester.requestFocus()
                                            },
                                        )
                                        val hostSettings = connection.hostSettings()
                                        val sections = HostChange.sections(hostSettings != null)
                                        KitDivider()
                                        sections.forEach { (title, changes) ->
                                            val current = changes.firstOrNull { it.active(hostSettings, runtimeDetails.model, runtimeDetails.effort) }?.label
                                            KitMenuRow(
                                                label = title,
                                                trailingText = listOfNotNull(current, "›").joinToString(" "),
                                                onClick = { attachMenuSection = title },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val sendEnabled = !reader.sending &&
                            pendingPromptAction == null &&
                            !sendAfterDictation &&
                            (prompt.isNotBlank() || imageAttachments.isNotEmpty() || dictationActive)
                        val composerText = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                        Box(Modifier.weight(1f).padding(vertical = 2.dp)) {
                        BasicTextField(
                            value = promptField,
                            onValueChange = { promptField = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = ReaderControlSize)
                                .background(MaterialTheme.colorScheme.surfaceContainer, ReaderComposerFieldShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, ReaderComposerFieldShape).focusRequester(promptFocusRequester)
                                .drawWithContent {
                                    drawContent()
                                    if (sendMarkPending && prompt.isEmpty()) {
                                        SendTiming.mark("box_cleared_frame")
                                        sendMarkPending = false
                                    }
                                },
                            textStyle = composerText,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 1,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitPrompt() }),
                            readOnly = dictationActive,
                            decorationBox = { inner ->
                                Column {
                                replyTo?.let { quoted ->
                                    ReplyQuoteBar(
                                        message = quoted,
                                        sunlight = sunlightMode,
                                        onCancel = { replyTo = null },
                                        modifier = Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp),
                                    )
                                }
                                Row(
                                    Modifier.heightIn(min = ReaderControlSize).padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                        if (prompt.isEmpty()) {
                                            Text(
                                                "Message ${session.displayName}",
                                                style = composerText,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
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
                                    }
                                    Box(
                                        Modifier.size(ReaderSendSize)
                                            .clip(CircleShape)
                                            .background(if (sunlightMode) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFF353535))
                                            .clickable(enabled = sendEnabled, role = Role.Button, onClick = submitPrompt)
                                            .semantics { contentDescription = "Send prompt" },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (reader.sending || sendAfterDictation) {
                                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                null,
                                                Modifier.size(18.dp),
                                                tint = if (sendEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                }
                            },
                        )
                        }
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
        if (pageOpen && pinnedPage != null) {
            PagePane(
                title = pinnedPageLabel(pinnedPage!!),
                onBack = { pageOpen = false },
                onOpenInBrowser = { openPage(context, pinnedPage!!) },
                screen = { modifier -> PageScreen(pinnedPage!!, modifier) },
            )
        } else if (computerOpen) {
            ComputerPane(
                hostLabel = hostLabel,
                state = computerState,
                target = computerTarget,
                picture = computerStore.lastPicture(session),
                onBack = { setComputerOpen(false) },
                onWake = { readerScope.launch { computerViewer.wake(computerTarget) } },
                onSleep = { computerViewer.sleep() },
                onEditTarget = { computerTargetOpen = true },
                screen = { modifier, control ->
                    computerState.localPort?.takeIf { computerState.status == ComputerStatus.Live }?.let { port ->
                        ComputerScreen(
                            url = ComputerViewerPage.url(port),
                            control = control,
                            onPicture = { computerStore.saveLastPicture(session, it) },
                            modifier = modifier,
                            onUnreachable = { computerViewer.fail("Cannot reach the VPS") },
                        )
                    }
                },
            )
        } else if (historyChat) {
            ChatHistoryView(
                connection = connection,
                reader = reader,
                agent = session.agent,
                workingDirectory = session.workingDirectory,
                fontScale = transcriptZoom,
                sunlight = sunlightMode,
                replyGate = replyGate,
                onReply = chooseReply,
                onSpeakableReplies = { speakableReplies = it; if (it.isEmpty()) { reading = false; replyCursor = 0 } },
                onChatReady = { chatReady = it },
                focusKey = speakingKey,
                onReaction = { message, emoji ->
                    val line = dev.multiprompt.companion.reader.ChatReactions.format(emoji, message.requestId, message.text)
                    if (line != null) readerScope.launch {
                        when (connection.sendPrompt(line)) {
                            is dev.multiprompt.companion.reader.PromptAdmission.Rejected -> android.widget.Toast.makeText(context, "Could not send the reaction", android.widget.Toast.LENGTH_SHORT).show()
                            else -> { onSessionInteraction(); onSessionInput() }
                        }
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
                    .pointerInput(onOpenTerminal, session.liveChat) {
                        if (!session.liveChat) detectEarlyDoubleTap(onOpenTerminal)
                    },
            )
        } else Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(overscrollLoader)
                .readerScroll(scrollState, followRequest)
                .pointerInput(onOpenTerminal, session.liveChat) {
                    if (!session.liveChat) detectEarlyDoubleTap(onOpenTerminal)
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
                failure != null || reader.status == ReaderStatus.Connecting -> "Connecting"
                reader.sending -> "Live · Sending"
                reader.output.isBlank() -> "Live"
                waitingForInput -> "Live · Ready"
                else -> "Live · Working"
            }
            val connectionColor = when {
                failure != null || reader.status == ReaderStatus.Connecting -> MaterialTheme.colorScheme.surfaceVariant
                waitingForInput -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val connectionTextColor = when {
                failure != null || reader.status == ReaderStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        if (reader.status == ReaderStatus.Connecting || failure != null || reader.sending) {
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
                val batchStart = transcript?.let { messages -> lastBatchStart(messages.map { it.role }) }
                val batchLabel = batchStart?.let { start -> batchTimeLabel(transcript?.getOrNull(start)?.timestamp, System.currentTimeMillis()) }
                transcript?.forEachIndexed { index, message ->
                    val isUser = message.role == "user"
                    val bodyColor = chatTextColor(sunlightMode, isUser)
                    key(connection, message.role, message.timestamp, message.text) {
                        if (unreadMessageKey == "transcript:${message.role}:${message.timestamp}:${message.text.hashCode()}") Text("New", color = MaterialTheme.colorScheme.primary)
                        if (index == batchStart && batchLabel != null) BatchTimeLine(batchLabel, sunlightMode)
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
                            NoteLine(
                                line,
                                MaterialTheme.typography.bodySmall,
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

private fun android.content.Context.findLifecycle(): androidx.lifecycle.LifecycleOwner? {
    var context: android.content.Context? = this
    while (context != null) {
        if (context is androidx.lifecycle.LifecycleOwner) return context
        context = (context as? android.content.ContextWrapper)?.baseContext
    }
    return null
}

private fun pinnablePage(value: String): Boolean =
    value.length in 8..2048 && (value.startsWith("https://") || value.startsWith("http://")) &&
        value.none { it.isWhitespace() || it.code < 32 }

private fun pinnedPageLabel(value: String): String =
    value.substringAfter("://").removePrefix("www.").let { if (it.length <= 48) it else it.take(45) + "…" }

private fun openPage(context: android.content.Context, value: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(value))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open that page", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private val noteLink = Regex("(https?://\\S+)|\\b(IGSH-\\d+)\\b")

private fun ticketUrl(number: String) = "https://app.hypertask.ai/detail/project-2060/$number"

@Composable
private fun NoteLine(line: String, style: TextStyle, color: Color = Color.Unspecified) {
    val annotated = remember(line) {
        buildAnnotatedString {
            var last = 0
            noteLink.findAll(line).forEach { match ->
                append(line.substring(last, match.range.first))
                val url = match.groupValues[1].ifBlank { ticketUrl(match.groupValues[2].removePrefix("IGSH-")) }
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Kit.AccentUnread, textDecoration = TextDecoration.Underline)))) {
                    append(match.value)
                }
                last = match.range.last + 1
            }
            append(line.substring(last))
        }
    }
    Text(annotated, style = style, color = color)
}

@Composable
private fun ChangelogNotes(notes: String) {
    if (notes.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        notes.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            NoteLine(line, MaterialTheme.typography.bodySmall)
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

private fun imeRunning(manager: InputMethodManager?): Boolean {
    val running = manager ?: return false
    return runCatching { running.isAcceptingText }.getOrDefault(false)
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
    onDictate: () -> Unit = {},
    dictating: Boolean = false,
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
            IconButton(onClick = onDictate) {
                Icon(
                    if (dictating) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = if (dictating) "Stop dictation" else "Dictate into the shell",
                )
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
    dictation: DeepgramDictation,
    dictationKey: String,
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
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    val ime = remember(hostView) { hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
    val imeScope = rememberCoroutineScope()
    val openKeyboard: () -> Unit = {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        imeScope.launch {
            withFrameNanos { }
            ime?.showSoftInput(hostView, InputMethodManager.SHOW_IMPLICIT)
            delay(120)
            if (!imeRunning(ime)) ime?.showSoftInput(hostView, InputMethodManager.SHOW_FORCED)
        }
    }
    val sharedDictation by dictation.state.collectAsState()
    val dictationState = sharedDictation.takeIf { it.sessionKey == dictationKey } ?: DictationState(configured = sharedDictation.configured)
    val dictating = dictationState.status in setOf(DictationStatus.CONNECTING, DictationStatus.LISTENING, DictationStatus.FINISHING)
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) dictation.start(dictationKey) }
    val toggleDictation = {
        if (dictating) {
            dictation.stop()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            dictation.start(dictationKey)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(dictationState.status) {
        if (dictationState.status == DictationStatus.IDLE && dictationState.sessionKey == dictationKey) {
            val spoken = dictationState.transcript.trim()
            if (spoken.isNotBlank()) {
                connection.paste(spoken)
                currentOnSessionInput()
            }
        }
    }
    DisposableEffect(connection) { onDispose { if (dictation.state.value.sessionKey == dictationKey) dictation.discard() } }
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
                onShowKeyboard = openKeyboard,
                onDictate = toggleDictation,
                dictating = dictating,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .background(TerminalBackground)
                .clipToBounds()
                .pointerInput(onBack) { detectPassiveDoubleTap(onBack) },
        ) {
            val shellMetrics = remember(fontSize, terminalTypeface, density) {
                val paint = Paint().apply {
                    typeface = terminalTypeface
                    textSize = with(density) { fontSize.toPx() }
                }
                val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
                paint.measureText("M").coerceAtLeast(1f) to lineHeight
            }
            val grid = shellGrid(
                widthPx = with(density) { maxWidth.toPx() },
                heightPx = with(density) { maxHeight.toPx() },
                charWidthPx = shellMetrics.first,
                lineHeightPx = shellMetrics.second,
            )
            ShellContent(
                historyVisible = historyVisible,
                onOpenHistory = { historyVisible = true },
                onJumpToLive = { historyVisible = false },
                loadHistory = connection::history,
                fontSize = fontSize,
                onFontSizeChange = onFontSizeChange,
                contentWidth = maxWidth,
                onLiveTap = openKeyboard,
            ) { liveModifier ->
                Terminal(
                    terminalEmulator = connection.emulator,
                    modifier = liveModifier.fillMaxSize(),
                    initialFontSize = fontSize,
                    minFontSize = fontSize,
                    maxFontSize = fontSize,
                    forcedSize = grid.rows to grid.columns,
                    focusRequester = focusRequester,
                    onTerminalTap = openKeyboard,
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

private suspend fun PointerInputScope.detectEarlyDoubleTap(onDoubleTap: () -> Unit) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!awaitTapUp(first)) return@awaitEachGesture
        val second = withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        } ?: return@awaitEachGesture
        if ((second.position - first.position).getDistance() > viewConfiguration.touchSlop * 4) return@awaitEachGesture
        if (awaitTapUp(second)) onDoubleTap()
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitTapUp(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return false
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return false
        if (change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) return false
        if (!change.pressed) return true
    }
}

@Composable
private fun NativeSkillPicker(
    skills: List<NativeSkill>,
    loading: Boolean,
    error: String?,
    onSelect: (NativeSkill) -> Unit,
) {
    ComposerList {
        when {
            loading -> ComposerListNote("Loading session skills…")
            error != null -> ComposerListNote(error)
            skills.isEmpty() -> ComposerListNote("No matching session skills")
            else -> skills.forEach { skill ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = Kit.RowHeight).clickable(role = Role.Button) { onSelect(skill) }
                        .padding(horizontal = Kit.SideChange, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("/${skill.name}", style = Kit.Body, color = Kit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (skill.description.isNotBlank()) {
                        Text(skill.description, style = Kit.Hint, color = Kit.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMentionPicker(chats: List<MentionChat>, onSelect: (MentionChat) -> Unit) {
    ComposerList {
        chats.forEach { chat ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Kit.RowHeight).testTag("mention-row")
                    .clickable(role = Role.Button) { onSelect(chat) }
                    .padding(horizontal = Kit.SideChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotAvatar(chat.session, diameter = 28.dp)
                Text(chat.name, style = Kit.Body, color = Kit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Bot", style = Kit.Hint, color = Kit.TextMuted)
            }
        }
    }
}

@Composable
private fun ComposerList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().kitSurface(Kit.CardShape).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
private fun ComposerListNote(text: String) {
    Text(text, style = Kit.Hint, color = Kit.TextMuted, modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.SideChange, vertical = 14.dp))
}

@Composable
internal fun BatchTimeLine(label: String, sunlight: Boolean) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().testTag("batch-time").padding(vertical = ReaderMessageSpacing),
        textAlign = TextAlign.Center,
        fontSize = ReaderMetaTextSize,
        color = if (sunlight) MaterialTheme.colorScheme.onSurfaceVariant else ReaderHint,
    )
}
