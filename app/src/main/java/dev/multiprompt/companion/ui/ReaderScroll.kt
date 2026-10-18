package dev.multiprompt.companion.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Composable
internal fun rememberReaderScrollState(session: Any): ScrollState =
    remember(session) { ScrollState(0) }

@Composable
internal fun Modifier.readerScroll(state: ScrollState, followRequest: Int = 0): Modifier {
    var pinnedToBottom by remember(state) { mutableStateOf(true) }
    var chosenOffset by remember(state) { mutableIntStateOf(0) }
    val userScroll = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (consumed.y != 0f) {
                    pinnedToBottom = !state.canScrollForward
                    chosenOffset = state.value
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state, followRequest) {
        pinnedToBottom = true
        if (state.maxValue != Int.MAX_VALUE) state.scrollTo(state.maxValue)
    }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.maxValue, state.isScrollInProgress, pinnedToBottom) }
            .collect { (maximum, scrolling, pinned) ->
                if (!scrolling && maximum != Int.MAX_VALUE) {
                    val target = if (pinned) maximum else chosenOffset.coerceAtMost(maximum)
                    if (state.value != target) state.scrollTo(target)
                }
            }
    }
    return nestedScroll(userScroll).verticalScroll(state)
}
