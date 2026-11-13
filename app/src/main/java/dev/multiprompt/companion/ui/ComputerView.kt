package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.multiprompt.companion.computer.ComputerControl
import dev.multiprompt.companion.computer.ComputerState
import dev.multiprompt.companion.computer.ComputerViewerPage
import dev.multiprompt.companion.computer.ComputerStatus
import dev.multiprompt.companion.computer.ComputerTarget
import java.io.File

@Composable
internal fun ComputerPane(
    hostLabel: String,
    state: ComputerState,
    target: ComputerTarget,
    picture: File?,
    onBack: () -> Unit,
    onWake: () -> Unit,
    onSleep: () -> Unit,
    onEditTarget: () -> Unit,
    modifier: Modifier = Modifier,
    screen: @Composable (Modifier, ComputerControl) -> Unit,
) {
    var fullScreen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(ComputerControl()) }
    val live = state.status == ComputerStatus.Live
    LaunchedEffect(live) { if (!live) control = ComputerControl() }
    val controlling = live && control.enabled
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = ReaderScreenPadding).padding(top = ReaderControlSpacing),
        verticalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
    ) {
        if (!fullScreen) {
            Row(Modifier.fillMaxWidth().testTag("computer-header"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                }
                Text("Computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    " · $hostLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Computer actions")
                    }
                    KitMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        KitMenuRow(label = target.value, enabled = false, onClick = {})
                        KitMenuRow(label = "Viewer address", onClick = { menuOpen = false; onEditTarget() })
                        KitMenuRow(label = if (fullScreen) "Leave full screen" else "Open full screen", onClick = {
                            menuOpen = false
                            fullScreen = !fullScreen
                        })
                        KitMenuRow(label = "Hide computer", onClick = { menuOpen = false; onBack() })
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(chromeShape(RoundedCornerShape(12.dp)))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("computer-screen"),
        ) {
            when (state.status) {
                ComputerStatus.Live -> screen(Modifier.fillMaxSize(), control.copy(enabled = controlling))
                else -> ScreenPicture(picture, dimmed = state.status == ComputerStatus.Sleeping)
            }
            when (state.status) {
                ComputerStatus.Connecting -> Overlay(
                    title = "Connecting to the VPS",
                    detail = "First open. No picture yet.",
                    spinner = true,
                )
                ComputerStatus.Reconnecting -> OverlayPill("Reconnecting", spinner = true)
                ComputerStatus.Lost -> {
                    OverlayPill("Connection lost", dotColor = MaterialTheme.colorScheme.error, action = "Retry" to onWake)
                    Overlay(
                        title = "Cannot reach the VPS",
                        detail = "Agents keep working. Only the picture is gone.",
                    )
                }
                ComputerStatus.Sleeping -> {
                    if (picture == null) {
                        Overlay(title = "Sleeping", detail = "Tap to wake.", action = "Wake up" to onWake)
                    } else {
                        Overlay(title = "Sleeping", detail = "Last picture kept.", action = "Wake up" to onWake)
                    }
                }
                ComputerStatus.Live -> Unit
            }
            if (fullScreen) {
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (controlling) {
                        CornerPill("Give back") { control = control.copy(enabled = false) }
                    }
                    CornerPill("Exit full screen") { fullScreen = false }
                }
            }
        }
        if (!fullScreen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(8.dp).background(statusColor(state.status), CircleShape),
                )
                Text(statusLabel(state.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                if (controlling) "In control" else "View only",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (controlling) "A tap clicks on the VPS. Keyboard types there." else "Agents keep working. Take control to click and type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KitSecondaryButton(
                if (controlling) "Give back" else "Take control",
                onClick = { control = control.copy(enabled = !controlling) },
                modifier = Modifier.fillMaxWidth(),
                enabled = live,
            )
            if (controlling) {
                KitSecondaryButton(
                    "Keyboard",
                    onClick = { control = control.copy(keyboardRequests = control.keyboardRequests + 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            KitSecondaryButton("Open full screen", onClick = { fullScreen = true }, modifier = Modifier.fillMaxWidth())
            if (live) {
                KitSecondaryButton("Sleep", onClick = onSleep, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CornerPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ScreenPicture(picture: File?, dimmed: Boolean) {
    val bitmap = remember(picture?.path, picture?.lastModified()) {
        picture?.let { file -> runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Last picture of the VPS screen",
            modifier = Modifier.fillMaxSize().alpha(if (dimmed) 0.45f else 1f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun Overlay(
    title: String,
    detail: String,
    spinner: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (spinner) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let { (label, onClick) -> KitSecondaryButton(label, onClick = onClick) }
    }
}

@Composable
private fun OverlayPill(
    label: String,
    spinner: Boolean = false,
    dotColor: Color? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spinner) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            dotColor?.let { Box(Modifier.size(8.dp).background(it, CircleShape)) }
            Text(label, style = MaterialTheme.typography.labelMedium)
            action?.let { (text, onClick) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp).clickable(onClick = onClick),
                )
            }
        }
    }
}

private fun statusLabel(status: ComputerStatus): String = when (status) {
    ComputerStatus.Sleeping -> "Sleeping"
    ComputerStatus.Connecting -> "Connecting"
    ComputerStatus.Live -> "Live"
    ComputerStatus.Reconnecting -> "Reconnecting"
    ComputerStatus.Lost -> "Connection lost"
}

@Composable
private fun statusColor(status: ComputerStatus): Color = when (status) {
    ComputerStatus.Live -> MaterialTheme.colorScheme.primary
    ComputerStatus.Connecting, ComputerStatus.Reconnecting -> MaterialTheme.colorScheme.onSurfaceVariant
    ComputerStatus.Sleeping -> MaterialTheme.colorScheme.tertiary
    ComputerStatus.Lost -> MaterialTheme.colorScheme.error
}

@Composable
internal fun ComputerScreen(
    url: String,
    control: ComputerControl,
    onPicture: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    onUnreachable: () -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                tag = AppliedControl(keyboardRequests = control.keyboardRequests)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) onUnreachable()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val applied = view.tag as? AppliedControl ?: return
                        view.evaluateJavascript(ComputerViewerPage.controlScript(applied.enabled == true), null)
                    }
                }
                loadUrl(url)
            }
        },
        update = { view ->
            val applied = view.tag as? AppliedControl
            if (applied != null) {
                if (applied.enabled != control.enabled) {
                    applied.enabled = control.enabled
                    view.evaluateJavascript(ComputerViewerPage.controlScript(control.enabled), null)
                }
                if (applied.keyboardRequests != control.keyboardRequests) {
                    applied.keyboardRequests = control.keyboardRequests
                    if (control.enabled) view.showServerKeyboard()
                }
            }
        },
        onRelease = { view ->
            runCatching { view.snapshot()?.let(onPicture) }
            runCatching { view.destroy() }
        },
    )
}

private class AppliedControl(var enabled: Boolean? = null, var keyboardRequests: Int = 0)

private fun WebView.showServerKeyboard() {
    requestFocus()
    evaluateJavascript(ComputerViewerPage.KEYBOARD_SCRIPT) {
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }
}

private fun WebView.snapshot(): Bitmap? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    }.getOrNull()
}

@Composable
internal fun PagePane(
    title: String,
    onBack: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    screen: @Composable (Modifier) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = ReaderScreenPadding).padding(top = ReaderControlSpacing),
        verticalArrangement = Arrangement.spacedBy(ReaderControlSpacing),
    ) {
        Row(Modifier.fillMaxWidth().testTag("page-header"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
            }
            Text("Page", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                " · $title",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Page actions")
                }
                KitMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    KitMenuRow(label = "Open in browser", onClick = { menuOpen = false; onOpenInBrowser() })
                    KitMenuRow(label = "Close page", onClick = { menuOpen = false; onBack() })
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(chromeShape(RoundedCornerShape(12.dp)))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("page-screen"),
        ) {
            screen(Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun PageScreen(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        onRelease = { view -> runCatching { view.destroy() } },
    )
}
