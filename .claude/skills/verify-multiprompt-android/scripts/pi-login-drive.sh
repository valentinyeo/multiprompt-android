#!/usr/bin/env bash
mpa="$scripts/mpa"
sudo cat /home/verifyssh/reader-requests.jsonl > "$EVIDENCE/pi-model-requests-before.jsonl"
"$mpa" read --wait-text pi-fast
"$mpa" read --wait-text pi-deep
scroll_to_newest
"$mpa" read --select visible --value pi-fast --extra enabled
"$mpa" read --select visible --value pi-deep --extra enabled
"$mpa" screenshot pi-model-choices
"$mpa" proof --name pi-model-choices --require-text pi-fast --require-text pi-deep --forbid-text PI_PANE_ONLY_545 --forbid-desc 'Jump to newest'
sudo cat /home/verifyssh/reader-requests.jsonl > "$EVIDENCE/pi-model-requests-after.jsonl"
sudo cat /home/verifyssh/ai-switch-models.json > "$EVIDENCE/pi-models-served.json"
python3 - "$EVIDENCE" > "$EVIDENCE/pi-model-host.json" <<'PYMODEL'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
models = json.loads((root / 'pi-models-served.json').read_text())
assert [model['name'] for model in models] == ['pi-fast', 'pi-deep'], models
before = [json.loads(line) for line in (root / 'pi-model-requests-before.jsonl').read_text().splitlines()]
after = [json.loads(line) for line in (root / 'pi-model-requests-after.jsonl').read_text().splitlines()]
assert after[:len(before)] == before
requests = [row for row in after[len(before):] if row['session'] == 'live-verify-session']
history = [row for row in requests if row['kind'] == 'history']
assert history, 'Pi chat never queried structured history'
assert not [row for row in requests if row['kind'] == 'snapshot'], 'Pi chat captured pane text'
print(json.dumps(dict(models=models, history_queries=len(history), pane_captures=0)))
PYMODEL
"$mpa" fill 'Message verify-session' PI_LOGIN_PROMPT_2276
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text 'Sign in to Pi to continue'
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" tap text verify-session
"$mpa" read --wait-text 'Sign in to Pi'
"$mpa" read --wait-text 'Open sign-in link'
"$mpa" screenshot ai-switch-pi-login
"$mpa" proof --name pi-login-pending --require-text 'Sign in to Pi' --require-text 'Open sign-in link' --require-text 'https://pi.example/verify-device'
sudo -u verifyssh env HOME=/home/verifyssh python3 - > "$EVIDENCE/pi-login-host.json" <<'PYHOST'
import json
import sqlite3
from pathlib import Path
with sqlite3.connect(Path.home() / '.local/state/multiprompt/shared-messages/store.sqlite3') as db:
    events = [json.loads(raw) for raw, in db.execute('SELECT body FROM message_events ORDER BY seq')]
login = [event for event in events if (event.get('item') or '').startswith('pi-login-') and event.get('kind') == 'text.done']
assert login, events
pending = login[-1]
assert pending['payload']['text'] == 'Sign in to Pi to continue\nhttps://pi.example/verify-device', pending
print(json.dumps(dict(pending=pending, provider='pi')))
PYHOST
login_url=https://pi.example/verify-device
browser=$(adb shell cmd package resolve-activity --brief --user 0 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "$login_url" | tr -d '\r' | tail -1)
[[ "$browser" == */* && "$browser" != android/* ]]
"$mpa" tap text 'Open sign-in link'
login_started=false
for attempt in {1..30}; do
    adb shell dumpsys activity activities > "$EVIDENCE/pi-login-browser-activity.txt"
    if python3 "$scripts/pi-login-browser.py" "$browser" "$package" "$login_url" < "$EVIDENCE/pi-login-browser-activity.txt" > "$EVIDENCE/pi-login-browser.json"; then
        login_started=true
        break
    fi
    sleep 1
done
[[ "$login_started" == true ]]
"$mpa" screenshot pi-login-browser
"$mpa" key back
"$mpa" read --wait-text 'Open sign-in link'
"$mpa" fill 'Message verify-session' PI_LOGIN_DONE_2276
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text 'Logged in to anthropic. The next reply uses that login.'
"$mpa" read --wait-absent 'Open sign-in link'
"$mpa" screenshot ai-switch-pi-logged-in
"$mpa" proof --name pi-login-completed --require-text 'Logged in to anthropic. The next reply uses that login.' --forbid-text 'Open sign-in link'
"$mpa" fill 'Message verify-session' PI_LOGIN_AFTER_2276
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text PI_REPLY_PI_LOGIN_AFTER_2276
"$mpa" screenshot ai-switch-pi-after-login
"$mpa" proof --name pi-login-after --require-text PI_REPLY_PI_LOGIN_AFTER_2276 --forbid-text 'Open sign-in link'
printf 'PI_LOGIN_HOST_CARD_BROWSER_HANDOFF_AND_RESULT_OK\n'
