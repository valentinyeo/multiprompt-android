#!/usr/bin/env bash
[[ "${GITHUB_ACTIONS:-}" == true ]]
scripts=$(cd "$(dirname "$0")" && pwd)
fixture_session=live-verify-session
if [[ "${BREAK_IT_MODE:-}" == true ]]; then sudo touch /home/verifyssh/host-restart-stream-enabled; fi
sudo install -m 755 "$scripts/host-restart-ssh.py" /home/verifyssh/host-restart-ssh.py
{ printf 'command="python3 /home/verifyssh/host-restart-ssh.py" '; cat "$FIXTURE/user-key.pub"; } | sudo tee /home/verifyssh/.ssh/authorized_keys >/dev/null
sudo install -m 755 "$scripts/host-restart-fixture.py" /home/verifyssh/host-restart-fixture.py
reader_source=app/src/main/assets/chat_history_reader.py
if [[ -n "${APP_REF:-}" ]]; then
    [[ "$(git -C app-source rev-parse HEAD)" == "$APP_REF" ]]
    reader_source="app-source/$reader_source"
fi
sha256sum "$reader_source" > "$EVIDENCE/host-restart-seed-reader.sha256"
sudo install -m 644 -o verifyssh -g verifyssh "$reader_source" /home/verifyssh/host-restart-reader.py
sudo -u verifyssh mkdir -p /home/verifyssh/.local/bin
sudo install -m 755 "$scripts/host-restart-fixture.py" /home/verifyssh/.local/bin/claude
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s "$fixture_session" -x 100 -y 32 'exec env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/host-restart-fixture.py console 2>/home/verifyssh/host-restart-startup.txt'
ready=false
for attempt in {1..30}; do
    if sudo -u verifyssh tmux capture-pane -pt "$fixture_session" > "$EVIDENCE/host-restart-startup-pane.txt"; then
        if grep -Fq HOST_RESTART_READY_2681 "$EVIDENCE/host-restart-startup-pane.txt" &&
            [[ $(sudo -u verifyssh tmux display-message -p -t "$fixture_session" '#{pane_current_command}') == sleep ]]; then ready=true; break; fi
    fi
    sleep 1
done
sudo cat /home/verifyssh/host-restart-startup.txt > "$EVIDENCE/host-restart-startup.txt"
[[ "$ready" == true ]]
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/host-restart-reader.py host start claude c001c001-2681-4000-8000-000000002681 > "$EVIDENCE/host-restart-start.json"
jq -e '.status == "started" or .status == "already"' "$EVIDENCE/host-restart-start.json"
