package dev.multiprompt.companion.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.multiprompt.companion.BuildConfig

@Composable
internal fun ColumnScope.AppMenuItems(
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (() -> Unit)? = null,
) {
    KitMenuRow(
        label = if (signedIn) "Sync: signed in — sign out" else "Sign in to sync",
        icon = Icons.Default.Cloud,
        onClick = {
            onDismiss()
            if (signedIn) onSignOut() else onSignIn()
        },
    )
    if (onUpdate != null) {
        KitMenuRow(
            label = "Update",
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
        KitMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            KitMenuRow(
                label = "Settings",
                onClick = {
                    expanded = false
                    onSettings()
                },
            )
            KitMenuRow(
                label = "Arrange splits",
                onClick = {
                    expanded = false
                    onArrangeSplits()
                },
            )
            KitMenuRow(
                label = "New workspace",
                onClick = {
                    expanded = false
                    onNewWorkspace()
                },
            )
            KitMenuRow(
                label = "Refresh",
                icon = Icons.Default.Refresh,
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            KitMenuRow(
                label = BuildConfig.VERSION_NAME,
                modifier = Modifier.testTag("app-version").semantics {
                    contentDescription = "Version ${BuildConfig.VERSION_NAME}, open release notes"
                },
                onClick = { expanded = false; onReleaseNotes() },
            )
        }
    }
}
