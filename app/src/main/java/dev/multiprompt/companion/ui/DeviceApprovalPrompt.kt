package dev.multiprompt.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
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
    KitSheet(onDismiss = {}, dismissible = false) {
        Text("Approve this device?", style = Kit.Heading, color = Kit.Text)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${device.name} wants to open your servers. Approve only if you just signed in on it.", style = Kit.Body, color = Kit.Text)
            Text(device.fingerprint, style = Kit.Hint, color = Kit.TextMuted)
            if (busy) Text("Adding to your servers…", style = Kit.Hint, color = Kit.TextMuted)
            if (error != null) Text(error, color = Kit.Danger)
        }
        KitPrimaryButton("Approve", onClick = onApprove, enabled = !busy)
        KitSecondaryButton("No", onClick = onDeny, enabled = !busy)
    }
}

fun sshKeyFingerprint(publicKeyLine: String): String {
    val blob = Base64.getDecoder().decode(publicKeyLine.trim().split(Regex("\\s+"), limit = 3)[1])
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
