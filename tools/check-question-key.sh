#!/usr/bin/env bash
set -euo pipefail
root=$(mktemp -d)
export QUESTION_PROOF_ROOT="$root"
trap 'command tmux -S "$root/socket" kill-server 2>/dev/null || true; rm -rf "$root"' EXIT
command tmux -S "$root/socket" new-session -d -s question-proof \
  'stty -echo -icanon; tmux -S "$QUESTION_PROOF_ROOT/socket" wait-for -S ready; dd bs=1 count=1 status=none > "$QUESTION_PROOF_ROOT/answer"; tmux -S "$QUESTION_PROOF_ROOT/socket" wait-for -S received; tmux -S "$QUESTION_PROOF_ROOT/socket" wait-for release'
tmux() { command tmux -S "$QUESTION_PROOF_ROOT/socket" "$@"; }
export -f tmux
tmux wait-for ready
bash -c "${1:?generated question-answer command}"
tmux wait-for received
cat "$root/answer"
