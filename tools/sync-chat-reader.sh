#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=6572511ca37a48b58bc6cc88d0fa4898d2bf4d71
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
mode=write
if [[ "${1:-}" == --check ]]; then
    mode=check
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py 9ff8ec0840f74bead4c4428662fe88baceedba58e56456f273301bab8b6f8350' \
    'chat_connectors/claude.py 7be13a50645bbcdb32f606e152ec75927a135db2f15c7310df50d899d093deca' \
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
    if [[ "$path" == chat_history_reader.py ]]; then
        patch --silent "$tmp/$path" tools/chat-reader-worker.patch
    fi
    asset="app/src/main/assets/$path"
    if [[ "$mode" == check ]]; then
        cmp "$tmp/$path" "$asset"
    else
        mkdir -p "$(dirname "$asset")"
        cp "$tmp/$path" "$asset"
    fi
done
python3 -B tools/chat-reader-bundle.py --revision "$revision+mubo-529" "$mode"
printf 'Reader and connectors match canonical revision, MUBO-529 patch and SHA256\n'
