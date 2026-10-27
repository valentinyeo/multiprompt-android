package dev.multiprompt.companion.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ColumnScope.AppMenuItems(
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (() -> Unit)? = null,
) {
    DropdownMenuItem(
        text = { Text(if (signedIn) "Sync: signed in — sign out" else "Sign in to sync") },
        leadingIcon = {
            Icon(
                Icons.Default.Cloud,
                null,
                Modifier.size(18.dp),
                tint = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = {
            onDismiss()
            if (signedIn) onSignOut() else onSignIn()
        },
    )
    if (onUpdate != null) {
        DropdownMenuItem(
            text = { Text("Update") },
            onClick = {
                onDismiss()
                onUpdate()
            },
        )
    }
}

@Composable
internal fun InboxOverflowMenu(
    onSettings: () -> Unit,
    onArrangeSplits: () -> Unit,
    onNewWorkspace: () -> Unit,
    onRefresh: () -> Unit,
    onReleaseNotes: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.roundOutlined()) {
            Icon(Icons.Default.MoreVert, "Session list actions")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    expanded = false
                    onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("Arrange splits") },
                onClick = {
                    expanded = false
                    onArrangeSplits()
                },
            )
            DropdownMenuItem(
                text = { Text("New workspace") },
                onClick = {
                    expanded = false
                    onNewWorkspace()
                },
            )
            DropdownMenuItem(
                text = { Text("Refresh") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            DropdownMenuItem(
                text = { VersionLabel(onClick = { expanded = false; onReleaseNotes() }) },
                onClick = { expanded = false; onReleaseNotes() },
            )
        }
    }
}
