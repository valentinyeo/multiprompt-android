package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile

data class HostKeyWarningText(
    val title: String,
    val body: String,
    val server: String,
    val saved: String?,
    val presented: String,
    val confirm: String,
)

object HostKeyWarning {
    fun text(host: HostProfile?, key: PresentedHostKey): HostKeyWarningText {
        val label = host?.label ?: "SSH host"
        val server = host?.let {
            "${it.username}@${it.hostname}" + if (it.port != 22) ":${it.port}" else ""
        } ?: "unknown server"
        return if (key.changed) {
            HostKeyWarningText(
                title = "SSH host key changed",
                body = "The saved identity for $label no longer matches. Only continue if you intentionally changed the server key.",
                server = server,
                saved = host?.hostKeyFingerprint?.let { "Saved: ${host.hostKeyType} $it" },
                presented = "Now: ${key.type} ${key.fingerprint}",
                confirm = "Replace trusted key",
            )
        } else {
            HostKeyWarningText(
                title = "Trust $label?",
                body = "$label presented this identity on the first connection:",
                server = server,
                saved = null,
                presented = "${key.type} ${key.fingerprint}",
                confirm = "Trust and connect",
            )
        }
    }
}
