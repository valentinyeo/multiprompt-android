#!/usr/bin/env bash
set -euo pipefail
: "${FIXTURE:?Set fixture directory outside evidence}"
: "${EVIDENCE:?Set evidence directory}"
if [[ "${VERIFY_FEATURE:-}" == session-routing.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true && "$FIXTURE" == "$RUNNER_TEMP/verify-ssh" && ! -e /home/verifyssh ]]
fi
mkdir -p "$FIXTURE" "$EVIDENCE"
sudo apt-get update -qq
sudo apt-get install -y openssh-server tmux
sudo useradd -m -s /bin/bash verifyssh
sudo usermod -p x verifyssh
ssh-keygen -q -t ed25519 -N '' -C verify-ephemeral -f "$FIXTURE/user-key"
ssh-keygen -q -t ed25519 -N '' -C verify-ephemeral -f "$FIXTURE/host-key"
ssh-keygen -lf "$FIXTURE/host-key.pub" -E sha256 | awk '{print $2}' > "$EVIDENCE/host-fingerprint.txt"
sudo install -d -m 700 -o verifyssh -g verifyssh /home/verifyssh/.ssh
sudo install -m 600 -o verifyssh -g verifyssh "$FIXTURE/user-key.pub" /home/verifyssh/.ssh/authorized_keys
if [[ "${VERIFY_FEATURE:-}" == session-routing.md ]]; then
    sudo install -m 755 "$(dirname "$0")/routing-canary.py" /home/verifyssh/routing-canary.py
    cat > "$FIXTURE/ssh-command" <<'COMMAND'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${SSH_ORIGINAL_COMMAND:-}" >> /home/verifyssh/commands.log
if [[ -n "${SSH_ORIGINAL_COMMAND:-}" ]]; then exec /bin/bash -c "$SSH_ORIGINAL_COMMAND"; fi
exec /bin/bash
COMMAND
    sudo install -m 755 "$FIXTURE/ssh-command" /home/verifyssh/ssh-command
    { printf 'command="/home/verifyssh/ssh-command" '; cat "$FIXTURE/user-key.pub"; } | sudo tee /home/verifyssh/.ssh/authorized_keys >/dev/null
fi
sudo install -d -m 755 /run/sshd
cat > "$FIXTURE/sshd_config" <<CONFIG
Port 2222
ListenAddress 0.0.0.0
HostKey $FIXTURE/host-key
PidFile $FIXTURE/sshd.pid
AuthorizedKeysFile .ssh/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
PermitRootLogin no
AllowUsers verifyssh
UsePAM no
PrintMotd no
LogLevel VERBOSE
CONFIG
sudo /usr/sbin/sshd -t -f "$FIXTURE/sshd_config"
sudo /usr/sbin/sshd -f "$FIXTURE/sshd_config" -E "$FIXTURE/sshd.log"
sudo install -m 755 "$(dirname "$0")/console.py" /home/verifyssh/console.py
if [[ "${VERIFY_FEATURE:-}" == chat-codex.md ]]; then
    sudo install -m 755 "$(dirname "$0")/codex-console.py" /home/verifyssh/codex-console.py
    sudo install -m 755 "$(dirname "$0")/resync-console.py" /home/verifyssh/resync-console.py
    for binary in hax codex claude; do sudo -u verifyssh ln -s "$(command -v python3)" "/home/verifyssh/$binary"; done
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/hax -u /home/verifyssh/codex-console.py /home/verifyssh/receipts.txt hax'
    sudo -u verifyssh tmux new-session -d -s codex-native -x 100 -y 32 '/home/verifyssh/codex -u /home/verifyssh/codex-console.py /home/verifyssh/codex-receipts.txt codex'
    sudo -u verifyssh tmux new-session -d -s claude-control -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/codex-console.py /home/verifyssh/claude-receipts.txt claude'
    sudo -u verifyssh tmux set-window-option -t claude-control automatic-rename off
    sudo -u verifyssh tmux rename-window -t claude-control claude-control
elif [[ "${VERIFY_FEATURE:-}" == session-summaries.md ]]; then
    sudo install -m 755 tools/session-summaries/console.py /home/verifyssh/summary-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/summary-console.py'
elif [[ "${VERIFY_FEATURE:-}" == session-gestures.md ]]; then
    sudo install -m 755 tools/session-gestures/console.py /home/verifyssh/gesture-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/gesture-console.py'
elif [[ "${VERIFY_FEATURE:-}" == chat-working.md ]]; then
    sudo install -m 755 tools/working-dots/console.py /home/verifyssh/working-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/working-console.py'
elif [[ "${VERIFY_FEATURE:-}" == terminal.md ]]; then
    sudo install -m 755 "$(dirname "$0")/terminal-console.py" /home/verifyssh/terminal-console.py
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 'python3 -u /home/verifyssh/terminal-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == chat-choices.md ]]; then
    sudo install -m 755 "$(dirname "$0")/choices-console.py" /home/verifyssh/choices-console.py
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 'exec -a claude /usr/bin/python3 -u /home/verifyssh/choices-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == native-skills.md ]]; then
    sudo install -m 755 "$(dirname "$0")/skills-console.py" /home/verifyssh/skills-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/skills-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == chat-reactions.md ]]; then
    sudo install -m 755 "$(dirname "$0")/reaction-console.py" /home/verifyssh/reaction-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/reaction-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == chat-cancel.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true ]]
    sudo install -m 755 "$(dirname "$0")/cancel-console.py" /home/verifyssh/cancel-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh env GITHUB_ACTIONS="$GITHUB_ACTIONS" HOME=/home/verifyssh tmux new-session -d -s verify-session -x 100 -y 32 'env GITHUB_ACTIONS=true HOME=/home/verifyssh /home/verifyssh/claude -u /home/verifyssh/cancel-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == chat-queued.md || "${VERIFY_FEATURE:-}" == chat-shared.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true ]]
    sudo install -m 755 "$(dirname "$0")/queued-console.py" /home/verifyssh/queued-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh env GITHUB_ACTIONS="$GITHUB_ACTIONS" tmux new-session -d -s verify-session -x 100 -y 32 'env GITHUB_ACTIONS=true HOME=/home/verifyssh /home/verifyssh/claude -u /home/verifyssh/queued-console.py'
    if [[ "${VERIFY_FEATURE:-}" == chat-shared.md ]]; then
        sudo install -m 644 app/src/main/assets/chat_history_reader.py /home/verifyssh/shared-reader.py
        sudo install -m 644 app/src/main/assets/chat_connectors/claude.py /home/verifyssh/shared-claude.py
        sudo install -m 644 app/src/main/assets/chat_connectors/codex.py /home/verifyssh/shared-codex.py
        sudo install -m 755 "$(dirname "$0")/shared-evidence.py" /home/verifyssh/shared-evidence.py
        sudo install -m 755 "$(dirname "$0")/shared-lock.py" /home/verifyssh/shared-lock.py
        sudo install -m 755 "$(dirname "$0")/codex-console.py" /home/verifyssh/codex-console.py
        sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/codex
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s codex-native -x 100 -y 32 '/home/verifyssh/codex -u /home/verifyssh/codex-console.py /home/verifyssh/codex-receipts.txt codex'
        sudo -u verifyssh tmux set-window-option -t codex-native automatic-rename off
        sudo -u verifyssh tmux rename-window -t codex-native codex-native
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s shared-lock-claude 'env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-lock.py claude a1b2c3d4-1008-4000-8000-000000001008'
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s shared-lock-codex 'env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-lock.py codex verify-codex'
        for attempt in {1..30}; do [[ -f /home/verifyssh/shared-lock-ready-claude-a1b2c3d4-1008-4000-8000-000000001008 && -f /home/verifyssh/shared-lock-ready-codex-verify-codex ]] && break; sleep 1; done
        [[ -f /home/verifyssh/shared-lock-ready-claude-a1b2c3d4-1008-4000-8000-000000001008 && -f /home/verifyssh/shared-lock-ready-codex-verify-codex ]]
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py bootstrap > "$EVIDENCE/shared-bootstrap.json"
        jq -e '.engine_info.generation == 21 and .bundle_bytes == 224140 and .bundle_sha256 == "43d0205c4f673178f116e53bc702db0080be4d613ee66a8e31427e531ce7a44f" and .snapshot.next_cursor == null and (.snapshot.items | length) == 0' "$EVIDENCE/shared-bootstrap.json" >/dev/null
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py bootstrap codex > "$EVIDENCE/shared-bootstrap-codex.json"
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py bootstrap neighbor > "$EVIDENCE/shared-bootstrap-neighbor.json"
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s shared-lock-neighbor 'env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-lock.py claude a1b2c3d4-1008-4000-8000-000000001009'
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh tmux new-session -d -s shared-lock-codex-neighbor 'env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-lock.py codex verify-codex-neighbor'
        for attempt in {1..30}; do [[ -f /home/verifyssh/shared-lock-ready-claude-a1b2c3d4-1008-4000-8000-000000001009 && -f /home/verifyssh/shared-lock-ready-codex-verify-codex-neighbor ]] && break; sleep 1; done
        [[ -f /home/verifyssh/shared-lock-ready-claude-a1b2c3d4-1008-4000-8000-000000001009 && -f /home/verifyssh/shared-lock-ready-codex-verify-codex-neighbor ]]
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py bootstrap codex-neighbor > "$EVIDENCE/shared-bootstrap-codex-neighbor.json"
        for neighbor in neighbor codex-neighbor; do
            sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py neighbor-submit "$neighbor" > "$EVIDENCE/shared-$neighbor-submit.json"
            sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/shared-evidence.py read "$neighbor" > "$EVIDENCE/shared-$neighbor-before.json"
            jq -e '(.rows | length) == 1 and .rows[0].state == "queued" and (.rows[0].id | length) > 0 and (.rows[0].attempt | length) > 0' "$EVIDENCE/shared-$neighbor-before.json" >/dev/null
        done
        jq -e '.rows[0].id == "sentinelclaude000000000000000001"' "$EVIDENCE/shared-neighbor-before.json" >/dev/null
        jq -e '.rows[0].id == "sentinelcodex0000000000000000002"' "$EVIDENCE/shared-codex-neighbor-before.json" >/dev/null
    fi
elif [[ "${VERIFY_FEATURE:-}" == chat-resync.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true ]]
    sudo install -m 644 app/src/main/assets/chat_history_reader.py /home/verifyssh/recovery-reader.py
    sudo install -m 755 "$(dirname "$0")/resync-console.py" /home/verifyssh/resync-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh env GITHUB_ACTIONS=true tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/resync-console.py console'
else
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 'python3 -u /home/verifyssh/console.py /home/verifyssh/receipts.txt'
fi
sudo -u verifyssh tmux set-window-option -t verify-session automatic-rename off
sudo -u verifyssh tmux rename-window -t verify-session verify-session
sudo -u verifyssh tmux display-message -p '#{pid}' > "$FIXTURE/tmux.pid"
sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/fixture-start.txt"
if [[ "${VERIFY_FEATURE:-}" == session-routing.md || "${VERIFY_FEATURE:-}" == session-gestures.md ]]; then
    sudo -u verifyssh tmux new-session -d -s verify-other -x 100 -y 32 'python3 -u /home/verifyssh/console.py /home/verifyssh/other-receipts.txt'
    sudo -u verifyssh tmux set-window-option -t verify-other automatic-rename off
    sudo -u verifyssh tmux rename-window -t verify-other verify-other
fi
if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true ]]
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
        [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -u)" -ne 0 && "$(id -un)" == verifyssh ]]
        tmux has-session -t verify-session
        tmux new-session -d -s verify-keep -x 100 -y 32 "python3 -u /home/verifyssh/console.py /home/verifyssh/keep-receipts.txt"
        tmux set-window-option -t verify-keep automatic-rename off
        tmux rename-window -t verify-keep verify-keep
        tmux has-session -t verify-keep
    '
fi
printf 'SSH FIXTURE READY\n'
