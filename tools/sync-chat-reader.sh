#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=8e05690c08160a9f8e68621875c22e3447389fea
[[ "$(cat app/src/main/assets/chat_history_reader.sha)" == "$revision" ]]
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py 47f6c2c285ede376761c1bbe67dcc2b2aa2c85972b1ffe5acd0d78ecad478267' \
    'chat_connectors/claude.py 7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py 6b51ea8f42b36b03330c36b9226b4e4580f419a169b9bb00c34db15ec9a5e477' \
    'multibot_feedback.py 960a574620ca6cfe5649a403fffad8f8704e937138292d356ce3c46167690ce9'; do
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
