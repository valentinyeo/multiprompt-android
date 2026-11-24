#!/usr/bin/env bash
set -euo pipefail
: "${HOME:?required}"
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
repo=${MULTIPROMPT_JOBS_REPO:-$HOME/projects/multiprompt-android-jobs}
origin=${MULTIPROMPT_JOBS_ORIGIN:-https://github.com/valentinyeo/multiprompt-android.git}
if [ -d "$repo/.git" ]; then
  git -C "$repo" fetch --depth 1 origin main
elif [ -e "$repo" ]; then
  printf 'refusing to install into existing %s: it is not a git clone\n' "$repo" >&2
  exit 1
else
  install -d "$(dirname -- "$repo")"
  git clone --depth 1 "$origin" "$repo"
fi
git -C "$repo" checkout -q --detach origin/main
units=$HOME/.config/systemd/user
install -d "$units"
write_unit() {
  rm -f "$units/$2"
  sed "s|%h/projects/multiprompt-android-jobs|$repo|g" "$1" > "$units/$2"
  chmod 644 "$units/$2"
}
write_unit "$root/tools/parity/multiprompt-parity.service" multiprompt-parity.service
write_unit "$root/tools/parity/multiprompt-parity.timer" multiprompt-parity.timer
write_unit "$root/tools/chat-shapes/multiprompt-chat-probe.service" multiprompt-chat-probe.service
write_unit "$root/tools/chat-shapes/multiprompt-chat-probe.timer" multiprompt-chat-probe.timer
write_unit "$root/tools/chat-shapes/multiprompt-chat-probe-ticket.service" multiprompt-chat-probe-ticket.service
systemctl --user daemon-reload
systemctl --user enable --now multiprompt-parity.timer
systemctl --user enable --now multiprompt-chat-probe.timer
printf 'Scheduled Multiprompt jobs installed from %s\n' "$repo"
