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
fetch() {
    mkdir -p "$tmp/$(dirname "$1")"
    if [[ -n "${MULTIPROMPT_DESKTOP_REPO:-}" ]]; then
        git -C "$MULTIPROMPT_DESKTOP_REPO" show "$revision:$1" > "$tmp/$1"
    elif [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        curl --fail --silent --show-error --location "https://raw.githubusercontent.com/valentinyeo/multiprompt/$revision/$1" > "$tmp/$1"
    else
        printf 'Set MULTIPROMPT_DESKTOP_REPO to the canonical desktop repository\n' >&2
        exit 1
    fi
    printf '%s  %s\n' "$2" "$tmp/$1" | sha256sum --check --status
}
place() {
    if [[ "$mode" == check ]]; then
        cmp "$tmp/$1" "$2"
    else
        mkdir -p "$(dirname "$2")"
        cp "$tmp/$1" "$2"
    fi
}
for entry in \
    'chat_history_reader.py 17f0a4b781fa5fc97a652d5845dc9bc2d5b4f78f7c1f3c9109b4a9d50ec1ce68' \
    'chat_connectors/claude.py 7be13a50645bbcdb32f606e152ec75927a135db2f15c7310df50d899d093deca' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py d3577c05a744fe45c390fb29cbe1c98cb3eb41b3ae178eafd1c885235a026569' \
    'multibot_feedback.py 0007d673e04fe9ad571c0070e39aff82ef3dff61d92ac0820ab361cf84a9bd2a'; do
    read -r path hash <<< "$entry"
    fetch "src/$path" "$hash"
    place "src/$path" "app/src/main/assets/$path"
done
for entry in \
    'tools/multibot-feedback-test.py d1881053e8377aed1cbabc5acbf309429ef761f507f41ed0c7646c9cb299af88' \
    'tools/chat-history-reader-test.py 5cf39892182f51499338c5cce4f6150dc1923651607f344c813d05395ece419a' \
    'tools/fixtures/MUBO-135/hax-reply-ids.jsonl 97ce377f262c2abc4222bd4ddfed4bf5da69aa6061df314e131b632a04c81a82' \
    'tools/fixtures/MUBO-135/hax-reply.jsonl 3e6d7642a01e0447fcf0834bf0037a379fb8a92f96c25e45886c366580ae19fa' \
    'tools/fixtures/MUBO-135/history-wire.txt 32dd4163473a319a81acf3323d2a62809c2d17c6c69932c48b999649259fadfe' \
    'tools/fixtures/MUBO-135/live-events.json e719dd9afde2790d7f74e63bb1e3127bbfdb62eda39f5fb9f620cbaa8816b97c' \
    'tools/fixtures/MUBO-293/slash-command-reply.jsonl 6819cbe8e7dfcf22bf46f4cc065dbaefd051060ffb5cca758d9ad0491ffd864c' \
    'tools/fixtures/MUBO-293/unknown-slash-command.jsonl 587cf778b1c1eaa29845d4944d53474745ae1b12ce66b000d01fb66989606925'; do
    read -r path hash <<< "$entry"
    fetch "$path" "$hash"
    place "$path" "tools/vendor/chat-reader/$path"
done
python3 -B tools/chat-reader-bundle.py --revision "$revision" "$mode"
printf 'Reader, connectors, vendored tests and their fixtures match canonical revision and SHA256\n'
