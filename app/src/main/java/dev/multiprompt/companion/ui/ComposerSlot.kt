package dev.multiprompt.companion.ui

internal enum class ComposerSlot { SEND, STOP }

internal fun composerSlot(working: Boolean, busy: Boolean, draftEmpty: Boolean): ComposerSlot =
    if (working && !busy && draftEmpty) ComposerSlot.STOP else ComposerSlot.SEND
