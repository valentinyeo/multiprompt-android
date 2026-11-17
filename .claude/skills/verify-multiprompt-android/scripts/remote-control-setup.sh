#!/usr/bin/env bash
[[ "${GITHUB_ACTIONS:-}" == true ]]
scripts=$(cd "$(dirname "$0")" && pwd)
fixture_session=live-verify-session
sudo install -m 755 "$scripts/remote-control-fixture.py" /home/verifyssh/remote-control-fixture.py
sudo install -m 644 app/src/main/assets/chat_history_reader.py /home/verifyssh/remote-control-reader.py
sudo -u verifyssh mkdir -p /home/verifyssh/.local/bin
sudo install -m 755 "$scripts/remote-control-fixture.py" /home/verifyssh/.local/bin/claude
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s "$fixture_session" -x 100 -y 32 'exec env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/remote-control-fixture.py console 2>/home/verifyssh/remote-control-startup.txt'
ready=false
for attempt in {1..30}; do
    if sudo -u verifyssh tmux capture-pane -pt "$fixture_session" > "$EVIDENCE/remote-control-startup-pane.txt"; then
        if grep -Fq REMOTE_CONTROL_READY_2541 "$EVIDENCE/remote-control-startup-pane.txt" &&
            [[ $(sudo -u verifyssh tmux display-message -p -t "$fixture_session" '#{pane_current_command}') == sleep ]]; then ready=true; break; fi
    fi
    sleep 1
done
sudo cat /home/verifyssh/remote-control-startup.txt > "$EVIDENCE/remote-control-startup.txt"
[[ "$ready" == true ]]
[[ ! -s /home/verifyssh/remote-control-receipts.jsonl ]]
printf 'NO_REMOTE_CONTROL_BEFORE_PHONE\n' > "$EVIDENCE/remote-control-before.txt"
