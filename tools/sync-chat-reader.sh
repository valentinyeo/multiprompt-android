#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_repo="${ZIGSHELL_REPO:-/home/valentin/projects/zigshell}"
asset=app/src/main/assets/chat_history_reader.py
revision=app/src/main/assets/chat_history_reader.sha
if [[ "${1:-}" == --check ]]; then
    git -C "$source_repo" show "$(cat "$revision"):src/chat_history_reader.py" | cmp - "$asset"
    printf 'Bundled reader matches recorded revision\n'
else
    sha=$(git -C "$source_repo" rev-parse origin/master)
    git -C "$source_repo" show "$sha:src/chat_history_reader.py" > "$asset"
    printf '%s\n' "$sha" > "$revision"
fi
