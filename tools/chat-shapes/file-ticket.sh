#!/usr/bin/env bash
set -uo pipefail
T=$(cat "$HOME/.config/hypertask-agents/credentials/sessions/multiprompt-android-dev-agent-token")
S=$(journalctl --user -u multiprompt-chat-probe.service -n 200 --no-pager -o cat | grep -E "CHAT_PROBE" | tail -1)
f=$(mktemp)
trap 'rm -f "$f"' EXIT
cat > "$f" <<TEXT
**Problem**

The daily chat check found one chat with stale messages.
Find why that chat stopped following its newest session.

**Fix**

Add that chat shape to the phone test seed.
Fix the chat reader until the test passes.

**Decision**

Every chat check mismatch becomes a failing phone test first.

**Done when**

The daily check passes again.
A phone test covers that chat shape.

**Risk**

The check saw only message counts.
It never saw chat text.
TEXT
created=$(hypertask --json --token "$T" task create --project 13734 --title "Daily chat check found a chat loading wrong messages" --description-file "$f" --markdown --priority high)
ticket=$(printf '%s' "$created" | python3 -c 'import json, sys; data = json.load(sys.stdin); tasks = data.get("tasks") or [data.get("task") or {}]; print(tasks[0].get("ticketNumber") or "")')
if [ -z "$ticket" ]; then
  printf 'the ticket was not created: no ticket number in the reply\n' >&2
  exit 1
fi
if [ -n "$S" ]; then
  hypertask --token "$T" activity add "$ticket" --text "$S"
fi
