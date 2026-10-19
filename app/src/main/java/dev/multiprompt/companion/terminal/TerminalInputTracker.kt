package dev.multiprompt.companion.terminal

import org.connectbot.terminal.ModifierManager

internal class TerminalInputTracker(private val onInput: () -> Unit) : ModifierManager {
    override fun isCtrlActive(): Boolean = false
    override fun isAltActive(): Boolean = false
    override fun isShiftActive(): Boolean = false
    override fun clearTransients() { onInput() }
}
