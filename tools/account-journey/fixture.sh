#!/usr/bin/env bash
set -euo pipefail
: "${FIXTURE:?Set fixture directory outside evidence}"
: "${MULTIPROMPT_DIR:?Set the multiprompt checkout}"
mkdir -p "$FIXTURE"
sudo apt-get update -qq
sudo apt-get install -y openssh-server tmux strace
sudo useradd -m -s /bin/bash valentin
sudo usermod -p x valentin
ssh-keygen -q -t ed25519 -N '' -C journey-ephemeral -f "$FIXTURE/user-key"
ssh-keygen -q -t ed25519 -N '' -C journey-ephemeral -f "$FIXTURE/host-key"
sudo install -d -m 700 -o valentin -g valentin /home/valentin/.ssh
sudo install -m 600 -o valentin -g valentin "$FIXTURE/user-key.pub" /home/valentin/.ssh/authorized_keys
sudo install -m 755 "$MULTIPROMPT_DIR/tools/mp-layout.mjs" /home/valentin/mp-layout.mjs
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
AllowUsers valentin
UsePAM no
PrintMotd no
CONFIG
sudo /usr/sbin/sshd -t -f "$FIXTURE/sshd_config"
sudo /usr/sbin/sshd -f "$FIXTURE/sshd_config" -E "$FIXTURE/sshd.log"
for name in work-main runner-1 runner-2 runner-3 notes-a; do
    sudo -u valentin tmux new-session -d -s "$name" -x 100 -y 32 "printf 'journey session $name\n'; exec bash"
    sudo -u valentin tmux set-window-option -t "$name" automatic-rename off
    sudo -u valentin tmux rename-window -t "$name" "$name"
done
sudo -u valentin tmux list-sessions
printf 'SSH FIXTURE READY\n'
