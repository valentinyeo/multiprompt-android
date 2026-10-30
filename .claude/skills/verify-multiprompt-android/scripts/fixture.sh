#!/usr/bin/env bash
set -euo pipefail
: "${FIXTURE:?Set fixture directory outside evidence}"
: "${EVIDENCE:?Set evidence directory}"
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
    sudo install -m 755 "$(dirname "$0")/cancel-console.py" /home/verifyssh/cancel-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/cancel-console.py /home/verifyssh/receipts.txt'
elif [[ "${VERIFY_FEATURE:-}" == chat-queued.md ]]; then
    sudo install -m 755 "$(dirname "$0")/queued-console.py" /home/verifyssh/queued-console.py
    sudo -u verifyssh ln -s "$(command -v python3)" /home/verifyssh/claude
    sudo -u verifyssh tmux new-session -d -s verify-session -x 100 -y 32 '/home/verifyssh/claude -u /home/verifyssh/queued-console.py /home/verifyssh/receipts.txt'
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
printf 'SSH FIXTURE READY\n'
