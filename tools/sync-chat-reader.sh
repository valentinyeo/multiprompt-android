#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=0f1fb9ee3ab9cc427273099c7baf6dffe78d2916
[[ "$(cat app/src/main/assets/chat_history_reader.sha)" == "$revision" ]]
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py bbcc265ea2dc816b8d66024b9fc8c67236e53cde27e0712eeae39153153c7f4f' \
    'chat_connectors/claude.py 1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31'; do
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
