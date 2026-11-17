#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "$HOME" == /home/verifyssh && "$(id -u)" -ne 0 && "$(id -un)" == verifyssh ]]
tmux has-session -t "=${1:-verify-session}"
for i in {0..39}; do
    if (( i < 12 )); then printf -v name "pin-%02d" "$i"
    else printf -v name "row-%02d" "$((i - 12))"; fi
    receipt="/home/verifyssh/pins-$name-receipts.txt"
    tmux new-session -d -s "$name" -x 100 -y 32 \
        "env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/console.py $receipt"
done
tmux list-sessions -F "#{session_name}" | grep -E "^(pin|row)-[0-9][0-9]$" | sort > /home/verifyssh/pins-names.txt
[[ "$(wc -l < /home/verifyssh/pins-names.txt)" == 40 ]]
tmux new-session -d -s cl-long-chat -x 100 -y 32 '/home/verifyssh/long-chat-bin/claude -u /home/verifyssh/long-chat-fixture.py /home/verifyssh/long-chat-source.json'
tmux set-window-option -t cl-long-chat automatic-rename off
tmux rename-window -t cl-long-chat cl-long-chat
for attempt in {1..300}; do
    if [[ -s /home/verifyssh/long-chat-source.json ]]; then exit 0; fi
    sleep 0.1
done
printf 'Long chat seed did not become ready\n' >&2
exit 1
