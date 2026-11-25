#!/usr/bin/env bash

set -uo pipefail

port="${ZSB_PORT:-19548}"

json_string() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]), end="")' "$1" 2>/dev/null || {
    local s="${1//\\/\\\\}"
    s="${s//\"/\\\"}"
    printf '"%s"' "$s"
  }
}

json_error() {
  local code="$1"
  local message="$2"
  local hint="${3:-}"
  printf '{"ok":false,"error":%s,"message":%s' "$(json_string "$code")" "$(json_string "$message")"
  if [ -n "${sess:-}" ]; then
    printf ',"session":%s' "$(json_string "$sess")"
  fi
  printf ',"port":%s' "$port"
  if [ -n "$hint" ]; then
    printf ',"hint":%s' "$(json_string "$hint")"
  fi
  printf '}\n'
}

print_help() {
  if [ -n "${1:-}" ]; then
    local deadline=15
    case "$1" in
      status|version) deadline=5 ;;
      doctor) deadline=20 ;;
      text|get-text|snapshot|cdp|eval|script) deadline=30 ;;
      screenshot|shoturl) deadline=60 ;;
      selftest) deadline=180 ;;
    esac
    case "$1" in
      text|get-text|snapshot|links)
        local unit=characters default_limit=2000
        if [ "$1" = links ]; then unit=links; default_limit=50; fi
        printf 'usage: zsb %s [--limit N | --full] [--tab ID]\n' "$1"
        printf '  --limit N  Maximum %s (default: %s)\n' "$unit" "$default_limit"
        printf '  --full     No practical limit\n  --tab ID   Read a tab in this session by ID, title, or URL match\n'
        printf '  Without --tab, read the target:true tab from zsb tabs (which may not be active).\n'
        ;;
      navigate) echo 'usage: zsb navigate <url> [--new-tab]  (reuses only the untouched agent-created target; otherwise opens a background tab; --new-tab always creates; --activate is ignored)' ;;
      workspace-list) echo 'usage: zsb workspace-list (JSON workspaces, groups, tabs and host names; no session context needed)' ;;
      session-status) echo 'usage: zsb session-status --tab <id>'
        echo '  Reports connecting, connected after a live tmux attachment, or disconnected with exit_code.'
        echo '  An unknown exit code is 4294967295; an unknown tab returns unknown_tab.'
        ;;
      session-open) echo 'usage: zsb session-open --workspace <name> (--group <name> | --after-tab <id>) --host <host name> --tmux <session> --name <tab title>'
        echo '  Names match exactly. Opens an existing remote tmux session in the background; never activates.'
        echo '  --after-tab places the new tab right below tab <id>, in its group.'
        echo '  Replies include state; created:true is not proof of connection. Use session-status --tab <id>.'
        echo '  --tmux accepts 1-63 letters, digits, hyphens, underscores or dots.'
        echo '  --name accepts 1-63 UTF-16 units, without control characters.'
        echo '  An existing tab for the same host/session in any workspace returns existing:true and its workspace name without changes.'
        ;;
      shell-open) echo 'usage: zsb shell-open --workspace <name> (--group <name> | --after-tab <id>) --host <host name> --name <tab title> [--attach <tmux session>] [--run <remote command>]'
        echo '  Opens a server shell tab on the desktop: plain VPS login or attach to a named tmux session.'
        echo '  --attach adds or revives a tmux tab; --run overrides the remote startup command (for example tmux attach -t work).'
        echo '  Without --attach host 0 and 1 open a plain shell; host 2 requires --attach.'
        echo '  --after-tab places the new tab right below tab <id>, in its group.'
        ;;
      session-close) echo 'usage: zsb session-close --tab <id>'
        echo '  Closes one terminal tab in the background. Local (non-remote) tabs are refused with not_remote_tab, the active tab with active_tab, a pinned tab with pinned_tab.'
        ;;
      session-rebind) echo 'usage: zsb session-rebind --tab <id> [--host <host name>] --tmux <session>'
        echo '  Points an existing tab at another remote tmux session and reconnects it, keeping its title and place.'
        echo '  Without --host the tab keeps its host. Another tab already on that host/session returns duplicate_session and its tab id.'
        echo '  Replies include state; check it with session-status --tab <id>.'
        ;;
      session-rename) echo 'usage: zsb session-rename --tab <id> --name <tab title>'
        echo '  --name accepts 1-63 UTF-16 units, without control characters.'
        ;;
      session-wait) echo 'usage: zsb session-wait --tab <id>'
        echo '  Puts one background terminal tab in Waiting until it needs attention, and changes nothing when it already waits.'
        echo '  The active tab is refused with active_tab; an unknown tab with unknown_tab.'
        ;;
      session-move) echo 'usage: zsb session-move --tab <id> [--workspace <name>] [--group <name> | --ungrouped]'
        echo '  Moves a background tab to a workspace and/or group by exact name. Moving to another workspace without --group leaves the tab ungrouped.'
        echo '  The active tab is refused with active_tab; a snoozed workspace is refused with workspace_snoozed.'
        ;;
      session-pin) echo 'usage: zsb session-pin --tab <id>'
        echo '  Pins a background tab to the top of the sidebar in every view. An unknown tab returns unknown_tab.'
        ;;
      session-unpin) echo 'usage: zsb session-unpin --tab <id>'
        echo '  Clears the pin from a background tab. An unknown tab returns unknown_tab.'
        ;;
      session-archive) echo 'usage: zsb session-archive --tab <id>'
        echo '  Archives a remote tab: remembers it, ends its tmux session and removes the tab. An unknown tab returns unknown_tab.'
        ;;
      session-unarchive) echo 'usage: zsb session-unarchive --tmux <session> [--host <host name>]'
        echo '  Reopens one archived session as a tab and drops it from the archive. An unknown session returns unknown_archived_session.'
        ;;
      selftest) echo 'usage: zsb selftest (JSON summary plus human result; nonzero on failure)' ;;
      pane-info) echo 'usage: zsb pane-info (hosting multiprompt tab, active, visible, windowId)' ;;
      reset) echo 'usage: zsb reset (reconnect bridge and clear stuck CDP sessions; keeps the pane open)' ;;
      extension-reload) echo 'usage: zsb extension-reload (reload unpacked extension code; no app restart)' ;;
      tabs) echo 'usage: zsb tabs [--close ID]' ;;
      url) echo 'usage: zsb url [--tab ID]' ;;
      frames) echo 'usage: zsb frames [--tab ID] (read-only frameId, parentFrameId and URL)' ;;
      find) echo 'usage: zsb find <text> [--tab ID]' ;;
      click) echo 'usage: zsb click <selector> [--tab ID] (reports via=cdp or frame-dom-fallback, and trusted)' ;;
      screenshot) echo 'usage: zsb screenshot [-o FILE | --output FILE] [--tab ID]' ;;
      console) echo 'usage: zsb console [-n N | --limit N] [--clear]' ;;
      routing-audit) echo 'usage: zsb routing-audit [--limit N | --full]' ;;
      resolve-window) echo 'usage: zsb resolve-window [--match TEXT] [--focused]' ;;
      emulate)
        cat <<'EMULATE_HELP'
usage: zsb emulate <device|WIDTHxHEIGHT> [--mobile]
       zsb emulate off
Devices: iphone (390x844), pixel (412x915). Sizes must be 1-10000 pixels.
Keep viewport, user agent and touch on this tab across navigations until off.
Custom sizes are desktop unless --mobile is supplied. Reload to refresh UA-dependent content.
F12 and browser DevTools are outside page automation; this does not open DevTools.
Examples: zsb emulate iphone; zsb emulate 390x844 --mobile; zsb emulate off
EMULATE_HELP
        ;;
      cdp)
        cat <<'CDP_HELP'
usage: zsb cdp <method> [params-json]
       zsb cdp --script <file.json|-> [--out FILE]
Send a Chrome DevTools Protocol command. Params must be a JSON object.
Scripts run ordered steps in ONE attached session on the same target tab.
Steps: {"method":"Profiler.enable","params":{}}, {"waitMs":500},
       {"method":"Page.reload","waitEvent":"Page.loadEventFired","timeoutMs":15000}.
Use - for stdin; print a JSON result array or write it to local --out FILE.
Errors stop at failedStep (1-based) and include completed results; no replay.
Step timeout defaults to 15000 ms; total timeout budget is at most 300000 ms.
Script total reply deadline: 360s; ZSB_TIMEOUT overrides this default.

Examples:
  zsb cdp Page.enable
  zsb cdp Runtime.evaluate '{"expression":"document.title"}'
  zsb cdp --script profile.json --out results.json
CDP_HELP
        ;;
      open) echo 'usage: zsb open [url]' ;;
      sidebar|status|doctor|back|forward|reload|active-tab|shoturl|native|version|browser-drop-gesture)
        printf 'usage: zsb %s\n' "$1" ;;
      activate) echo 'usage: zsb activate <tabId> [--target-only]' ;;
      close-tab) echo 'usage: zsb close-tab <id|match>' ;;
      tab) echo 'usage: zsb tab close <id|match>' ;;
      insert) echo 'usage: zsb insert <text>' ;;
      eval|script) printf 'usage: zsb %s [--frame ID] <js> [--tab ID]\n' "$1" ;;
      key) echo 'usage: zsb key <combo>' ;;
      cursor) echo 'usage: zsb cursor [on|off|move X Y|click X Y]' ;;
      profile-clone) echo 'usage: zsb profile-clone <src> <dst>' ;;
      chrome-launch) echo 'usage: zsb chrome-launch <exe> <extension> <theme> [args...] (internal; the app runs it on Windows)' ;;
      create-pane-window) echo 'usage: zsb create-pane-window <url> [--app] [--bounds L T W H]' ;;
      theme) echo 'usage: zsb theme <name>' ;;
      protect-window) echo 'usage: zsb protect-window [--shell-id N] [--owner-pid N]' ;;
      manifest) echo 'usage: zsb manifest <exe> <extension-id>' ;;
      type) printf '%s\n' 'usage: zsb type [--tab ID] [--keys] [--raw-enter] <text> | <selector> <text>' 'Newlines become line breaks; --raw-enter presses Enter at each newline (submits chat composers).' 'Options may appear in any order, each at most once.' ;;
      *) print_help ;;
    esac
    printf 'Total reply deadline: %ss; override with ZSB_TIMEOUT (finite seconds > 0, at most 86400).\n' "$deadline"
    return
  fi
  cat <<'EOF'
usage: zsb <command> [args] (also: multiprompt-browser)

Commands and flags:
  workspace-list                    List workspaces, groups, tabs and host names as JSON
  session-open --workspace NAME (--group NAME | --after-tab ID) --host HOST --tmux SESSION --name TITLE
                                    Attach a remote tmux tab in the background, idempotently
  shell-open --workspace NAME (--group NAME | --after-tab ID) --host HOST --name TITLE [--attach SESSION] [--run CMD]
                                    Open a server shell tab or attach to tmux
  session-status --tab ID            Report connection state and exit_code when disconnected
  session-close --tab ID             Close one background terminal tab
  session-rebind --tab ID [--host HOST] --tmux SESSION
                                    Point a tab at another remote tmux session and reconnect it
  session-rename --tab ID --name TITLE
                                    Set a tab's manual title
  session-move --tab ID [--workspace NAME] [--group NAME | --ungrouped]
                                    Move a background tab to another workspace or group
  session-pin --tab ID              Pin one background tab to the top of the sidebar
  session-unpin --tab ID            Clear that pin
  session-archive --tab ID          Archive one remote tab and end its tmux session
  session-unarchive --tmux SESSION [--host HOST]
                                    Reopen one archived session as a tab
  session-wait --tab ID             Put a background tab in Waiting until it needs attention
  open <url>                         Open a browser pane for this session
  navigate <url> [--new-tab]         Reuse only untouched agent target; otherwise append; --new-tab always creates
  tabs [--close ID]                  List tabs or close one
  reset                             Reconnect bridge and clear stuck CDP sessions (keeps pane open)
  extension-reload                  Reload unpacked extension code (no app restart)
  active-tab | activate <id> | close-tab <id> | tab close <id>
  url [--tab ID]                    Read a tab URL
  frames [--tab ID]                 List frameId, parentFrameId and URL (read-only)
  eval --frame ID <js> [--tab ID]    Evaluate JavaScript in a chosen frame's MAIN world
  text [--limit N | --full] [--tab ID]      Read visible text (default: 2000 chars)
  snapshot [--limit N | --full] [--tab ID]  Read page state (default: 2000 chars)
  links [--limit N | --full] [--tab ID]     Read links (default: 50 links)
  find <text> [--tab ID]            Find text in the target tab
  click <selector> [--tab ID]       Click an element
  type [--tab ID] [--keys] [--raw-enter] <text> | <selector> <text>  Type into the page; newlines become line breaks
                                    --raw-enter presses Enter at each newline (submits chat composers)
                                    Options may appear in any order, each at most once.
  key <combo>                       Send a key combination
  screenshot [-o FILE | --output FILE] [--tab ID]
  console [-n N | --limit N] [--clear]
  routing-audit [--limit N | --full]
  resolve-window [--match TEXT] [--focused]
  selftest                          Verify page read/type/click/screenshot and visible hosting tab
  pane-info                         Ask the app for hosting tab name, active, visible, windowId
  status | doctor | back | forward | reload | shoturl
  cursor [on | off | move X Y | click X Y]
  emulate <device|WIDTHxHEIGHT> [--mobile] | emulate off
  insert <text> | eval <code> | cdp <method> [params-json]
  cdp --script FILE|- [--out FILE]  Run CDP steps in one attached session

Unqualified reads and actions use the target:true tab in zsb tabs, not necessarily active:true.
Use zsb <command> --help or -h for local command help without contacting the browser.
Reply deadlines (total seconds from send, not idle time):
  Default 15; status/version 5; doctor 20; text/get-text/snapshot/cdp/eval/script 30;
  screenshot/shoturl 60; selftest 180 total across its requests; cdp --script 360.
  ZSB_TIMEOUT overrides these defaults: finite seconds greater than 0, at most 86400.
  Expiry returns nonzero with JSON error reply_timeout, elapsed time, deadline and a recovery hint.
Remote recovery: reconnect SSH/tmux, open or rebind this session's pane, then run zsb status.
EOF
}

if [ "$#" -eq 0 ]; then
  print_help >&2
  exit 2
fi

case "${1:-}" in
  help|--help|-h)
    print_help
    exit 0
    ;;
esac
if [ "${2:-}" = "--help" ] || [ "${2:-}" = "-h" ]; then
  print_help "$1"
  exit 0
fi
if { [ "$1" = tab ] && [ "${2:-}" = close ]; } || { [ "$1" = tabs ] && [ "${2:-}" = --close ]; }; then
  if [ "${3:-}" = "--help" ] || [ "${3:-}" = "-h" ]; then
    print_help "$1"
    exit 0
  fi
fi

sess=""
fallback_sess="${ZSB_SESSION:-}"
if [ "$1" = "workspace-list" ] || [ "$1" = "session-open" ] || [ "$1" = "shell-open" ] || [ "$1" = "session-status" ] || [ "$1" = "session-close" ] || [ "$1" = "session-wait" ] || [ "$1" = "session-rebind" ] || [ "$1" = "session-rename" ] || [ "$1" = "session-move" ] || [ "$1" = "session-pin" ] || [ "$1" = "session-unpin" ] || [ "$1" = "session-archive" ] || [ "$1" = "session-unarchive" ]; then
  sess="workspace"
elif [ -n "${TMUX:-}" ]; then
  sess="$(tmux display-message -p '#S' 2>/dev/null || true)"
fi
if [ -z "$sess" ]; then
  sess="$fallback_sess"
fi
if [ -z "$sess" ]; then
  json_error "no_session" "cannot determine session (set ZSB_SESSION or run inside tmux)" "Run multiprompt-browser inside the multiprompt-owned tmux/SSH tab, or set ZSB_SESSION."
  exit 1
fi

if [ "$1" = "selftest" ]; then
  if [ "$#" -ne 1 ]; then print_help selftest >&2; exit 2; fi
  python3 - "$sess" "$port" "${BASH_SOURCE[0]}" <<'PY_SELFTEST'
import base64
import datetime
import html
import json
import math
import os
import subprocess
import shlex
import struct
import sys
import time
import urllib.parse
import uuid

session, port, wrapper = sys.argv[1:]
try:
    timeout = float(os.environ.get("ZSB_TIMEOUT", "180"))
    if not math.isfinite(timeout) or not 0 < timeout <= 86400:
        raise ValueError()
except ValueError:
    print(json.dumps({"ok": False, "error": "bad_args", "message": "ZSB_TIMEOUT must be finite seconds greater than 0 and at most 86400", "session": session, "port": int(port)}))
    sys.exit(1)
started = time.monotonic()
deadline = started + timeout
stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
title = f"zsb selftest {session} {stamp}"
marker = "zsb-selftest-" + uuid.uuid4().hex
page = (f"<!doctype html><title>{html.escape(title)}</title>"
        f"<h1>{html.escape(title)}</h1><p>{marker}</p>"
        '<input id="zsb-input" aria-label="Selftest input">'
        '''<button id="zsb-button" onclick="document.getElementById('zsb-result').textContent='clicked'">Test click</button>'''
        '<p id="zsb-result">not clicked</p>')
# Extensions cannot script data: pages, so build the test page on a real site.
url = "https://example.com/?" + marker
summary = {"ok": False, "session": session, "title": title, "tab_name": None,
           "active": False, "visible": False, "steps": {}}
tab_id = None
window_id = None


def request(*args):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError()
    try:
        command = ["bash", wrapper, *args]
        run = subprocess.run(shlex.join(command) if os.name == "nt" else command, capture_output=True, text=True,
                             timeout=remaining,
                             env={**os.environ, "TMUX": "", "ZSB_SESSION": session, "ZSB_PORT": port,
                                  "ZSB_TIMEOUT": str(remaining)})
    except subprocess.TimeoutExpired:
        raise TimeoutError() from None
    if time.monotonic() >= deadline:
        raise TimeoutError()
    if run.returncode:
        raise ValueError(run.stderr.strip().removeprefix("multiprompt-browser: "))
    raw = run.stdout
    if args[0] == "open" and raw.strip() == "ok":
        return {}
    reply = json.loads(raw)
    result = reply.get("result", reply)
    if args[0] == "tabs" and isinstance(result, dict) and result.get("tabs") == []:
        if not reply.get("extensionVersion"):
            raise ValueError("extension outdated, reload it in chrome://extensions (empty session pane tab list)")
        raise ValueError("empty session pane tab list: bound browser window has no tabs; reopen/rebind it")
    if isinstance(result, dict) and result.get("error"):
        raise ValueError(str(result["error"]))
    return result


def tabs():
    result = request("tabs")
    return result if isinstance(result, list) else result["tabs"]


def find_test_tab():
    try:
        matches = [tab for tab in tabs() if url in (tab.get("url"), tab.get("pendingUrl"))]
        if len(matches) == 1:
            return matches[0]
        problem = f"tabs found {len(matches)} tabs matching marker URL {url}"
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as exc:
        problem = f"tabs failed: {exc}"
    try:
        active = request("active-tab")
        pane = request("pane-info")
        # issue: active-tab reports the session's target tab; agent tabs are
        # never activated, so the browser's active flag is not required.
        if (url in (active.get("url"), active.get("pendingUrl")) and
                active.get("windowId") == pane.get("windowId") and
                isinstance(active.get("id"), int) and active["id"] > 0 and
                isinstance(pane.get("windowId"), int) and pane["windowId"] > 0):
            return active
        problem += (f"; pane active-tab did not match marker URL (id={active.get('id')}, "
                    f"url={active.get('url')!r}, active={active.get('active')}, "
                    f"windowId={active.get('windowId')}, pane windowId={pane.get('windowId')})")
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as exc:
        problem += f"; active-tab fallback failed: {exc}"
    raise ValueError(f"cannot locate selftest tab: {problem}")


def step(name, action):
    try:
        details = action() or {}
        summary["steps"][name] = {"pass": True, **details}
    except TimeoutError:
        raise
    except Exception as exc:
        summary["steps"][name] = {"pass": False, "error": str(exc)}


def open_page():
    global tab_id, window_id
    request("open", url)
    tab = find_test_tab()
    tab_id, window_id = str(tab["id"]), tab["windowId"]
    # zsb eval takes one expression, so chain with commas.
    build = ("(document.title=" + json.dumps(title) + ",document.body.innerHTML=" + json.dumps(page) +
             ",document.title)")
    for _ in range(20):
        try:
            if request("eval", build, "--tab", tab_id) == title:
                break
        except ValueError:
            pass
        time.sleep(min(0.5, max(0, deadline - time.monotonic())))
    tab = find_test_tab()
    if tab.get("title") != title or tab.get("target") is False:
        raise ValueError("selftest page is not this session's target browser tab")
    return {"tab_id": tab_id, "windowId": window_id}


def read_page():
    result = request("text", "--full", "--tab", tab_id)
    if marker not in result.get("text", "") or title not in result.get("text", ""):
        raise ValueError("text read did not return this selftest page")


def type_input():
    request("type", "--tab", tab_id, "#zsb-input", marker)
    value = request("eval", "document.getElementById('zsb-input').value", "--tab", tab_id)
    if value == marker:
        return
    focused = request("eval", "(document.activeElement && (document.activeElement.id || document.activeElement.tagName)) || 'none'", "--tab", tab_id)
    request("type", "--tab", tab_id, "#zsb-input", marker)
    retried = request("eval", "document.getElementById('zsb-input').value", "--tab", tab_id)
    if retried != marker:
        raise ValueError(f"input value did not match typed text: first={value!r} focused={focused!r} retried={retried!r}")


def click_button():
    request("click", "#zsb-button", "--tab", tab_id)
    result = request("eval", "document.getElementById('zsb-result').textContent", "--tab", tab_id)
    if result != "clicked":
        raise ValueError("button result did not change to clicked")


def screenshot():
    result = request("screenshot", "--tab", tab_id)
    data = result.get("data")
    if result.get("dataUrl", "").startswith("data:image/png;base64,"):
        data = result["dataUrl"].split(",", 1)[1]
    png = base64.b64decode(data or "", validate=True)
    if len(png) < 33 or png[:8] != b"\x89PNG\r\n\x1a\n" or png[12:16] != b"IHDR":
        raise ValueError("screenshot did not contain PNG bytes")
    width, height = struct.unpack(">II", png[16:24])
    if not width or not height:
        raise ValueError("screenshot has empty dimensions")
    return {"bytes": len(png), "width": width, "height": height}


def hosting():
    info = request("pane-info")
    summary.update({"tab_name": info.get("tab_name"), "active": info.get("active") is True,
                    "visible": info.get("visible") is True})
    if not isinstance(summary["tab_name"], str) or not summary["tab_name"]:
        raise ValueError("app did not report a hosting tab name")
    if window_id is not None and info.get("windowId") != window_id:
        raise ValueError("app hosting window differs from the tested pane")
    if not summary["active"] or not summary["visible"]:
        raise ValueError("hosting multiprompt tab is not active and visible")
    if tab_id is not None and find_test_tab().get("target") is False:
        raise ValueError("selftest browser tab is no longer this session's target")


def close_page():
    global tab_id
    # open may have created the page before its verification failed.
    if tab_id is None:
        matches = [tab for tab in tabs() if tab.get("url") == url]
        if not matches:
            return {"not_created": True}
        if len(matches) != 1:
            raise ValueError("ambiguous cleanup; refusing to close unrelated tabs")
        tab_id = str(matches[0]["id"])
    try:
        request("close-tab", tab_id)
    except ValueError as exc:
        if "refusing to close the last tab in a multiprompt browser pane" not in str(exc):
            raise
        request("navigate", "about:blank", "--new-tab")
        request("close-tab", tab_id)
    if any(str(tab["id"]) == tab_id for tab in tabs()):
        raise ValueError("selftest tab still exists after close")


try:
    try:
        step("open", open_page)
        for name, action in [("text", read_page), ("type", type_input),
                             ("click", click_button), ("screenshot", screenshot)]:
            if tab_id is None:
                summary["steps"][name] = {"pass": False, "skipped": "open did not identify a test tab"}
            else:
                step(name, action)
        step("hosting", hosting)
    finally:
        if time.monotonic() < deadline:
            step("close", close_page)
    if time.monotonic() >= deadline:
        raise TimeoutError()
except TimeoutError:
    hint = "Reconnect this SSH session and reopen/rebind its browser pane; selftest cleanup may be incomplete."
    message = f"multiprompt selftest reply timeout after {time.monotonic() - started:.3f}s (deadline {timeout:g}s). {hint}"
    print("multiprompt-browser: " + message, file=sys.stderr)
    print(json.dumps({"ok": False, "error": "reply_timeout", "message": message,
                      "session": session, "port": int(port), "hint": hint}))
    sys.exit(1)
summary["ok"] = all(result["pass"] for result in summary["steps"].values())
print(json.dumps(summary, ensure_ascii=False, separators=(",", ":")))
state = "visible" if summary["visible"] else "not visible"
name = json.dumps(summary["tab_name"], ensure_ascii=False)
if summary["ok"]:
    print(f"PASS: pane for {session} is on tab {name} ({state})")
else:
    failed = next((step, result) for step, result in summary["steps"].items() if not result["pass"])
    print(f"FAIL: {failed[0]}: {failed[1].get('error') or failed[1].get('skipped')}")
sys.exit(0 if summary["ok"] else 1)
PY_SELFTEST
  exit "$?"
fi

send_zsb() {
  python3 - "$sess" "$port" "$@" 3<&0 <<'PY_REQUEST'
import base64
import json
import math
import os
import pathlib
import socket
import subprocess
import sys
import time

session, port, *args = sys.argv[1:]
fix = "Restart/reconnect multiprompt and this SSH session; if it persists, reload the multiprompt extension in the browser (chrome://extensions)."


def fail(code, message, reply=None, hint=None):
    message = " ".join(str(message).split())
    print("multiprompt-browser: " + message, file=sys.stderr)
    print(json.dumps(reply if reply is not None else
                     {"ok": False, "error": code, "message": message,
                      "session": session, "port": int(port), **({"hint": hint} if hint else {})}))
    sys.exit(1)


script_mode = args[0] == "cdp" and len(args) > 1 and args[1] == "--script"
default_timeout = {"status": 5, "version": 5, "doctor": 20,
                   "text": 30, "get-text": 30, "snapshot": 30, "cdp": 30, "eval": 30, "script": 30,
                   "screenshot": 60, "shoturl": 60}.get(args[0], 15)
if script_mode:
    default_timeout = 360
try:
    timeout = float(os.environ.get("ZSB_TIMEOUT", default_timeout))
    if not math.isfinite(timeout) or not 0 < timeout <= 86400:
        raise ValueError()
except ValueError:
    fail("bad_args", "ZSB_TIMEOUT must be finite seconds greater than 0 and at most 86400")


if args[0] == "workspace-list":
    if len(args) != 1:
        fail("bad_args", "usage: zsb workspace-list")
elif args[0] == "session-status":
    try:
        if len(args) != 3 or args[1] != "--tab" or not args[2].isascii() or not args[2].isdigit() or not 0 < int(args[2]) <= 0xffffffff:
            raise ValueError()
    except ValueError:
        fail("bad_args", "usage: zsb session-status --tab <positive tab id>")
elif args[0] == "shell-open":
    flags = {"--workspace", "--group", "--after-tab", "--host", "--name", "--attach", "--run"}
    values = {}
    rest = args[1:]
    i = 0
    while i < len(rest):
        flag = rest[i]
        if flag not in flags or flag in values:
            fail("bad_args", "shell-open takes --workspace, --group or --after-tab, --host, --name, and optional --attach and --run once each")
        if i + 1 >= len(rest):
            fail("bad_args", f"shell-open flag {flag} needs a value")
        value = rest[i + 1]
        if not value or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
            fail("bad_args", "shell-open values must be nonempty and must not contain control characters")
        values[flag] = value
        i += 2
    for required in ("--workspace", "--host", "--name"):
        if required not in values:
            fail("bad_args", "shell-open requires --workspace, --group or --after-tab, --host and --name")
    if ("--group" in values) == ("--after-tab" in values):
        fail("bad_args", "shell-open takes --group or --after-tab, not both")
    if "--attach" in values:
        attach = values["--attach"]
        if len(attach) > 63 or any(not (c.isascii() and (c.isalnum() or c in "-_.")) for c in attach):
            fail("invalid_tmux", "attach session must contain 1-63 letters, digits, hyphens, underscores or dots")
    if len(values["--name"].encode("utf-16-le")) // 2 > 63:
        fail("invalid_name", "tab title must contain 1-63 UTF-16 units without control characters")
    if any(len(value.encode()) > 256 for value in values.values()):
        fail("bad_args", "shell-open argument too long")

elif args[0] == "session-open":
    flags = {"--workspace", "--group", "--after-tab", "--host", "--tmux", "--name"}
    values = {}
    if len(args) != 11:
        fail("bad_args", "session-open requires --workspace, --group or --after-tab, --host, --tmux and --name")
    for flag, value in zip(args[1::2], args[2::2]):
        if flag not in flags or flag in values or not value or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
            fail("bad_args", "session-open requires each named flag once, with nonempty values and no control characters")
        values[flag] = value
    if ("--group" in values) == ("--after-tab" in values):
        fail("bad_args", "session-open takes --group or --after-tab, not both")
    after = values.get("--after-tab")
    if after is not None and (not after.isascii() or not after.isdigit() or not 0 < int(after) <= 0xffffffff):
        fail("bad_args", "--after-tab takes a positive tab id")
    tmux = values["--tmux"]
    if len(tmux) > 63 or any(not (c.isascii() and (c.isalnum() or c in "-_.")) for c in tmux):
        fail("invalid_tmux", "tmux session must contain 1-63 letters, digits, hyphens, underscores or dots")
    if len(values["--name"].encode("utf-16-le")) // 2 > 63:
        fail("invalid_name", "tab title must contain 1-63 UTF-16 units without control characters")
    if any(len(value.encode()) > 256 for value in values.values()):
        fail("bad_args", "session-open argument too long")

elif args[0] in {"session-close", "session-wait", "session-rebind", "session-rename", "session-move", "session-pin", "session-unpin", "session-archive"}:
    edit_specs = {
        "session-close": ({"--tab"}, {"--tab"}),
        "session-wait": ({"--tab"}, {"--tab"}),
        "session-rename": ({"--tab", "--name"}, {"--tab", "--name"}),
        "session-rebind": ({"--tab", "--host", "--tmux"}, {"--tab", "--tmux"}),
        "session-move": ({"--tab", "--workspace", "--group"}, {"--tab"}),
        "session-pin": ({"--tab"}, {"--tab"}),
        "session-unpin": ({"--tab"}, {"--tab"}),
        "session-archive": ({"--tab"}, {"--tab"}),
    }
    allowed, required = edit_specs[args[0]]
    values = {}
    ungrouped = False
    rest = args[1:]
    i = 0
    while i < len(rest):
        if args[0] == "session-move" and rest[i] == "--ungrouped":
            if ungrouped:
                fail("bad_args", "session-move takes --ungrouped once")
            ungrouped = True
            i += 1
            continue
        if i + 1 >= len(rest):
            fail("bad_args", f"{args[0]} flag {rest[i]} needs a value")
        flag, value = rest[i], rest[i + 1]
        if flag not in allowed or flag in values or not value:
            fail("bad_args", f"{args[0]} takes only its named flags once, with nonempty values")
        if any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
            if flag == "--tmux":
                fail("invalid_tmux", "tmux session must contain 1-63 letters, digits, hyphens, underscores or dots")
            if flag == "--name":
                fail("invalid_name", "tab title must contain 1-63 UTF-16 units without control characters")
            fail("bad_args", f"{args[0]} values must not contain control characters")
        values[flag] = value
        i += 2
    if not required <= values.keys():
        fail("bad_args", f"{args[0]} requires " + ", ".join(sorted(required)))
    tab_text = values["--tab"]
    try:
        if not tab_text.isascii() or not tab_text.isdigit() or not 0 < int(tab_text) <= 0xffffffff:
            raise ValueError()
    except ValueError:
        fail("bad_args", f"{args[0]} --tab must be a positive tab id")
    if args[0] == "session-move":
        if not (values.keys() & {"--workspace", "--group"}) and not ungrouped:
            fail("bad_args", "session-move needs --workspace, --group or --ungrouped")
        if "--group" in values and ungrouped:
            fail("bad_args", "session-move takes --group or --ungrouped, not both")
    if "--tmux" in values:
        tmux = values["--tmux"]
        if len(tmux) > 63 or any(not (c.isascii() and (c.isalnum() or c in "-_.")) for c in tmux):
            fail("invalid_tmux", "tmux session must contain 1-63 letters, digits, hyphens, underscores or dots")
    if "--name" in values and len(values["--name"].encode("utf-16-le")) // 2 > 63:
        fail("invalid_name", "tab title must contain 1-63 UTF-16 units without control characters")
    if any(len(value.encode()) > 256 for value in values.values()):
        fail("bad_args", f"{args[0]} argument too long")

elif args[0] == "session-unarchive":
    allowed, required = {"--tmux", "--host"}, {"--tmux"}
    values = {}
    rest = args[1:]
    i = 0
    while i < len(rest):
        if i + 1 >= len(rest):
            fail("bad_args", f"session-unarchive flag {rest[i]} needs a value")
        flag, value = rest[i], rest[i + 1]
        if flag not in allowed or flag in values or not value:
            fail("bad_args", "session-unarchive takes only its named flags once, with nonempty values")
        if any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
            if flag == "--tmux":
                fail("invalid_tmux", "tmux session must contain 1-63 letters, digits, hyphens, underscores or dots")
            fail("bad_args", "session-unarchive values must not contain control characters")
        values[flag] = value
        i += 2
    if not required <= values.keys():
        fail("bad_args", "session-unarchive requires --tmux")
    tmux = values["--tmux"]
    if len(tmux) > 63 or any(not (c.isascii() and (c.isalnum() or c in "-_.")) for c in tmux):
        fail("invalid_tmux", "tmux session must contain 1-63 letters, digits, hyphens, underscores or dots")
    if any(len(value.encode()) > 256 for value in values.values()):
        fail("bad_args", "session-unarchive argument too long")

output_path = None
if script_mode:
    if len(args) not in {3, 5} or (len(args) == 5 and (args[3] != "--out" or not args[4])):
        fail("bad_args", "usage: zsb cdp --script <file.json|-> [--out FILE]")
    if len(args) == 5:
        output_path = args[4]
    try:
        with os.fdopen(3, "r", encoding="utf-8") if args[2] == "-" else open(args[2], encoding="utf-8") as source:
            script = source.read(1024 * 1024 + 1)
        if len(script.encode()) > 1024 * 1024:
            fail("bad_args", "CDP script exceeds 1 MiB")
        steps = json.loads(script)
        if not isinstance(steps, list):
            fail("bad_args", "CDP script must be a JSON array")
        script = json.dumps(steps, separators=(",", ":"))
    except (OSError, ValueError) as exc:
        fail("bad_args", f"cannot read CDP script: {exc}")
    args = ["cdp", "--script-json", script]

if "ZSB_TIMEOUT" not in os.environ and "ZSB_TIMEOUT_SECONDS" in os.environ:
    try:
        timeout = float(os.environ["ZSB_TIMEOUT_SECONDS"])
        if not 0 < timeout <= 300:
            raise ValueError()
    except ValueError:
        fail("bad_timeout", "ZSB_TIMEOUT_SECONDS must be greater than 0 and at most 300")
def pane_port():
    try:
        return int(port)
    except (TypeError, ValueError):
        return 0


def pane_holders():
    target = pane_port()
    if not target:
        return []
    inodes = set()
    for table in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            rows = pathlib.Path(table).read_text(errors="replace").splitlines()[1:]
        except OSError:
            continue
        for row in rows:
            fields = row.split()
            if len(fields) < 10 or fields[3] != "0A":
                continue
            try:
                if int(fields[1].rsplit(":", 1)[1], 16) == target:
                    inodes.add(fields[9])
            except (IndexError, ValueError):
                continue
    if not inodes:
        return []
    holders = []
    try:
        entries = list(pathlib.Path("/proc").iterdir())
    except OSError:
        return []
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            for handle in entry.joinpath("fd").iterdir():
                try:
                    link = os.readlink(handle)
                    if not link.startswith("socket:["):
                        continue
                    if link[8:-1] not in inodes:
                        continue
                except OSError:
                    continue
                try:
                    command = " ".join(entry.joinpath("cmdline").read_bytes().decode(errors="replace").split("\x00")).strip()
                except OSError:
                    command = ""
                holders.append((int(entry.name), command))
                break
        except OSError:
            continue
    return holders


TUNNEL_PROCESSES = {"sshd", "sshd-session", "ssh", "ssh.exe", "sshd.exe"}


def tunnel_holds_port(holders):
    return all(any(os.path.basename(word).split(":")[0] in TUNNEL_PROCESSES for word in command.split())
               for _, command in holders)


def fail_port_conflict(holders):
    pid, command = holders[0]
    label = command or "unknown process"
    message = (f"multiprompt app not reachable: pane port {port} is held by pid {pid} ({label}), not by the "
               f"Multiprompt SSH tunnel, so this request went nowhere. Stop that process, then reconnect this SSH session from multiprompt.")
    fail("port_conflict", message,
         {"ok": False, "error": "port_conflict", "message": message, "session": session, "port": pane_port(),
          "holder": {"pid": pid, "command": command},
          "hint": "Stop the stale process holding the pane port (kill " + str(pid) + "), then reconnect this SSH/tmux session from multiprompt."})


def unix_endpoint(path):
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sock.settimeout(min(5, timeout))
        sock.connect(path)
        return sock
    except OSError:
        sock.close()
        return None

explicit = os.environ.get("ZSB_SOCKET")
configured = ""
if not explicit:
    config = pathlib.Path(os.environ.get("XDG_CONFIG_HOME", pathlib.Path.home() / ".config")) / "multiprompt/browser-socket"
    try:
        configured = config.read_text().strip()
    except FileNotFoundError:
        pass
    except OSError:
        fail("tunnel_down", "Cannot read this user's browser socket configuration")
endpoint = explicit or configured
sock = unix_endpoint(endpoint) if endpoint else None
if sock is None and explicit:
    holders = pane_holders()
    message = (f"the owner browser relay socket {explicit} is not reachable. Install or restart the owner browser relay "
               "(bash tools/install-owner-browser.sh <desktop-ssh-host>) on this server, then reconnect this SSH session from multiprompt.")
    if holders:
        message += f" Pane port {port} is held by pid {holders[0][0]} ({holders[0][1] or 'unknown process'})."
    fail("relay_unavailable", message, hint="Install or restart the owner browser relay, then reconnect this SSH/tmux session.")
if sock is None:
    endpoint = None
    if configured:
        holders = pane_holders()
        message = (f"the browser relay socket {configured} is not reachable. Install or restart the owner browser relay "
                   "(bash tools/install-owner-browser.sh <desktop-ssh-host>), then reconnect this SSH session from multiprompt.")
        if holders:
            message += f" Pane port {port} is held by pid {holders[0][0]} ({holders[0][1] or 'unknown process'})."
        fail("relay_unavailable", message, hint="Install or restart the browser relay, then reconnect this SSH/tmux session.")
token = None
if sock is None:
    try:
        token = os.environ.get("MULTIPROMP_NOTIFY_RELAY_TOKEN")
        if not token:
            token = subprocess.run([str(pathlib.Path.home() / ".local/bin/yeo-key"), "Multiprompt Notify Relay Token", "MULTIPROMP_NOTIFY_RELAY_TOKEN"], check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True).stdout.rstrip("\n")
        if not token:
            raise ValueError()
    except (OSError, subprocess.SubprocessError, ValueError):
        holders = pane_holders()
        detail = ""
        if holders and not tunnel_holds_port(holders):
            detail = (f" Pane port {port} is held by pid {holders[0][0]} ({holders[0][1] or 'unknown process'}), "
                      "not by the Multiprompt SSH tunnel.")
        fail("notify_token_unavailable", "Notify relay token unavailable; set MULTIPROMP_NOTIFY_RELAY_TOKEN or unlock the Multiprompt Notify Relay Token item in Yeo UX Agents." + detail)

started = time.monotonic()
deadline = started + timeout
payload = base64.b64encode(("\x1f".join(args) + "\x1f").encode()).decode()
if script_mode and len(payload) + len(session.encode()) + 6 > 64 * 1024:
    fail("bad_args", "CDP script exceeds the remote command transport limit (64 KiB encoded)")
if sock is None:
    try:
        sock = socket.create_connection(("127.0.0.1", int(port)), timeout=min(5, timeout))
    except (OSError, ValueError):
        holders = pane_holders()
        if holders and not tunnel_holds_port(holders):
            fail_port_conflict(holders)
        fail("tunnel_down", "multiprompt app not reachable (SSH tunnel down). Reconnect this SSH/tmux session from multiprompt.")
try:
    with sock:
        sock.settimeout(max(0.001, deadline - time.monotonic()))
        request = f"ZSB:{session}:{payload}\n"
        sock.sendall((request if endpoint else f"AUTH:{token}:" + request).encode())
        chunks, size = [], 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise socket.timeout()
            sock.settimeout(remaining)
            chunk = sock.recv(65536)
            if time.monotonic() >= deadline:
                raise socket.timeout()
            if not chunk:
                break
            size += len(chunk)
            if size > 16 * 1024 * 1024:
                fail("reply_too_large", "multiprompt reply exceeded 16 MiB. Retry with a smaller output limit.")
            chunks.append(chunk)
except socket.timeout:
    fail("reply_timeout", f"multiprompt app/bridge deadline exceeded; run zsb reset to reconnect without reopening the pane. Reply timeout after {time.monotonic() - started:.3f}s (deadline {timeout:g}s). " + fix, hint=fix)
except OSError:
    fail("connection_closed", "multiprompt connection closed before the reply completed (app stopped or bridge changed). " + fix)
raw = b"".join(chunks).decode(errors="replace")
if not raw.strip():
    holders = pane_holders() if not endpoint else []
    if holders and not tunnel_holds_port(holders):
        fail_port_conflict(holders)
    fail("empty_reply", "multiprompt app not reachable: tunnel accepted the request but closed without a reply (app stopped or bridge changed). " + fix)
# The app's pane handoff and WebView2 actions use a literal acknowledgement.
if raw.strip() == "ok" and args[0] in {"open", "sidebar", "navigate", "back", "forward", "reload"}:
    print(raw.rstrip())
    sys.exit(0)
try:
    reply = json.loads(raw)
except ValueError:
    first = raw.strip().splitlines()[0][:160]
    if first.startswith(("multiprompt-browser [--window", "usage:")):
        fail("bad_args", f"the bridge rejected the arguments of '{args[0]}' and printed its usage ({first}). Check the command and its flags; the app and pane are fine.")
    fail("invalid_reply", f"multiprompt app/bridge returned an invalid reply (possibly interrupted by an update). Reply started with: {first}. " + fix)
if script_mode and isinstance(reply, list):
    if output_path:
        try:
            pathlib.Path(output_path).write_text(raw.rstrip() + "\n", encoding="utf-8")
        except OSError as exc:
            fail("write_failed", f"cannot write CDP results: {exc}")
    else:
        print(raw.rstrip())
    sys.exit(0)
if not isinstance(reply, dict) or not isinstance(reply.get("ok"), bool):
    fail("invalid_reply", "multiprompt app/bridge returned an invalid reply (possibly incompatible versions). " + fix)
loaded, bridge = reply.get("extensionVersion"), reply.get("bridgeVersion")
if loaded and bridge and loaded != bridge and args[0] != "extension-reload":
    fail("version_mismatch", f"extension version {loaded} does not match bridge {bridge}: reload the extension in chrome://extensions; if it persists, install matching bridge/extension versions.")
if reply.get("tunnel") == "down":
    fail("tunnel_down", "multiprompt app not reachable (SSH tunnel down). Reconnect this SSH/tmux session from multiprompt.")
if reply.get("pane") == "closed":
    fail("pane_closed", "browser pane closed: open/rebind the browser pane for this exact multiprompt tab.")
if reply["ok"] is not True:
    message = reply.get("message") or reply.get("error") or "multiprompt command failed"
    hint = reply.get("hint") or ("Use zsb workspace-list to find workspace, group and host names." if args[0] in {"workspace-list", "session-open", "shell-open", "session-status", "session-close", "session-wait", "session-rebind", "session-rename", "session-move", "session-pin", "session-unpin", "session-archive", "session-unarchive"} else "Reload the multiprompt extension in the browser or reopen/rebind this session's browser pane.")
    fail(reply.get("error", "command_failed"), f"{message}. {hint}", reply)
result = reply.get("result", {})
if args[0] == "tabs" and len(args) == 1:
    tabs = result.get("tabs") if isinstance(result, dict) else result
    if tabs == []:
        try:
            parts = tuple(int(part) for part in loaded.split("."))
            current = len(parts) == 3 and parts >= (1, 0, 54)
        except (ValueError, AttributeError):
            current = False
        message = ("empty session pane tab list: reopen/rebind this pane" if current else
                   "extension outdated, reload it in chrome://extensions (requires extension 1.0.54 or newer)")
        fail("pane_tabs_empty" if current else "extension_outdated", message,
             {"ok": False, "error": "pane_tabs_empty" if current else "extension_outdated",
              "message": message, "extensionVersion": loaded})
print(raw.rstrip())
PY_REQUEST
}

orig_args=("$@")
if [ "${orig_args[0]}" = "screenshot" ]; then
  output_path=""
  stripped_args=()
  i=0
  while [ "$i" -lt "${#orig_args[@]}" ]; do
    arg="${orig_args[$i]}"
    if [ "$arg" = "-o" ] || [ "$arg" = "--output" ]; then
      i=$((i + 1))
      if [ "$i" -ge "${#orig_args[@]}" ]; then
        echo "multiprompt-browser: screenshot $arg requires a file path" >&2
        exit 2
      fi
      output_path="${orig_args[$i]}"
    else
      stripped_args+=("$arg")
    fi
    i=$((i + 1))
  done

  if [ -n "$output_path" ]; then
    response="$(send_zsb "${stripped_args[@]}")"
    rc=$?
    if [ "$rc" -ne 0 ]; then
      printf '%s\n' "$response"
      exit "$rc"
    fi
    tmp_response="$(mktemp)"
    printf '%s' "$response" > "$tmp_response"
    python3 - "$output_path" "$tmp_response" <<'PY'
import base64
import json
import pathlib
import sys

out_path = sys.argv[1]
response_path = pathlib.Path(sys.argv[2])
raw = response_path.read_text(errors="replace").strip()

try:
    obj = json.loads(raw)
except Exception as exc:
    print(f"multiprompt-browser: screenshot returned non-JSON ({exc}): {raw[:240]}", file=sys.stderr)
    sys.exit(1)

if not obj.get("ok"):
    print(raw, file=sys.stderr)
    sys.exit(1)

result = obj.get("result") or {}
data = result.get("data")
data_url = result.get("dataUrl")
if data_url:
    prefix = "data:image/png;base64,"
    if not data_url.startswith(prefix):
        print("multiprompt-browser: screenshot returned a non-PNG data URL", file=sys.stderr)
        sys.exit(1)
    data = data_url[len(prefix):]

if not data:
    print("multiprompt-browser: screenshot response did not include PNG data", file=sys.stderr)
    sys.exit(1)

try:
    png = base64.b64decode(data, validate=True)
except Exception as exc:
    print(f"multiprompt-browser: screenshot PNG base64 decode failed: {exc}", file=sys.stderr)
    sys.exit(1)

pathlib.Path(out_path).parent.mkdir(parents=True, exist_ok=True)
pathlib.Path(out_path).write_bytes(png)
report = {"ok": True, "path": out_path, "bytes": len(png)}
via = result.get("via") or ("Page.captureScreenshot" if result.get("data") else "")
if via:
    report["via"] = via
for field in ("warning", "emulationLost"):
    if field in result:
        report[field] = result[field]
print(json.dumps(report, separators=(",", ":")))
PY
    rc=$?
    rm -f "$tmp_response"
    exit "$rc"
  fi
fi

send_zsb "${orig_args[@]}"
