package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentHarness

enum class TmuxAction {
    ENTER,
    INTERRUPT,
    SCROLL_UP,
    SCROLL_BOTTOM,
}

object TmuxCommands {
    const val SNAPSHOT_PREFIX = "__MP_TMUX_SNAPSHOT__"
    const val ALT_PREFIX = "__MP_TMUX_ALT__"
    const val CREATED_PREFIX = "__MP_TMUX_CREATED__"

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
        "for n, st in states.items() if isinstance(st, dict) and (not want or n == want)))"

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
            TmuxAction.SCROLL_BOTTOM -> "End"
            TmuxAction.SCROLL_UP -> "PageUp"
        }
        return "tmux send-keys -t ${target(sessionName)} $key"
    }

    fun questionAnswer(sessionName: String, question: ClaudeQuestion, screen: String, index: Int): String {
        require(question.options.any { it.index == index })
        check(ClaudeQuestion.parse(screen) == question) { "This question is no longer waiting for an answer" }
        return modelPickerOption(sessionName, index)
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

    const val CANCEL_PREFIX = "__MP_TMUX_CANCEL__"

    fun cancelQueued(sessionName: String): String =
        "python3 -c '$CANCEL_PYTHON' ${target(sessionName)} 2>&1"

    private val CANCEL_PYTHON = """
import json,re,subprocess,sys,time
t=sys.argv[1]
q=json.load(sys.stdin)
text=q["text"]
keep=q["keep"]
def tmux(*a,data=None):
    return subprocess.run(["tmux"]+list(a),input=data,capture_output=True,timeout=8)
def cells(line):
    out=[]
    dim=False
    for m in re.finditer(r"\x1b\[([0-9;]*)m|\x1b\[[0-9;?]*[A-Za-z]|(.)",line):
        if m.group(2) is not None:
            out.append((m.group(2),dim))
        elif m.group(1) is not None:
            codes=(m.group(1) or "0").split(";")
            i=0
            while i<len(codes):
                c=codes[i]
                if c in ("38","48"):
                    i+=2 if i+1<len(codes) and codes[i+1]=="5" else 4
                    continue
                if c in ("","0","22"):
                    dim=False
                elif c=="2":
                    dim=True
                i+=1
    return out
def box_rows():
    shown=tmux("capture-pane","-p","-e","-J","-t",t).stdout.decode("utf-8","replace").splitlines()
    rows=[cells(l) for l in shown]
    plain=["".join(c for c,_ in r) for r in rows]
    starts=[i for i,l in enumerate(plain) if l.startswith("❯")]
    if not starts:
        return []
    out=[]
    for i in range(starts[-1],len(rows)):
        if plain[i].startswith("─"):
            break
        out.append(rows[i])
    return out
def visible():
    words=[]
    for n,r in enumerate(box_rows()):
        words.append("".join(c for c,d in (r[1:] if n==0 else r) if not d))
    return " ".join(" ".join(words).split())
def hint():
    plain=" ".join("".join(c for c,_ in r) for r in box_rows())
    if "select a queued message" in plain:
        return "select"
    return "edit" if "edit queued messages" in plain else "none"
def flat(s):
    return "".join(s.split())
def matches(pulled):
    f=flat(pulled)
    if not f:
        return False
    return any("".join(flat(x) for x in keep[:i]+[text]+keep[i:])==f for i in range(len(keep)+1))
def clear():
    for _ in range(len(visible())+8):
        if not visible():
            return True
        tmux("send-keys","-t",t,"C-u")
    return not visible()
def paste(s,enter):
    b="mp-android-cancel-%d"%time.time_ns()
    ok=tmux("load-buffer","-b",b,"-",data=s.encode("utf-8")).returncode==0
    ok=ok and tmux("paste-buffer","-dpr","-b",b,"-t",t).returncode==0
    if enter and ok:
        time.sleep(0.3)
        ok=tmux("send-keys","-t",t,"Enter").returncode==0
    return ok
def finish(status):
    print("$CANCEL_PREFIX"+status)
    sys.exit(0)
draft=visible()
if draft and not clear():
    finish("box_not_clear")
state=hint()
status="ok"
if state=="select":
    status="unsupported"
elif state=="none":
    status="not_queued"
else:
    tmux("send-keys","-t",t,"Up")
    pulled=""
    for _ in range(20):
        time.sleep(0.1)
        pulled=visible()
        if pulled:
            break
    if not matches(pulled):
        status="queue_mismatch"
    elif not clear():
        status="box_not_clear"
    else:
        for k in keep:
            if not paste(k,True):
                status="requeue_failed"
if draft and status not in ("box_not_clear","queue_mismatch") and not visible():
    paste(draft,False)
finish(status)
""".trim()

    fun createClaudeSession(sessionName: String, remotePath: String): String =
        createAgentSession(sessionName, remotePath, AgentHarness.CLAUDE)

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
        val target = TmuxParser.shellQuote("=$sessionName")
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
