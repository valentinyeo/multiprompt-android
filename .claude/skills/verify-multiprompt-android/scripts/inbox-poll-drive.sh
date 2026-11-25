#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"

inbox_poll_log() {
    sudo cat /home/verifyssh/commands.log > "$EVIDENCE/inbox-poll-$1.txt"
}

inbox_poll_count() {
    python3 - "$EVIDENCE" "$1" "$2" "$3" "$4" <<'PY'
import json
from pathlib import Path
import sys
root, before, after, low, high = Path(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
a = (root / ('inbox-poll-' + before + '.txt')).read_text()
b = (root / ('inbox-poll-' + after + '.txt')).read_text()
assert b.startswith(a), 'INBOX_POLL_LOG_RESET'
opens = b[len(a):].count('__MP_TMUX_BEGIN__')
assert low <= opens <= high, f'INBOX_POLL_WINDOW {after}: {opens}, expected {low}..{high}'
path = root / 'inbox-poll-windows.json'
windows = json.loads(path.read_text()) if path.exists() else []
windows.append(dict(before=before, after=after, list_sessions=opens, minimum=low, maximum=high))
path.write_text(json.dumps(windows, indent=2) + '\n')
print(json.dumps(windows[-1]))
PY
}

sleep 35
inbox_poll_log idle-before
sleep 22
inbox_poll_log idle-after
inbox_poll_count idle-before idle-after 1 3
started=$(date +%s%3N)
sudo -u verifyssh tmux send-keys -t verify-session summary-next Enter
"$mpa" read --wait-text 'The release is ready.' --timeout 10
elapsed=$(( $(date +%s%3N) - started ))
test "$elapsed" -le 10000
printf 'INBOX_NEW_MESSAGE_OK elapsed_ms=%s limit_ms=10000\n' "$elapsed" | tee "$EVIDENCE/inbox-message-latency.txt"
"$mpa" proof --name inbox-new-message --require-text 'The release is ready.' --forbid-text 'Which route should we use?'
sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/inbox-new-message-host.txt"
grep -F 'The release is ready.' "$EVIDENCE/inbox-new-message-host.txt"

"$mpa" tap desc 'Session list actions'
"$mpa" tap text Settings
"$mpa" read --wait-text 'Session list settings'
sleep 2
inbox_poll_log settings-before
sleep 12
inbox_poll_log settings-after
inbox_poll_count settings-before settings-after 0 0
"$mpa" screenshot inbox-poll-settings
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
sleep 3
inbox_poll_log settings-return
inbox_poll_count settings-after settings-return 1 8

"$mpa" tap desc 'Robot verify-session'
"$mpa" read --wait-text 'Message verify-session'
sleep 2
inbox_poll_log reader-before
sleep 12
inbox_poll_log reader-after
inbox_poll_count reader-before reader-after 0 0
"$mpa" screenshot inbox-poll-reader
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox

"$mpa" key home
sleep 2
inbox_poll_log background-before
sleep 12
inbox_poll_log background-after
inbox_poll_count background-before background-after 0 0
"$mpa" launch --wait-text Inbox
sleep 3
inbox_poll_log resume
inbox_poll_count background-after resume 1 8
"$mpa" proof --name inbox-poll-resumed --require-text Inbox --require-text 'The release is ready.'
