#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=39bae4f473b4d5782a2ccc4811d9d3b7ea61f1bd
[[ "$(cat app/src/main/assets/chat_history_reader.sha)" == "$revision" ]]
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py c4b69e9730790cf27e103c3542b4fc224ef6d83a670fb3e61ead29bbeb248aeb' \
    'chat_connectors/claude.py 1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py 5f805aef8fff1735356fe4c165bb161efcb791ab0a47ef30d3a7777dc21d784b' \
    'multibot_feedback.py 39a5951183131259c9d1edfb47b6d649777303e35481a9d09e2526d1dd29b366'; do
    read -r path hash <<< "$entry"
    mkdir -p "$tmp/$(dirname "$path")"
    if [[ -n "${MULTIPROMPT_DESKTOP_REPO:-}" ]]; then
        git -C "$MULTIPROMPT_DESKTOP_REPO" show "$revision:src/$path" > "$tmp/$path"
    elif [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        curl --fail --silent --show-error --location "https://raw.githubusercontent.com/valentinyeo/multiprompt/$revision/src/$path" > "$tmp/$path"
    else
        printf 'Set MULTIPROMPT_DESKTOP_REPO to the canonical desktop repository\n' >&2
        exit 1
    fi
    printf '%s  %s\n' "$hash" "$tmp/$path" | sha256sum --check --status
    asset="app/src/main/assets/$path"
    if [[ "${1:-}" == --check ]]; then
        cmp "$tmp/$path" "$asset"
    else
        mkdir -p "$(dirname "$asset")"
        cp "$tmp/$path" "$asset"
    fi
done
printf 'Reader and connectors match canonical revision and SHA256\n'
