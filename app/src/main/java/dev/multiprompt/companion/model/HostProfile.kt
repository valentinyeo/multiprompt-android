package dev.multiprompt.companion.model

import java.util.UUID

data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val keySecretId: String = "host_key_$id",
    val passphraseSecretId: String? = null,
    val hostKeyType: String? = null,
    val hostKeyFingerprint: String? = null,
    val hostKeys: List<String> = emptyList(),
    val dismissedHostKeyFingerprint: String? = null,
)

data class HostDraft(
    val id: String? = null,
    val label: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val passphrase: String = "",
)

