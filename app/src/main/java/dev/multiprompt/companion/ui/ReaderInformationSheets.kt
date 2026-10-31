package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.ssh.TmuxText
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun EstimateAccuracySheet(
    rows: List<TmuxText.AgentAccuracy>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    KitSheet(onDismiss = onDismiss) {
        Text("Estimate accuracy", style = Kit.Heading, color = Kit.Text)
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                loading -> Text("Reading the estimate log…", style = Kit.Body, color = Kit.Text)
                error != null -> Text(error, style = Kit.Body, color = Kit.Danger)
                rows.isEmpty() -> Text(
                    "No finished estimates yet. A finished turn counts when it ran " +
                        "two minutes or more, and an agent needs three of them.",
                    style = Kit.Body, color = Kit.Text,
                )
                else -> rows.forEach { row ->
                    Text(
                        "${row.agent}: tasks take " +
                            String.format(Locale.US, "%.1f", row.ratio) +
                            "x the estimate (last ${row.samples})",
                        style = Kit.Body, color = Kit.Text,
                    )
                }
            }
        }
        KitPrimaryButton("Close", onClick = onDismiss)
    }
}

@Composable
internal fun SessionHistorySheet(
    historyPages: StateFlow<List<String>>,
    onLoadOlder: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    val pages by historyPages.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun loadOlder() {
        if (loading || exhausted) return
        loading = true
        scope.launch {
            val got = onLoadOlder()
            if (!got) exhausted = true
            loading = false
        }
    }

    KitSheet(onDismiss = onDismiss) {
        Text("Session history", style = Kit.Heading, color = Kit.Text)
        if (pages.isEmpty()) {
            Text("Capturing…", style = Kit.Hint, color = Kit.TextMuted)
        } else if (exhausted) {
            Text("No older history available.", style = Kit.Hint, color = Kit.TextMuted)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pages.asReversed()) { _, page ->
                SelectionContainer {
                    Text(page, style = MaterialTheme.typography.bodySmall, color = Kit.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            item {
                KitSecondaryButton(
                    if (loading) "Loading…" else "Load older",
                    onClick = ::loadOlder,
                    enabled = !loading && !exhausted,
                )
            }
        }
        KitPrimaryButton("Back to live", onClick = onDismiss)
    }
}
