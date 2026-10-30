#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=fc76d5714ced43500552fa0398f8b2f56ee41bd5
sha256=47a2a75d4c670ab21224ea166d512616694881cb484106e17851fbaef71dd508
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
