package dev.multiprompt.companion.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.account.AccountHostMap

@Composable
internal fun HostMappingDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val layout = state.accountLayout
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desktop tmux hosts") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Host 0 must use valentin's outer tmux. Inne tabs attach there, not directly to inne. SSH keys stay local.")
            AccountHostMap.labels.forEachIndexed { slot, label ->
                var expanded by remember { mutableStateOf(false) }
                val name = layout?.hosts?.firstOrNull { it.slot == slot }?.name ?: label
                TextButton(onClick = { expanded = true }) { Text("$slot · $name: ${state.accountHosts[slot]?.let { "${it.label} (${it.username})" } ?: "Not mapped"}") }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text("Automatic") }, onClick = { viewModel.mapAccountHost(slot, null); expanded = false })
                    state.hosts.filter { slot != 0 || it.username == "valentin" }.forEach { host ->
                        DropdownMenuItem(text = { Text("${host.label} · ${host.username}@${host.hostname}") }, onClick = { viewModel.mapAccountHost(slot, host.id); expanded = false })
                    }
                }
            }
            state.hosts.forEach { host ->
                host.hostKeyFingerprint?.let { Text("Pinned $it", style = MaterialTheme.typography.labelSmall) }
            }
            state.pendingHostKeys.forEach { (id, key) ->
                Text(if (key.changed) "WARNING: the SSH host key changed" else "First connection: verify this SSH host key",
                    color = MaterialTheme.colorScheme.error)
                Text(key.fingerprint)
                TextButton(onClick = { viewModel.trustHostKey(id) }) {
                    Text(if (key.changed) "Replace trusted key" else "Trust this key")
                }
            }
            state.hostErrors.forEach { (id, message) ->
                Text("${state.hosts.firstOrNull { it.id == id }?.label}: $message", color = MaterialTheme.colorScheme.error)
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
