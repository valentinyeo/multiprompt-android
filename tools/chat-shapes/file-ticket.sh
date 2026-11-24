#!/usr/bin/env bash
set -uo pipefail
T=$(cat "$HOME/.config/hypertask-agents/credentials/sessions/multiprompt-android-dev-agent-token")
S=$(journalctl --user -u multiprompt-chat-probe.service -n 200 --no-pager -o cat | grep -E "CHAT_PROBE" | tail -1)
f=$(mktemp)
trap 'rm -f "$f"' EXIT
cat > "$f" <<TEXT
**Problem**

The daily live chat check found a chat that loads the wrong messages.
Probe summary: $S

**Fix**

Find which chat shape broke and add it to the phone test seed.

**Decision**

Every probe mismatch becomes a failing phone test first.

**Done when**

The daily check passes again and a phone test covers the shape.

**Risk**

The probe saw only ids and counts, never chat text.
TEXT
hypertask --json --token "$T" tasks create --project 13734 --title "Daily chat check found a chat loading wrong messages" --description-file "$f" --markdown --priority high
