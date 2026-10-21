package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import java.util.Base64

data class PendingDevice(val id: String, val name: String, val fingerprint: String)

@Composable
fun DeviceApprovalPrompt(
    device: PendingDevice,
    busy: Boolean,
    error: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDeny() },
        title = { Text("Approve this device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${device.name} wants to open your servers. Approve only if you just signed in on it.")
                Text(
                    device.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Text("Adding to your servers…", style = MaterialTheme.typography.bodySmall)
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = onDeny, enabled = !busy) { Text("No") }
        },
    )
}

fun sshKeyFingerprint(publicKeyLine: String): String {
    val blob = Base64.getDecoder().decode(publicKeyLine.trim().split(Regex("\\s+"), limit = 3)[1])
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
