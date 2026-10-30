package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun defaultShellFontSize(bodyFontSize: TextUnit): TextUnit = bodyFontSize

internal fun zoomShellFontSize(size: Float, zoom: Float): Float =
    (size * zoom).coerceIn(8f, 28f)

@Composable
internal fun ShellContent(
    historyVisible: Boolean,
    onOpenHistory: () -> Unit,
    onJumpToLive: () -> Unit,
    loadHistory: suspend () -> List<String>,
    fontSize: TextUnit,
    onFontSizeChange: (Float) -> Unit,
    contentWidth: Dp,
    onLiveTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    liveContent: @Composable (Modifier) -> Unit,
) {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var historyDrag by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableFloatStateOf(0f) }
    val currentFontSize by rememberUpdatedState(fontSize)
    val currentOnFontSizeChange by rememberUpdatedState(onFontSizeChange)
    val currentOnLiveTap by rememberUpdatedState(onLiveTap)
    val currentOnOpenHistory by rememberUpdatedState(onOpenHistory)
    val currentLoadHistory by rememberUpdatedState(loadHistory)
    LaunchedEffect(historyVisible) {
        if (historyVisible) {
            lines = null
            error = null
            try {
                lines = currentLoadHistory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not load history"
            }
        } else {
            historyDrag = 0f
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(TerminalBackground)) {
        val density = LocalDensity.current
        val maxPan by rememberUpdatedState(with(density) { (contentWidth - maxWidth).toPx().coerceAtLeast(0f) })
        val liveWidth = contentWidth.coerceAtLeast(1.dp)
        Box(
            Modifier.fillMaxSize()
                .testTag("Shell content")
                .pointerInput(historyVisible) {
                    if (historyVisible) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var total = Offset.Zero
                        var zooming = false
                        var panning = false
                        var size = currentFontSize.value
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) {
                                zooming = true
                                size = zoomShellFontSize(size, event.calculateZoom())
                                currentOnFontSizeChange(size)
                                event.changes.forEach { it.consume() }
                            } else if (!zooming) {
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val delta = change.position - change.previousPosition
                                total += delta
                                if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y) * 2) {
                                    val next = (pan - delta.x).coerceIn(0f, maxPan)
                                    if (next != pan || panning) {
                                        pan = next
                                        panning = true
                                        change.consume()
                                    }
                                }
                                if (abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x) * 2) {
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!zooming && !panning && abs(total.y) > 32.dp.toPx() && abs(total.y) > abs(total.x) * 2) {
                            historyDrag = abs(total.y)
                            currentOnOpenHistory()
                        } else if (!zooming && total.getDistance() < viewConfiguration.touchSlop) {
                            currentOnLiveTap()
                        }
                    }
                },
        ) {
            liveContent(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(liveWidth)
                    .offset { IntOffset(-pan.coerceIn(0f, maxPan).roundToInt(), 0) },
            )
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            })
        }
        if (historyVisible) {
            Box(Modifier.fillMaxSize().imePadding().background(TerminalBackground)) {
                ShellHistory(lines.orEmpty(), fontSize, onJumpToLive, initialScrollPx = historyDrag)
                if (lines == null && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center)) }
            }
        }
    }
}

@Composable
internal fun ShellHistory(
    lines: List<String>,
    fontSize: TextUnit,
    onJumpToLive: () -> Unit,
    modifier: Modifier = Modifier,
    initialScrollPx: Float = 0f,
) {
    val state = rememberLazyListState()
    val latestOnJumpToLive by rememberUpdatedState(onJumpToLive)
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val overscroll = remember(state, threshold) {
        object : NestedScrollConnection {
            var distance = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 || state.canScrollBackward) distance = 0f
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !state.canScrollBackward && available.y < 0) {
                    distance -= available.y
                    if (distance > threshold) latestOnJumpToLive()
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) state.scrollToItem(0, initialScrollPx.roundToInt())
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag("Shell history").nestedScroll(overscroll)
                .horizontalScroll(rememberScrollState()),
        ) {
            items(lines.asReversed()) { line ->
                Text(
                    line,
                    fontFamily = ReaderFontFamily,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.2f,
                    color = TerminalForeground,
                    softWrap = false,
                )
            }
        }
        FloatingActionButton(onClick = onJumpToLive, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to live")
        }
    }
}
