package dev.multiprompt.companion.ssh

enum class TmuxAction {
    ENTER,
    INTERRUPT,
}

/** The complete allowlist of remote commands the mobile reader can run. */
object TmuxCommands {
    const val SNAPSHOT_PREFIX = "__MP_TMUX_SNAPSHOT__"

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

    fun stream(sessionName: String): String {
        val target = target(sessionName)
        return "if ! tmux has-session -t $target 2>/dev/null; then " +
            "printf 'tmux session is no longer available\\n' >&2; exit 1; fi; " +
            "while tmux has-session -t $target 2>/dev/null; do " +
            "printf '$SNAPSHOT_PREFIX'; " +
            "tmux capture-pane -p -J -S -200 -t $target 2>/dev/null | " +
            "tail -c 131072 | od -An -v -tx1 | tr -d ' \\n'; " +
            "printf '\\n'; sleep 1; done; " +
            "printf 'tmux session ended\\n' >&2; exit 1"
    }

    private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")
}
