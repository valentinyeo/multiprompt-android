package dev.multiprompt.companion.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.Workspace
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.reader.TranscriptMessage
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.skills.SkillStore
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.upload.ScreencastUploader
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.util.ReflectionHelpers

enum class M3Screen { INBOX_SPLITS, INBOX_SEARCH, INBOX_LIST, SESSION, INBOX_MENU, SESSION_MENU }

private val testHost = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
private val testSession = TmuxSession("host", "claude-reader", 1, 0, 20, preview = "Ready for your next prompt")
private val testWorkspace = Workspace("work", "Android", "host", "/home/user/android")

@Composable
internal fun M3TestScreen(screen: M3Screen, material3: Boolean, sunlight: Boolean) {
    MultipromptTheme(sunlight = sunlight, material3 = false) {
        Surface {
            if (screen == M3Screen.SESSION || screen == M3Screen.SESSION_MENU) {
                ReaderFixture(material3, sunlight)
            } else {
                InboxFixture(screen, material3, sunlight)
            }
        }
    }
}

@Composable
private fun InboxFixture(screen: M3Screen, material3: Boolean, sunlight: Boolean) {
    SessionsScreen(
        state = AppUiState(
            hosts = listOf(testHost),
            sessions = listOf(testSession, testSession.copy(name = "codex-tests", lastActivityEpochSeconds = 10)),
            workspaces = listOf(testWorkspace),
            workspaceSplitIds = listOf(null, testWorkspace.id),
            sessionWorkspaceIds = mapOf(SessionReadStore.key("host", testSession.name) to testWorkspace.id),
            newestSessionsAtBottom = false,
            selectedWorkspaceId = if (screen == M3Screen.INBOX_SPLITS) testWorkspace.id else null,
        ),
        signedIn = true,
        onSignIn = {},
        onSignOut = {},
        onOpen = {},
        onOpenTerminal = {},
        onArchive = {},
        onArchiveUntil = { _, _ -> },
        onRestore = {},
        onDissolve = {},
        onEnd = {},
        onRestoreDissolved = {},
        onForgetDissolved = {},
        onMarkUnread = {},
        onToggleArchived = {},
        onSelectSessionBucket = {},
        onSelectWorkspace = {},
        onSwitchWorkspace = {},
        onMoveWorkspaceSplit = { _, _ -> },
        onResetWorkspaceSplitOrder = {},
        onCreateWorkspace = { null },
        onMoveSession = { _, _ -> },
        onRenameSession = { _, _ -> null },
        onSetFavorite = { _, _ -> },
        onRefresh = {},
        onSelectSection = {},
        onForgetSession = {},
        onStartNewSession = {},
        onSetNewestSessionsAtBottom = {},
        onSetAllSplitOnRight = {},
        appTheme = if (sunlight) AppTheme.SUNLIGHT else AppTheme.DARK,
        onSetAppTheme = {},
        onClearSessionActionError = {},
        readerDefaultFontScale = 1f,
        onSetReaderDefaultFontScale = {},
        crashReport = null,
        onClearCrashReport = {},
        onAddHost = {},
        material3 = material3,
    )
}

@Composable
private fun ReaderFixture(material3: Boolean, sunlight: Boolean) {
    val context = LocalContext.current
    val secrets = remember { SecretStore(context) }
    val connection = remember {
        SessionReaderConnection(
            SshRepository(secrets), testHost, testSession.name,
            historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(context.cacheDir.resolve("m3-chat-history")),
        ).apply {
            ReflectionHelpers.getField<MutableStateFlow<ReaderState>>(this, "_state").value = ReaderState(
                status = ReaderStatus.Live,
                transcript = listOf(
                    TranscriptMessage("user", null, "Give the Inbox a Material 3 look."),
                    TranscriptMessage("assistant", null, "## Ready\n\nThe classic app stays unchanged.\n\n- Inbox and chat only\n- Independent update channel\n\n[Project](https://multiprompt.dev)"),
                ),
            )
        }
    }
    val dictation = remember { DeepgramDictation(context, secrets) }
    val skills = remember { SkillStore(context).apply { repos = emptyList() } }
    val screencast = remember { ScreencastUploader(context, secrets) }
    DisposableEffect(connection) {
        onDispose {
            connection.close()
            dictation.close()
        }
    }
    ReaderScreen(
        connection = connection,
        dictation = dictation,
        screencast = screencast,
        skills = skills,
        signedIn = true,
        onSignIn = {},
        onSignOut = {},
        session = testSession,
        unread = false,
        archived = false,
        initialFontScale = 1f,
        onBack = {},
        onMarkRead = {},
        onArchiveToggle = {},
        onRemind = {},
        onOpenTerminal = {},
        onSwitchSession = {},
        onFontScaleChanged = {},
        onRename = { null },
        onDissolve = {},
        onEnd = {},
        onSessionInteraction = {},
        onSessionInput = {},
        technicalMode = false,
        onTechnicalModeChanged = {},
        sunlightMode = sunlight,
        onSetAppTheme = {},
        onResetFontScale = { 1f },
        sessionActionError = null,
        onClearSessionActionError = {},
        onForget = {},
        onShowEtaAccuracy = {},
        onReleaseNotes = {},
        material3 = material3,
    )
}
