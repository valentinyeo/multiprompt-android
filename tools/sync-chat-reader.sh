#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=f6d470f180f746e83f3e4171de2913f9d7dfc015
[[ $# -le 1 && ( "${1:-}" == "" || "${1:-}" == --check ) ]]
mode=write
if [[ "${1:-}" == --check ]]; then
    mode=check
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in \
    'chat_history_reader.py 17f0a4b781fa5fc97a652d5845dc9bc2d5b4f78f7c1f3c9109b4a9d50ec1ce68' \
    'chat_connectors/claude.py 7be13a50645bbcdb32f606e152ec75927a135db2f15c7310df50d899d093deca' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py d3577c05a744fe45c390fb29cbe1c98cb3eb41b3ae178eafd1c885235a026569' \
    'multibot_feedback.py 0007d673e04fe9ad571c0070e39aff82ef3dff61d92ac0820ab361cf84a9bd2a'; do
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
