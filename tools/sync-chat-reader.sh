#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=3b57ab3b2295cba313f402ed95fa61de1a415b4e
[[ "$(cat app/src/main/assets/chat_history_reader.sha)" == "$revision" ]]
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py f53de90e796b43612b79c8dda895065a68dff559ecb486cc5a2f445ee989c75c' \
    'chat_connectors/claude.py 7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py bdee3cdd7ad1b82c2abdc5e549633395841a015ad9cb27520f822d66125a0a42' \
    'multibot_feedback.py 960a574620ca6cfe5649a403fffad8f8704e937138292d356ce3c46167690ce9'; do
    read -r path hash <<< "$entry"
    asset="app/src/main/assets/$path"
    if [[ "$path" == chat_history_reader.py || "$path" == multibot_feedback.py ]]; then
        printf '%s  %s\n' "$hash" "$asset" | sha256sum --check --status
        continue
    fi
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
    if [[ "${1:-}" == --check ]]; then
        cmp "$tmp/$path" "$asset"
    else
        mkdir -p "$(dirname "$asset")"
        cp "$tmp/$path" "$asset"
    fi
done
printf 'Reader and connectors match canonical revision and SHA256\n'
