package dev.multiprompt.companion.model

import java.util.UUID

data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostId: String,
    val remotePath: String,
)
