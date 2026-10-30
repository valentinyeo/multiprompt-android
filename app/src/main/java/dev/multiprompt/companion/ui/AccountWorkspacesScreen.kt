package dev.multiprompt.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.account.AccountHostMap
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.model.TmuxSession

@Composable
internal fun AccountWorkspacesScreen(
    viewModel: MainViewModel,
    onInbox: () -> Unit,
    onReleaseNotes: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val layout = state.accountLayout
    var allSessions by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hostMapping by remember { mutableStateOf(false) }
    var workspaceEditor by remember { mutableStateOf(false) }
    var oldWorkspace by remember { mutableStateOf<String?>(null) }
    var workspaceName by remember { mutableStateOf("") }
    var renameTab by remember { mutableStateOf<AccountTab?>(null) }
    var tabName by remember { mutableStateOf("") }
    var moveTab by remember { mutableStateOf<AccountTab?>(null) }
    var addSession by remember { mutableStateOf<TmuxSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = state.selectedWorkspaceId?.takeIf { it in layout?.workspaces.orEmpty() } ?: layout?.workspaces?.firstOrNull()
    val signIn: () -> Unit = { viewModel.signInAccount { context.startActivity(it) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                if (!allSessions) FilledTonalButton(onClick = { allSessions = false }) { Text("Workspaces") }
                else TextButton(onClick = { allSessions = false }) { Text("Workspaces") }
                if (allSessions) FilledTonalButton(onClick = { allSessions = true }) { Text("All Sessions") }
                else TextButton(onClick = { allSessions = true; viewModel.refresh() }) { Text("All Sessions") }
                TextButton(onClick = onInbox) { Text("Inbox") }
                TextButton(onClick = { viewModel.select(AppSection.HOSTS) }) { Text("Hosts") }
                TextButton(onClick = { viewModel.select(AppSection.UPDATE) }) { Text("Update") }
            }
            VersionLabel(onClick = onReleaseNotes)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            TextButton(onClick = { viewModel.syncAccount(); viewModel.refresh() }) { Text("Refresh") }
            TextButton(onClick = { hostMapping = true }) { Text("Host mapping") }
            TextButton(onClick = signIn) { Text(if (state.signedIn) "Switch / renew login" else "Sign in") }
            if (state.signedIn) TextButton(onClick = viewModel::signOutAccount) { Text("Sign out") }
        }
        if (state.accountSyncing || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            when {
                state.accountPending -> "Pending changes saved on this device"
                !state.signedIn -> "Sign in with the same desktop account to load workspaces"
                layout != null -> "Account layout · revision ${layout.revision}"
                else -> "Pulling account workspaces…"
            }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
        )
        (error ?: state.accountError ?: state.sessionActionError)?.let { message ->
            Text(message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        state.hostErrors.forEach { (id, message) ->
            Text("${state.hosts.firstOrNull { it.id == id }?.label}: $message — see Hosts to verify keys",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text("Find a tab or session") }, singleLine = true)
        if (!allSessions) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                layout?.workspaces.orEmpty().forEach { workspace ->
                    if (workspace == selected) FilledTonalButton(onClick = { viewModel.selectWorkspace(workspace) }) { Text(workspace) }
                    else TextButton(onClick = { viewModel.selectWorkspace(workspace) }) { Text(workspace) }
                }
                TextButton(enabled = state.signedIn && layout != null, onClick = {
                    oldWorkspace = null; workspaceName = ""; workspaceEditor = true; error = null
                }) { Text("+ Workspace") }
            }
            selected?.let { workspace ->
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    TextButton(enabled = state.signedIn, onClick = {
                        oldWorkspace = workspace; workspaceName = workspace; workspaceEditor = true; error = null
                    }) { Text("Rename workspace") }
                    TextButton(enabled = state.signedIn, onClick = {
                        error = viewModel.editAccountLayout { current ->
                            val index = current.workspaces.indexOf(workspace)
                            if (index > 0) current.copy(workspaces = current.workspaces.toMutableList().apply {
                                add(index - 1, removeAt(index))
                            }) else current
                        }
                    }) { Text("Move left") }
                    TextButton(enabled = state.hosts.isNotEmpty(), onClick = {
                        viewModel.selectWorkspace(workspace); viewModel.startNewSession()
                    }) { Text("New session") }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(layout?.tabs.orEmpty().filter { it.workspace == selected &&
                    (it.name.contains(query, true) || it.session.contains(query, true)) }, key = { it.identity }) { tab ->
                    val session = AccountHostMap.sessionFor(tab, state.sessions, state.accountHosts, layout)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tab.name, Modifier.fillMaxWidth().clickable(enabled = session != null) {
                                session?.let { viewModel.openReader(it, userInitiated = true) }
                            }, style = MaterialTheme.typography.titleMedium)
                            Text("${AccountHostMap.labels[tab.host]} · ${tab.session}", style = MaterialTheme.typography.bodySmall)
                            if (session == null) Text("Map this host to an SSH profile to open it", color = MaterialTheme.colorScheme.error)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(enabled = session != null, onClick = { session?.let { viewModel.openReader(it, userInitiated = true) } }) { Text("Open") }
                                TextButton(enabled = session != null, onClick = { session?.let { viewModel.openTerminal(it, userInitiated = true) } }) { Text("Terminal") }
                                TextButton(enabled = state.signedIn, onClick = { renameTab = tab; tabName = tab.name; error = null }) { Text("Rename") }
                                TextButton(enabled = state.signedIn, onClick = { moveTab = tab; error = null }) { Text("Move") }
                                TextButton(enabled = state.signedIn, onClick = { viewModel.closeAccountTab(tab) }) { Text("Close tab") }
                            }
                        }
                    }
                }
                if (layout?.tabs.orEmpty().none { it.workspace == selected }) item {
                    Text("No tabs here. Add a running session from All Sessions. Closing a tab never kills tmux.", Modifier.padding(16.dp))
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.hosts.forEach { host ->
                    item(key = "host:${host.id}") { Text("${host.label} · ${host.username}@${host.hostname}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) }
                    items(state.sessions.filter { it.hostId == host.id &&
                        (it.displayName.contains(query, true) || it.name.contains(query, true)) }.sortedBy { it.name }, key = { "${it.hostId}:${it.name}" }) { raw ->
                        val tab = layout?.let { AccountHostMap.tabFor(raw, it, state.accountHosts) }
                        val session = tab?.let { AccountHostMap.sessionFor(it, state.sessions, state.accountHosts, layout) } ?: raw
                        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(session.displayName, Modifier.fillMaxWidth().clickable { viewModel.openReader(session) }, style = MaterialTheme.typography.titleMedium)
                                Text("${raw.name} · ${tab?.workspace ?: "Not in a workspace"}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { viewModel.openReader(session) }) { Text("Open") }
                                    TextButton(onClick = { viewModel.openTerminal(session) }) { Text("Terminal") }
                                    TextButton(enabled = state.signedIn && layout?.workspaces?.isNotEmpty() == true, onClick = { addSession = raw; error = null }) {
                                        Text(if (tab == null) "Add to workspace" else "Move to workspace")
                                    }
                                }
                            }
                        }
                    }
                    if (state.sessions.none { it.hostId == host.id }) item { Text("No sessions found", Modifier.padding(horizontal = 16.dp)) }
                }
                if (state.hosts.isEmpty()) item { Button(onClick = { viewModel.showHostEditor() }, Modifier.padding(16.dp)) { Text("Add SSH host") } }
            }
        }
    }

    if (workspaceEditor || renameTab != null) AlertDialog(
        onDismissRequest = { workspaceEditor = false; renameTab = null },
        title = { Text(if (renameTab != null) "Rename tab" else if (oldWorkspace == null) "New workspace" else "Rename workspace") },
        text = { Column {
            OutlinedTextField(if (renameTab != null) tabName else workspaceName, {
                if (renameTab != null) tabName = it else workspaceName = it
                error = null
            }, label = { Text("Name") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            error = viewModel.editAccountLayout { current ->
                renameTab?.let { current.renameTab(it.identity, tabName.trim()) }
                    ?: oldWorkspace?.let { current.renameWorkspace(it, workspaceName.trim()) }
                    ?: current.copy(workspaces = current.workspaces + workspaceName.trim())
            }
            if (error == null) { workspaceEditor = false; renameTab = null }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { workspaceEditor = false; renameTab = null }) { Text("Cancel") } },
    )
    if (moveTab != null || addSession != null) AlertDialog(
        onDismissRequest = { moveTab = null; addSession = null }, title = { Text("Choose workspace") },
        text = { Column {
            layout?.workspaces.orEmpty().forEach { workspace ->
                TextButton(onClick = {
                    error = moveTab?.let { tab -> viewModel.editAccountLayout { it.moveTab(tab.identity, workspace) } }
                        ?: addSession?.let { viewModel.addAccountSession(it, workspace) }
                    if (error == null) { moveTab = null; addSession = null }
                }) { Text(workspace) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { moveTab = null; addSession = null }) { Text("Cancel") } },
    )
    if (hostMapping) AlertDialog(
        onDismissRequest = { hostMapping = false }, title = { Text("Desktop tmux hosts") },
        text = { Column {
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
        } }, confirmButton = { TextButton(onClick = { hostMapping = false }) { Text("Done") } },
    )
}
