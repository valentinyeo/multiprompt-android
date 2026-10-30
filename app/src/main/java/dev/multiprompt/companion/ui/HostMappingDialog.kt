package dev.multiprompt.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountHostMap

@Composable
internal fun HostMappingDialog(state: AppUiState, onMap: (Int, String?) -> Unit, onDismiss: () -> Unit) {
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    KitSheet(onDismiss = onDismiss) {
        BackHandler { if (selectedSlot != null) selectedSlot = null else onDismiss() }
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Desktop tmux hosts", style = Kit.BodyStrong, color = Kit.Text)
            val slot = selectedSlot
            if (slot == null) {
                KitListGroup {
                    AccountHostMap.labels.forEachIndexed { index, label ->
                        val name = state.accountLayout?.hosts?.firstOrNull { it.slot == index }?.name ?: label
                        val host = state.accountHosts[index]?.let { "${it.label} (${it.username})" } ?: "Not mapped"
                        KitListRow("$index · $name: $host", onClick = { selectedSlot = index })
                    }
                }
                state.hosts.forEach { host ->
                    host.hostKeyFingerprint?.let { Text("Pinned $it", style = Kit.Body, color = Kit.TextMuted) }
                }
                state.hostErrors.forEach { (id, message) ->
                    Text("${state.hosts.firstOrNull { it.id == id }?.label}: $message", style = Kit.Body, color = Kit.Danger)
                }
                KitPrimaryButton("Done", onClick = onDismiss)
            } else {
                KitListGroup {
                    KitListRow("Automatic", onClick = { onMap(slot, null); selectedSlot = null })
                    state.hosts.filter { slot != 0 || it.username == "valentin" }.forEach { host ->
                        KitListRow("${host.label} · ${host.username}@${host.hostname}", onClick = { onMap(slot, host.id); selectedSlot = null })
                    }
                }
                KitSecondaryButton("Back", onClick = { selectedSlot = null })
            }
        }
    }
}
