package dev.multiprompt.companion.terminal

internal object TerminalKeys {
    enum class Key(val label: String, private val sequence: String) {
        Esc("Esc", "\u001b"),
        Tab("Tab", "\t"),
        Up("Up", "\u001b[A"),
        Down("Down", "\u001b[B"),
        Left("Left", "\u001b[D"),
        Right("Right", "\u001b[C");

        fun bytes(): ByteArray = sequence.toByteArray(Charsets.UTF_8)
    }

    fun character(codePoint: Int, ctrl: Boolean = false, alt: Boolean = false): ByteArray {
        val control = if (ctrl) {
            when (codePoint) {
                in 'a'.code..'z'.code, in '@'.code..'_'.code -> codePoint and 0x1f
                ' '.code -> 0
                '?'.code -> 0x7f
                else -> codePoint
            }
        } else {
            codePoint
        }
        val bytes = String(Character.toChars(control)).toByteArray(Charsets.UTF_8)
        return if (alt) byteArrayOf(0x1b) + bytes else bytes
    }
}
