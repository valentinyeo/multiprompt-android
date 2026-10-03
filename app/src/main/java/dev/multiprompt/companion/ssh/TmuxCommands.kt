package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentHarness

enum class TmuxAction {
    ENTER,
    INTERRUPT,
    /** Scrolls the agent TUI's own scrollback up one page (alternate-screen panes). */
    SCROLL_UP,
    /** Returns to the live view. */
    SCROLL_BOTTOM,
}

/** The complete allowlist of remote commands the mobile reader can run. */
object TmuxCommands {
    const val SNAPSHOT_PREFIX = "__MP_TMUX_SNAPSHOT__"
    const val ALT_PREFIX = "__MP_TMUX_ALT__"
    const val CREATED_PREFIX = "__MP_TMUX_CREATED__"

    /** Reader stream line carrying the session's agent time estimate, hex encoded. */
    const val ETA_PREFIX = "__MP_TMUX_ETA__"

    /**
     * Prints this host's agent time estimates as
     * `name<TAB>remaining<TAB>total<TAB>description` rows, one per open estimate. The remaining
     * time is computed on the host, so a clock difference between phone and host cannot distort
     * a countdown. A session name narrows it to that one session. The output is empty when the
     * host never installed the estimate hooks, and every failure is swallowed so a missing or
     * corrupt state file can never break a reader command.
     */
    fun etaRowsCommand(sessionName: String? = null): String {
        val argument = sessionName?.let { " " + TmuxParser.shellQuote(it) } ?: ""
        return "python3 -c '$ETA_PYTHON'$argument 2>/dev/null || true"
    }

    /**
     * Prints one tab-separated row per open estimate ("name<TAB>remaining<TAB>total<TAB>desc")
     * and one per recorded lifecycle state ("@state<TAB>name<TAB>state<TAB>recorded at"), so a
     * reader gets both the countdown and the hook-accurate working/ready state from one read.
     * Every read is guarded: a missing or corrupt file can never take the estimate rows with it.
     */
    private const val ETA_PYTHON = "import json,os,sys,time\n" +
        "d=os.environ.get(\"MULTIPROMPT_STATE_DIR\") or os.path.join(os.path.expanduser(\"~\"),\".local/state/multiprompt\")\n" +
        "now=int(time.time())\n" +
        "want=sys.argv[1] if len(sys.argv)>1 else \"\"\n" +
        "try:\n" +
        "    data=json.load(open(os.path.join(d,\"eta-open.json\")))\n" +
        "except Exception:\n" +
        "    data={}\n" +
        "sys.stdout.write(\"\".join(\"%s\\t%d\\t%d\\t%s\\n\" % (n, " +
        "max(0, int(e.get(\"started_at\") or now) + int(e.get(\"est_seconds\") or 0) - now), " +
        "int(e.get(\"est_seconds\") or 0), \" \".join(str(e.get(\"desc\") or \"\").split())) " +
        "for n, e in data.items() if isinstance(e, dict) and (not want or n == want)))\n" +
        "try:\n" +
        "    states=json.load(open(os.path.join(d,\"tab-state.json\")))\n" +
        "except Exception:\n" +
        "    states={}\n" +
        "sys.stdout.write(\"\".join(\"@state\\t%s\\t%s\\t%d\\n\" % (n, str(st.get(\"state\") or \"\"), " +
        "int(st.get(\"at\") or 0)) " +
        "for n, st in states.items() if isinstance(st, dict) and (not want or n == want)))"

    /**
     * The finished-estimate log, hex encoded so it survives the line framing. The last 64 KiB
     * is plenty for the accuracy median and keeps a long log from stalling the phone.
     */
    fun etaLogCommand(): String =
        "mp_dir=\"\${MULTIPROMPT_STATE_DIR:-\$HOME/.local/state/multiprompt}\"; " +
            "if [ -f \"\$mp_dir/eta-log.jsonl\" ]; then " +
            "tail -c 65536 \"\$mp_dir/eta-log.jsonl\" | od -An -v -tx1 | tr -d ' \\n'; fi"

    fun capture(sessionName: String): String = captureCommand(target(sessionName))

    /**
     * Full-screen agent TUIs draw in the alternate screen, so the pane's scrollback holds
     * whatever ran before they started, not their conversation. Mixing the two put
     * hours-old output directly above the current turn. Take history only when the pane is
     * on the normal screen.
     */
    private fun captureCommand(target: String): String =
        "if [ \"\$(tmux display-message -p -t $target '#{alternate_on}' 2>/dev/null)\" = 1 ]; " +
            "then tmux capture-pane -p -J -t $target; " +
            "else tmux capture-pane -p -J -S -5000 -t $target; fi"

    fun action(sessionName: String, action: TmuxAction): String {
        val key = when (action) {
            TmuxAction.ENTER -> "Enter"
            TmuxAction.INTERRUPT -> "C-c"
            TmuxAction.SCROLL_BOTTOM -> "End"
            TmuxAction.SCROLL_UP -> "PageUp"
        }
        return "tmux send-keys -t ${target(sessionName)} $key"
    }

    fun modelPickerOption(sessionName: String, index: Int): String {
        require(index in 1..9) { "Model picker option must be between 1 and 9" }
        return "tmux send-keys -t ${target(sessionName)} $index"
    }

    fun pastePrompt(sessionName: String): String =
        "mp_buffer=mp-android-\$\$; " +
            "trap 'tmux delete-buffer -b \"\$mp_buffer\" 2>/dev/null || true' EXIT HUP INT TERM; " +
            "tmux load-buffer -b \"\$mp_buffer\" - && " +
            "tmux paste-buffer -dpr -b \"\$mp_buffer\" -t ${target(sessionName)} && " +
            // Agent TUIs read a bracketed paste asynchronously. An Enter that lands in the
            // same read chunk is swallowed as part of the paste, which leaves the prompt
            // sitting unsent in the composer.
            "sleep 0.3 && " +
            "tmux send-keys -t ${target(sessionName)} Enter"

    fun createClaudeSession(sessionName: String, remotePath: String): String =
        createAgentSession(sessionName, remotePath, AgentHarness.CLAUDE)

    fun createShellSession(sessionName: String, remotePath: String): String =
        createAgentSession(sessionName, remotePath, AgentHarness.SHELL)

    /**
     * Creates the tmux session and starts [harness] in [remotePath]. The binary, its flags and
     * the login-shell wrapper are constants; only the quoted directory and session name are
     * interpolated, so the request can never carry a command of its own.
     */
    fun createAgentSession(
        sessionName: String,
        remotePath: String,
        harness: AgentHarness,
    ): String {
        val path = TmuxParser.shellQuote(remotePath)
        val name = TmuxParser.shellQuote(sessionName)
        // An SSH exec channel runs a non-login, non-interactive shell whose PATH omits
        // ~/.local/bin (added by .profile, read only by login shells). The agent lookup and
        // the launch must both go through a login shell or the binary is never found.
        val check = if (harness.startsAnAgent) {
            "if ! bash -lc 'command -v ${harness.binary} >/dev/null 2>&1'; then " +
                "printf '${harness.binary} was not found on the VPS PATH\\n' >&2; exit 3; fi; "
        } else {
            ""
        }
        val launch = if (harness.startsAnAgent) {
            val line = "exec ${harness.binary}${if (harness.arguments.isBlank()) "" else " ${harness.arguments}"}"
            " 'exec bash -lc \"$line\"'"
        } else {
            ""
        }
        return "if [ ! -d $path ]; then printf 'Project directory not found\\n' >&2; exit 2; fi; " +
            "$check" +
            "tmux new-session -d -s $name -c $path$launch && " +
            "printf '$CREATED_PREFIX%s\\n' $name"
    }

    /**
     * Read-only listing of the folders directly inside [path]. Each line is "name<TAB>git",
     * where git is 1 for a folder holding a .git entry (a directory or a worktree file).
     * Hidden folders and files are skipped, and the caller caps how much it reads. A null
     * [path] starts at the host's own projects folder, falling back to its home directory.
     */
    fun listDirectories(path: String? = null): String {
        val quoted = path?.let(TmuxParser::shellQuote)
        return if (quoted == null) {
            "mp_home=\"\$HOME\"; " +
                "if [ -d \"\$mp_home/projects\" ]; then mp_root=\"\$mp_home/projects\"; " +
                "else mp_root=\"\$mp_home\"; fi; " +
                "printf '@root\\t%s\\n' \"\$mp_root\"; " +
                "cd \"\$mp_root\" || exit 2; " +
                DIRECTORY_LIST_BODY
        } else {
            "if [ ! -d $quoted ]; then printf 'Directory not found\\n' >&2; exit 2; fi; " +
                "cd $quoted || exit 2; " +
                "printf '@root\\t%s\\n' \"\$PWD\"; " +
                DIRECTORY_LIST_BODY
        }
    }

    private const val DIRECTORY_LIST_BODY =
        "for mp_entry in *; do " +
            "[ -d \"\$mp_entry\" ] || continue; " +
            "case \"\$mp_entry\" in .*) continue;; esac; " +
            "if [ -e \"\$mp_entry/.git\" ]; then mp_git=1; else mp_git=0; fi; " +
            "printf '%s\\t%s\\n' \"\$mp_entry\" \"\$mp_git\"; " +
            "done"

    /**
     * Ends one session. Idempotent on purpose: when the session is already gone, or the whole
     * tmux server has died, `kill-session` fails with "no server running" and the app used to
     * keep the dead session forever. Asking first means an absent session is success.
     */
    fun dissolveSession(sessionName: String): String {
        val target = TmuxParser.shellQuote("=$sessionName")
        return "if tmux has-session -t $target 2>/dev/null; then tmux kill-session -t $target; fi"
    }

    /**
     * Restores an archived session under a fresh name. Reusing the old name is unsafe: tmux
     * names get recycled from the folder name, so a same-name session is usually unrelated
     * work, and attaching to it dropped people into the wrong conversation (the desktop hit
     * exactly that). The window is renamed back to the archived label so the row still reads
     * the same.
     */
    fun resurrectSession(
        sessionName: String,
        displayName: String,
        workingDirectory: String,
        resumeCommand: String,
    ): String {
        val fresh = freshSessionName(sessionName)
        val session = TmuxParser.shellQuote(fresh)
        val target = TmuxParser.shellQuote("$fresh:")
        val directory = TmuxParser.shellQuote(workingDirectory)
        val command = TmuxParser.shellQuote(resumeCommand)
        val label = TmuxParser.shellQuote(displayName)
        val create = if (workingDirectory.isBlank()) {
            "tmux new-session -d -s $session"
        } else {
            "tmux new-session -d -s $session -c $directory"
        }
        val rename = if (displayName.isBlank()) "" else " && tmux rename-window -t $target $label"
        return "if tmux has-session -t $session 2>/dev/null; then " +
            "printf 'session_exists\\n' >&2; exit 2; fi; " +
            "$create && tmux send-keys -t $target -l -- $command && " +
            "tmux send-keys -t $target Enter$rename"
    }

    /** A restore name that cannot collide with an unrelated session of the same base name. */
    fun freshSessionName(sessionName: String): String {
        val base = sessionName.take(40).trimEnd('-', '.', ':')
        return "$base-r${System.currentTimeMillis() % 100000}"
    }

    /**
     * The newest Claude conversation recorded for [workingDirectory]. Claude Code writes one
     * jsonl per conversation under ~/.claude/projects/<path with punctuation as dashes>/, and
     * the newest one is what a bare continue would pick. Reading it at archive time makes the
     * later restore resume that exact conversation, not whatever became newest since.
     */
    fun latestClaudeSessionId(workingDirectory: String): String {
        val path = TmuxParser.shellQuote(workingDirectory)
        return "mp_cwd=$path; " +
            "mp_enc=\$(printf '%s' \"\$mp_cwd\" | sed 's/[^A-Za-z0-9]/-/g'); " +
            "ls -t \"\$HOME/.claude/projects/\$mp_enc\" 2>/dev/null | " +
            "grep '\\.jsonl\$' | head -1 | sed 's/\\.jsonl\$//'"
    }

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
            "printf '${ALT_PREFIX}%s\\n' \"\$(tmux display-message -p -t $target '#{alternate_on}' 2>/dev/null)\"; " +
            "if [ \"\$(tmux display-message -p -t $target '#{pane_in_mode}' 2>/dev/null)\" = 1 ]; " +
            "then sleep 1; continue; fi; " +
            "{ ${captureCommand(target)} ; } 2>/dev/null | " +
            "tail -c 524288 > \"\$mp_snapshot\"; " +
            "mp_eta=\$(${etaRowsCommand(sessionName)}); " +
            "mp_current=\$(cksum < \"\$mp_snapshot\")-\$(printf '%s' \"\$mp_eta\" | cksum); " +
            "if [ \"\$mp_current\" != \"\$mp_previous\" ]; then " +
            "printf '$ETA_PREFIX'; printf '%s' \"\$mp_eta\" | od -An -v -tx1 | tr -d ' \\n'; printf '\\n'; " +
            "printf '$SNAPSHOT_PREFIX'; " +
            "od -An -v -tx1 < \"\$mp_snapshot\" | tr -d ' \\n'; " +
            "printf '\\n'; mp_previous=\$mp_current; fi; " +
            "sleep 1; done; " +
            "printf 'tmux session ended\\n' >&2; exit 1"
    }

    private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")
}
