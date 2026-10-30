#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=c490189d5d85ebf4c1c65ad801feac634994d5b3
sha256=cd4fbb1bacc93ff9bcb47fcceab5a5f2feae5de2ae992eccc70c17651bd6e296
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
