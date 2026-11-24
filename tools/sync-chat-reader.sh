#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=4e43135910fd38cb8939506a29a07c2ea62c1066
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
mode=write
if [[ "${1:-}" == --check ]]; then
    mode=check
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py 46217cb5fe289a4ca05b76837821d729a2d8720c810ed795104db80c09cbf211' \
    'chat_connectors/claude.py 7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py bdee3cdd7ad1b82c2abdc5e549633395841a015ad9cb27520f822d66125a0a42' \
    'multibot_feedback.py 9561748d6986ff93112be8e0c0b1f05bdbb00e21a5c4a97361fd697041142562'; do
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
    if [[ "$mode" == check ]]; then
        cmp "$tmp/$path" "$asset"
    else
        mkdir -p "$(dirname "$asset")"
        cp "$tmp/$path" "$asset"
    fi
done
python3 -B tools/chat-reader-bundle.py --revision "$revision" "$mode"
printf 'Reader and connectors match canonical revision and SHA256\n'
