package dev.multiprompt.companion.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.connectbot.terminal.ModifierManager

internal class TerminalInputTracker(private val onInput: () -> Unit) : ModifierManager {
    private var ctrl by mutableStateOf(false)
    private var alt by mutableStateOf(false)

    fun toggleCtrl() { ctrl = !ctrl }
    fun toggleAlt() { alt = !alt }

    override fun isCtrlActive(): Boolean = ctrl
    override fun isAltActive(): Boolean = alt
    override fun isShiftActive(): Boolean = false
    override fun clearTransients() {
        ctrl = false
        alt = false
        onInput()
    }
}
