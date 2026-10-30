#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=e62769a0862c8518a68a74ea6bfc6030b03dad3b
sha256=3c5c2f1923f2a5346e65ed7fc235aab998b9e67054535541f3ea18b51466cdfd
asset=app/src/main/assets/chat_history_reader.py
[[ "$(cat app/src/main/assets/chat_history_reader.sha)" == "$revision" ]]
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
if [[ -n "${MULTIPROMPT_DESKTOP_REPO:-}" ]]; then
    git -C "$MULTIPROMPT_DESKTOP_REPO" show "$revision:src/chat_history_reader.py" > "$tmp"
elif [[ "${GITHUB_ACTIONS:-}" == true ]]; then
    curl --fail --silent --show-error --location "https://raw.githubusercontent.com/valentinyeo/multiprompt/$revision/src/chat_history_reader.py" > "$tmp"
else
    printf 'Set MULTIPROMPT_DESKTOP_REPO to the canonical desktop repository\n' >&2
    exit 1
fi
printf '%s  %s\n' "$sha256" "$tmp" | sha256sum --check --status
if [[ "${1:-}" == --check ]]; then
    cmp "$tmp" "$asset"
    printf 'Bundled reader matches canonical revision and SHA256\n'
else
    cp "$tmp" "$asset"
fi
