package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.ssh.TmuxText

internal fun ReaderState.isWorking(agent: AgentKind): Boolean = sending || (hookState?.active
    ?: TmuxText.readerBlocks(screenOutput, agent).any {
        it.kind == TmuxText.ReaderBlockKind.PROGRESS && isTransientProgress(it.text)
    })

@Composable
internal fun WorkingDots() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "Working" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) {
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), CircleShape))
        }
    }
}
