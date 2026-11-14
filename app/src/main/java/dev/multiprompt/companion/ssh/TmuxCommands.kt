package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentHarness

enum class TmuxAction {
    ENTER,
    INTERRUPT,
    ESCAPE,
    SCROLL_UP,
    SCROLL_BOTTOM,
}

object TmuxCommands {
    const val SNAPSHOT_PREFIX = "__MP_TMUX_SNAPSHOT__"
    const val SNAPSHOT_PACKED_MARKER = "gz:"
    const val ALT_PREFIX = "__MP_TMUX_ALT__"
    const val CREATED_PREFIX = "__MP_TMUX_CREATED__"
    private val LIVE_CHAT_SID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val LIVE_CHAT_NAME = Regex("live-[A-Za-z0-9_.:-]{1,59}")
    private val LIVE_CHAT_FOLDER = Regex("[A-Za-z0-9/_.~-]{1,4096}")

    const val ETA_PREFIX = "__MP_TMUX_ETA__"

    fun etaRowsCommand(sessionName: String? = null): String {
        val argument = sessionName?.let { " " + TmuxParser.shellQuote(it) } ?: ""
        return "python3 -c '$ETA_PYTHON'$argument 2>/dev/null || true"
    }

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
        "for n, st in states.items() if isinstance(st, dict) and (not want or n == want)))\n" +
        "if not want:\n" +
        "    import glob\n" +
        "    best={}\n" +
        "    for p in glob.glob(os.path.join(d,\"agent-host\",\"*.state.json\")):\n" +
        "        try:\n" +
        "            s=json.load(open(p)); m=os.path.getmtime(p); t=s.get(\"tmux\"); r=s.get(\"result\")\n" +
        "            a=int(float(r.get(\"at\") or 0)) if isinstance(r, dict) else 0\n" +
        "        except Exception:\n" +
        "            continue\n" +
        "        if isinstance(t, str) and t and (t not in best or m > best[t][0]):\n" +
        "            best[t]=(m, a, 0 if s.get(\"inflight\") else 1)\n" +
        "    sys.stdout.write(\"\".join(\"@reply\\t%s\\t%d\\t%d\\n\" % (t, a, w) for t, (m, a, w) in best.items()))\n" +
        "    prov={}\n" +
        "    for p in glob.glob(os.path.join(d,\"agent-host\",\"*.config.json\")):\n" +
        "        try:\n" +
        "            c=json.load(open(p)); m=os.path.getmtime(p); t=c.get(\"tmux\"); v=c.get(\"provider\")\n" +
        "        except Exception:\n" +
        "            continue\n" +
        "        if c.get(\"enabled\") is True and isinstance(t, str) and t and isinstance(v, str) and (t not in prov or m > prov[t][0]):\n" +
        "            prov[t]=(m, v)\n" +
        "    sys.stdout.write(\"\".join(\"@provider\\t%s\\t%s\\n\" % (t, v) for t, (m, v) in prov.items()))"

    fun etaLogCommand(): String =
        "mp_dir=\"\${MULTIPROMPT_STATE_DIR:-\$HOME/.local/state/multiprompt}\"; " +
            "if [ -f \"\$mp_dir/eta-log.jsonl\" ]; then " +
            "tail -c 65536 \"\$mp_dir/eta-log.jsonl\" | od -An -v -tx1 | tr -d ' \\n'; fi"

    fun capture(sessionName: String): String = captureCommand(target(sessionName))

    private fun captureCommand(target: String): String =
        "if [ \"\$(tmux display-message -p -t $target '#{alternate_on}' 2>/dev/null)\" = 1 ]; " +
            "then tmux capture-pane -p -J -t $target; " +
            "else tmux capture-pane -p -J -S -5000 -t $target; fi"

    fun action(sessionName: String, action: TmuxAction): String {
        val key = when (action) {
            TmuxAction.ENTER -> "Enter"
            TmuxAction.INTERRUPT -> "C-c"
            TmuxAction.ESCAPE -> "Escape"
            TmuxAction.SCROLL_BOTTOM -> "End"
            TmuxAction.SCROLL_UP -> "PageUp"
        }
        return "tmux send-keys -t ${target(sessionName)} $key"
    }

    fun questionAnswer(sessionName: String, question: dev.multiprompt.companion.reader.PendingQuestion,
        screen: String, choices: Set<Int>, other: String): String {
        require(choices.all { it in question.options.indices })
        require(choices.isNotEmpty() || other.isNotBlank())
        require(question.multiSelect || choices.size + (if (other.isNotBlank()) 1 else 0) == 1)
        require(question.other || other.isEmpty())
        val activeScreen = checkNotNull(question.activeScreen(screen)) { "This question is no longer waiting for an answer" }
        val destination = target(sessionName)
        val commands = mutableListOf<String>()
        if (question.multiSelect) {
            val rows = activeScreen.lines().map(String::trim).filter { Regex("^[❯›>]?\\s*[1-9][.)]").containsMatchIn(it) }
            val current = rows.indexOfFirst { it.startsWith('❯') || it.startsWith('›') || it.startsWith('>') }
            check(current >= 0 && rows.size >= question.options.size) { "Question selection is unavailable" }
            val checked = rows.mapIndexedNotNull { index, line ->
                index.takeIf { line.contains('☒') || line.contains('☑') || line.contains("[x]") || line.contains("[✓]") }
            }.toSet()
            var cursor = current
            for (index in (checked union choices).sorted()) {
                if ((index in checked) == (index in choices)) continue
                val keys = List(kotlin.math.abs(index - cursor)) { if (index > cursor) "Down" else "Up" } + "Space"
                commands.add("tmux send-keys -t $destination " + keys.joinToString(" "))
                cursor = index
            }
            if (other.isNotBlank()) {
                val keys = List(kotlin.math.abs(question.options.size - cursor)) { if (question.options.size > cursor) "Down" else "Up" } + "Space"
                commands.add("tmux send-keys -t $destination " + keys.joinToString(" "))
            } else commands.add("tmux send-keys -t $destination Enter")
        } else {
            val index = if (other.isNotBlank()) question.options.size else choices.single()
            if (question.options.isEmpty()) {
                check(other.isNotBlank())
            } else if (other.isNotBlank() && question.provider == "codex") {
                val rows = activeScreen.lines().map(String::trim).filter { Regex("^[❯›>]?\\s*[1-9][.)]").containsMatchIn(it) }
                val current = rows.indexOfFirst { it.startsWith('❯') || it.startsWith('›') || it.startsWith('>') }
                check(current >= 0) { "Question selection is unavailable" }
                val keys = List(kotlin.math.abs(index - current)) { if (index > current) "Down" else "Up" } + "Enter"
                commands.add("tmux send-keys -t $destination " + keys.joinToString(" "))
            } else commands.add("tmux send-keys -t $destination ${index + 1}")
        }
        if (other.isNotBlank()) {
            commands.add("(" + pastePrompt(sessionName) + ")")
        }
        return commands.joinToString(" && ")
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
            "sleep 0.3 && " +
            "tmux send-keys -t ${target(sessionName)} Enter"

    fun createClaudeSession(sessionName: String, remotePath: String): String =
        createAgentSession(sessionName, remotePath, AgentHarness.CLAUDE)

    fun createLiveChat(
        sessionName: String,
        remotePath: String,
        sid: String,
        harness: AgentHarness = AgentHarness.CLAUDE,
    ): String {
        require(LIVE_CHAT_SID.matches(sid)) { "Invalid live chat id" }
        require(LIVE_CHAT_NAME.matches(sessionName)) { "Invalid live chat name" }
        require(LIVE_CHAT_FOLDER.matches(remotePath)) { "Live chat needs a folder name without spaces or symbols" }
        val provider = requireNotNull(harness.agent?.hostProvider) { "${harness.label} cannot start a live chat" }
        val path = TmuxParser.shellQuote(remotePath)
        val name = TmuxParser.shellQuote(sessionName)
        val reader = "python3 ~/.multiprompt-runtime/reader.py"
        val mode = if (harness == AgentHarness.CLAUDE) " --permission-mode bypassPermissions" else ""
        val host = "$reader host enable $provider $sid --tmux $sessionName --cwd $remotePath" +
            "$mode >/dev/null 2>&1; " +
            "$reader host start $provider $sid >/dev/null 2>&1; exec sleep infinity"
        return "if [ ! -d $path ]; then printf 'Project directory not found\\n' >&2; exit 2; fi; " +
            "if ! bash -lc 'command -v ${harness.binary} >/dev/null 2>&1'; then " +
            "printf '${harness.binary} was not found on the VPS PATH\\n' >&2; exit 3; fi; " +
            "python3 - host status $provider $sid >/dev/null 2>&1; " +
            "if [ ! -f ~/.multiprompt-runtime/reader.py ]; then " +
            "printf 'Live chat reader could not be installed\\n' >&2; exit 4; fi; " +
            "tmux new-session -d -s $name -c $path ${TmuxParser.shellQuote(host)} && " +
            "printf '$CREATED_PREFIX%s\\n' $name"
    }

    fun createShellSession(sessionName: String, remotePath: String): String =
        createAgentSession(sessionName, remotePath, AgentHarness.SHELL)

    fun createAgentSession(
        sessionName: String,
        remotePath: String,
        harness: AgentHarness,
    ): String {
        val path = TmuxParser.shellQuote(remotePath)
        val name = TmuxParser.shellQuote(sessionName)
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

    fun dissolveSession(sessionName: String): String {
        val target = sessionTarget(sessionName)
        return "if tmux has-session -t $target 2>/dev/null; then tmux kill-session -t $target; fi"
    }

    fun resurrectSession(
        sessionName: String,
        displayName: String,
        workingDirectory: String,
        resumeCommand: String,
    ): String {
        val fresh = freshSessionName(sessionName)
        val session = TmuxParser.shellQuote(fresh)
        val target = target(fresh)
        val directory = TmuxParser.shellQuote(workingDirectory)
        val command = TmuxParser.shellQuote(resumeCommand)
        val label = TmuxParser.shellQuote(displayName)
        val create = if (workingDirectory.isBlank()) {
            "tmux new-session -d -s $session"
        } else {
            "tmux new-session -d -s $session -c $directory"
        }
        val rename = if (displayName.isBlank()) "" else " && tmux rename-window -t $target $label"
        return "if tmux has-session -t ${sessionTarget(fresh)} 2>/dev/null; then " +
            "printf 'session_exists\\n' >&2; exit 2; fi; " +
            "$create && tmux send-keys -t $target -l -- $command && " +
            "tmux send-keys -t $target Enter$rename"
    }

    fun resurrectLiveChat(sessionName: String, sid: String): String {
        require(LIVE_CHAT_SID.matches(sid)) { "Invalid live chat id" }
        val fresh = freshSessionName(sessionName)
        require(LIVE_CHAT_NAME.matches(fresh)) { "Invalid live chat name" }
        val name = TmuxParser.shellQuote(fresh)
        val reader = "python3 ~/.multiprompt-runtime/reader.py"
        val host = "${liveChatProvider(sid)}; " +
            "$reader host stop \$mp_p $sid >/dev/null 2>&1; " +
            "$reader host enable \$mp_p $sid --tmux $fresh >/dev/null 2>&1; " +
            "$reader host start \$mp_p $sid >/dev/null 2>&1; exec sleep infinity"
        return "if [ ! -f ~/.multiprompt-runtime/reader.py ]; then " +
            "printf 'Live chat reader is missing\\n' >&2; exit 4; fi; " +
            "if tmux has-session -t ${sessionTarget(fresh)} 2>/dev/null; then " +
            "printf 'session_exists\\n' >&2; exit 2; fi; " +
            "tmux new-session -d -s $name ${TmuxParser.shellQuote(host)}"
    }

    fun liveChatProvider(sid: String): String {
        require(LIVE_CHAT_SID.matches(sid)) { "Invalid live chat id" }
        return "mp_p=claude; for mp_c in claude codex pi hax cursor; do " +
            "mp_k=\$(printf '%s' \"\$mp_c:$sid\" | sha256sum | cut -c1-32); " +
            "if [ -f \"\$HOME/.local/state/multiprompt/agent-host/\$mp_k.config.json\" ]; then mp_p=\$mp_c; break; fi; done"
    }

    fun freshSessionName(sessionName: String): String {
        val base = sessionName.take(40).trimEnd('-', '.', ':')
        return "$base-r${System.currentTimeMillis() % 100000}"
    }

    fun latestClaudeSessionId(workingDirectory: String): String {
        val path = TmuxParser.shellQuote(workingDirectory)
        return "mp_cwd=$path; " +
            "mp_enc=\$(printf '%s' \"\$mp_cwd\" | sed 's/[^A-Za-z0-9]/-/g'); " +
            "ls -t \"\$HOME/.claude/projects/\$mp_enc\" 2>/dev/null | " +
            "grep '\\.jsonl\$' | head -1 | sed 's/\\.jsonl\$//'"
    }

    fun renameWindow(sessionName: String, displayName: String): String =
        "tmux rename-window -t ${target(sessionName)} ${TmuxParser.shellQuote(displayName)}"

    fun rename(sessionName: String, displayName: String, tellAgent: Boolean): String =
        if (tellAgent) "${renameWindow(sessionName, displayName)} && ${pastePrompt(sessionName)}"
        else renameWindow(sessionName, displayName)

    fun attach(sessionName: String): String =
        "exec tmux attach-session -f ignore-size -t ${sessionTarget(sessionName)}"

    internal fun sessionRows(format: String): String {
        val destination = "\"${exactName("\$mp_session")}:\""
        return "tmux list-sessions -F '#{session_name}' | while IFS= read -r mp_session; do " +
            "mp_row=\$(tmux display-message -p -t $destination -F ${TmuxParser.shellQuote(format)}) || continue; " +
            "case \"\$mp_row\" in *'${TmuxParser.FIELD_SEPARATOR}'*) ;; *) continue;; esac; " +
            "mp_name=\${mp_row%%${TmuxParser.FIELD_SEPARATOR}*}; " +
            "[ \"\$mp_name\" = \"\$mp_session\" ] || continue; " +
            "printf '%s\\n' \"\$mp_row\"; " +
            "printf '${TmuxParser.PREVIEW_PREFIX}'; " +
            "tmux capture-pane -p -J -S -12 -t $destination 2>/dev/null | " +
            "tail -c 4096 | od -An -v -tx1 | tr -d ' \\n'; " +
            "printf '\\n'; done; "
    }

    internal fun hostedRows(): String =
        "if [ -f ~/.multiprompt-runtime/reader.py ]; then " +
            "python3 ~/.multiprompt-runtime/reader.py host list 2>/dev/null | python3 -c '$HOSTED_PYTHON' 2>/dev/null; fi; "

    private const val HOSTED_PYTHON = "import json,re,subprocess,sys\n" +
        "sep=\"${TmuxParser.FIELD_SEPARATOR}\"\n" +
        "try:\n" +
        "    chats=json.load(sys.stdin).get(\"chats\") or []\n" +
        "except Exception:\n" +
        "    chats=[]\n" +
        "try:\n" +
        "    physical=set(subprocess.run([\"tmux\",\"list-sessions\",\"-F\",\"#{session_name}\"],capture_output=True,text=True,timeout=5).stdout.splitlines())\n" +
        "except Exception:\n" +
        "    physical=set()\n" +
        "for c in chats:\n" +
        "    if not isinstance(c, dict) or c.get(\"visible\") is False:\n" +
        "        continue\n" +
        "    n=c.get(\"name\"); s=c.get(\"sid\"); p=c.get(\"cwd\"); a=c.get(\"activity\")\n" +
        "    if not isinstance(n, str) or not isinstance(s, str) or n in physical:\n" +
        "        continue\n" +
        "    if not re.fullmatch(r\"live-[A-Za-z0-9_.:-]{1,59}\", n) or not re.fullmatch(r\"[A-Za-z0-9_-]{1,80}\", s):\n" +
        "        continue\n" +
        "    if not isinstance(p, str) or sep in p or \"\\n\" in p or \"\\r\" in p:\n" +
        "        p=\"\"\n" +
        "    if not isinstance(a, int):\n" +
        "        a=0\n" +
        "    print(sep.join([n, \"1\", \"0\", str(a), \"0\", \"0\", p, \"\", \"sleep\", \"\", s, str(a), \"\"]))"

    fun stream(sessionName: String, hosted: Boolean = false): String {
        val target = target(sessionName)
        val session = sessionTarget(sessionName)
        val missing = if (hosted) {
            "printf '${ALT_PREFIX}0\\n'; printf '$ETA_PREFIX\\n'; printf '$SNAPSHOT_PREFIX\\n'; " +
                "while true; do sleep 30; printf '\\n'; done; "
        } else {
            "printf 'tmux session is no longer available\\n' >&2; exit 1; "
        }
        return "if ! tmux has-session -t $session 2>/dev/null; then " +
            missing + "fi; " +
            "mp_snapshot=\$(mktemp) || exit 2; " +
            "trap 'rm -f \"\$mp_snapshot\"' EXIT HUP INT TERM; " +
            "mp_previous=''; " +
            "if command -v gzip >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1; " +
            "then mp_packed=1; else mp_packed=0; fi; " +
            "while tmux has-session -t $session 2>/dev/null; do " +
            "mp_alternate=\$(tmux display-message -p -t $target '#{alternate_on}' 2>/dev/null); " +
            "printf '${ALT_PREFIX}%s\\n' \"\$mp_alternate\"; " +
            "if [ \"\$(tmux display-message -p -t $target '#{pane_in_mode}' 2>/dev/null)\" = 1 ]; " +
            "then sleep 1; continue; fi; " +
            "{ ${captureCommand(target)} ; } 2>/dev/null | " +
            "tail -c 524288 > \"\$mp_snapshot\"; " +
            "mp_eta=\$(${etaRowsCommand(sessionName)}); " +
            "mp_current=\$(cksum < \"\$mp_snapshot\")-\$(printf '%s' \"\$mp_eta\" | cksum)-\$mp_alternate; " +
            "if [ \"\$mp_current\" != \"\$mp_previous\" ]; then " +
            "printf '$ETA_PREFIX'; printf '%s' \"\$mp_eta\" | od -An -v -tx1 | tr -d ' \\n'; printf '\\n'; " +
            "printf '$SNAPSHOT_PREFIX'; " +
            "if [ \"\$mp_packed\" = 1 ]; then printf '$SNAPSHOT_PACKED_MARKER'; " +
            "gzip -c < \"\$mp_snapshot\" | base64 | tr -d '\\n'; " +
            "else od -An -v -tx1 < \"\$mp_snapshot\" | tr -d ' \\n'; fi; " +
            "printf '\\n'; mp_previous=\$mp_current; fi; " +
            "sleep 1; done; " +
            "printf 'tmux session ended\\n' >&2; exit 1"
    }

    private fun exactName(sessionName: String): String = "=$sessionName"

    private fun sessionTarget(sessionName: String): String = TmuxParser.shellQuote(exactName(sessionName))

    private fun target(sessionName: String): String = TmuxParser.shellQuote("${exactName(sessionName)}:")
}
