#!/usr/bin/env bash
[[ "${GITHUB_ACTIONS:-}" == true ]]
scripts=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$scripts/../../../.." && pwd)
fixture_session=live-fresh-start-3000
sudo install -m 755 "$root/tools/session-fresh-start/fixture.py" /home/verifyssh/session-fresh-start-fixture.py
sudo install -m 644 "$root/tools/verify-seed.py" /home/verifyssh/verify-seed.py
reader_source="$root/app/src/main/assets/chat_history_reader.py"
if [[ -n "${APP_REF:-}" ]]; then
    [[ "$(git -C app-source rev-parse HEAD)" == "$APP_REF" ]]
    reader_source="app-source/app/src/main/assets/chat_history_reader.py"
fi
sha256sum "$reader_source" > "$EVIDENCE/session-fresh-start-reader.sha256"
sudo install -m 644 -o verifyssh -g verifyssh "$reader_source" /home/verifyssh/session-fresh-start-reader.py
sudo -u verifyssh mkdir -p /home/verifyssh/.local/bin
sudo install -m 755 "$root/tools/session-fresh-start/fixture.py" /home/verifyssh/.local/bin/claude
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s "$fixture_session" -x 100 -y 32 'exec env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/session-fresh-start-fixture.py console 2>/home/verifyssh/session-fresh-start-startup.txt'
ready=false
for attempt in {1..30}; do
    if sudo -u verifyssh tmux capture-pane -pt "$fixture_session" > "$EVIDENCE/session-fresh-start-startup-pane.txt"; then
        if grep -Fq FRESH_START_READY_3000 "$EVIDENCE/session-fresh-start-startup-pane.txt" &&
            [[ $(sudo -u verifyssh tmux display-message -p -t "$fixture_session" '#{pane_current_command}') == sleep ]]; then ready=true; break; fi
    fi
    sleep 1
done
sudo cat /home/verifyssh/session-fresh-start-startup.txt > "$EVIDENCE/session-fresh-start-startup.txt"
[[ "$ready" == true ]]
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/session-fresh-start-reader.py host start claude c0030000-3000-4000-8000-000000003000 > "$EVIDENCE/session-fresh-start-host-start.json"
jq -e '.status == "started" or .status == "already"' "$EVIDENCE/session-fresh-start-host-start.json"
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/session-fresh-start-fixture.py seed --home /home/verifyssh > "$EVIDENCE/session-fresh-start-seed.json"
jq -e '.sid == "c0030000-3000-4000-8000-000000003000"' "$EVIDENCE/session-fresh-start-seed.json"
sudo -u verifyssh python3 - <<'PY' > "$EVIDENCE/seed-counts.json"
import json
from pathlib import Path
manifest = json.loads(Path("/home/verifyssh/session-fresh-start.json").read_text())
print(json.dumps(dict(sessions=1, bulk_sessions=0, session_names=[manifest["session"]],
    shapes=[], transcript=None, real_chat=None, fresh_start=manifest), indent=2))
PY