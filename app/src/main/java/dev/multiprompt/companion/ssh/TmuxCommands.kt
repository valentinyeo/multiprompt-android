package dev.multiprompt.companion.ssh

enum class TmuxAction {
    ENTER,
    INTERRUPT,
}

/** The complete allowlist of remote commands the mobile reader can run. */
object TmuxCommands {
    const val SNAPSHOT_PREFIX = "__MP_TMUX_SNAPSHOT__"
    const val CREATED_PREFIX = "__MP_TMUX_CREATED__"

    fun capture(sessionName: String): String =
        "tmux capture-pane -p -J -S -2000 -t ${target(sessionName)}"

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

    fun createClaudeSession(sessionName: String, remotePath: String): String {
        val path = TmuxParser.shellQuote(remotePath)
        val name = TmuxParser.shellQuote(sessionName)
        return "if [ ! -d $path ]; then printf 'Project directory not found\\n' >&2; exit 2; fi; " +
            "if ! command -v claude >/dev/null 2>&1; then printf 'Claude Code is not installed\\n' >&2; exit 3; fi; " +
            "tmux new-session -d -s $name -c $path " +
            "'exec claude --dangerously-skip-permissions' && " +
            "printf '$CREATED_PREFIX%s\\n' $name"
    }

    fun createShellSession(sessionName: String, remotePath: String): String {
        val path = TmuxParser.shellQuote(remotePath)
        val name = TmuxParser.shellQuote(sessionName)
        return "if [ ! -d $path ]; then printf 'Project directory not found\\n' >&2; exit 2; fi; " +
            "tmux new-session -d -s $name -c $path && " +
            "printf '$CREATED_PREFIX%s\\n' $name"
    }

    fun dissolveSession(sessionName: String): String =
        "tmux kill-session -t ${TmuxParser.shellQuote("=$sessionName")}"

    fun renameWindow(sessionName: String, displayName: String): String =
        "tmux rename-window -t ${target(sessionName)} ${TmuxParser.shellQuote(displayName)}"

    fun stream(sessionName: String): String {
        val target = target(sessionName)
        return "if ! tmux has-session -t $target 2>/dev/null; then " +
            "printf 'tmux session is no longer available\\n' >&2; exit 1; fi; " +
            "mp_snapshot=\$(mktemp) || exit 2; " +
            "trap 'rm -f \"\$mp_snapshot\"' EXIT HUP INT TERM; " +
            "mp_previous=''; " +
            "while tmux has-session -t $target 2>/dev/null; do " +
            "tmux capture-pane -p -J -S -2000 -t $target 2>/dev/null | " +
            "tail -c 524288 > \"\$mp_snapshot\"; " +
            "mp_current=\$(cksum < \"\$mp_snapshot\"); " +
            "if [ \"\$mp_current\" != \"\$mp_previous\" ]; then " +
            "printf '$SNAPSHOT_PREFIX'; " +
            "od -An -v -tx1 < \"\$mp_snapshot\" | tr -d ' \\n'; " +
            "printf '\\n'; mp_previous=\$mp_current; fi; " +
            "sleep 1; done; " +
            "printf 'tmux session ended\\n' >&2; exit 1"
    }

    private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")
}
