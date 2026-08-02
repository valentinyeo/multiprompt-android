package dev.multiprompt.companion.ssh

enum class TmuxAction {
    ENTER,
    INTERRUPT,
}

/** The complete allowlist of remote commands the mobile reader can run. */
object TmuxCommands {
    fun capture(sessionName: String): String =
        "tmux capture-pane -p -J -S -200 -t ${target(sessionName)}"

    fun action(sessionName: String, action: TmuxAction): String {
        val key = when (action) {
            TmuxAction.ENTER -> "Enter"
            TmuxAction.INTERRUPT -> "C-c"
        }
        return "tmux send-keys -t ${target(sessionName)} $key"
    }

    fun pastePrompt(sessionName: String): String =
        "mp_buffer=mp-android-\$\$; " +
            "trap 'tmux delete-buffer -b \"\$mp_buffer\" 2>/dev/null || true' EXIT HUP INT TERM; " +
            "tmux load-buffer -b \"\$mp_buffer\" - && " +
            "tmux paste-buffer -dpr -b \"\$mp_buffer\" -t ${target(sessionName)} && " +
            "tmux send-keys -t ${target(sessionName)} Enter"

    private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")
}
