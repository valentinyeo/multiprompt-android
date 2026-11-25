#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=ee643caf5b5c32567790124c6209942b2a95d86b
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
    'chat_history_reader.py b5cc6695b808d6b5fc56af93cb737e65d9c5d3deffa262717536d1afe6282d0d' \
    'chat_connectors/claude.py 7be13a50645bbcdb32f606e152ec75927a135db2f15c7310df50d899d093deca' \
    'chat_connectors/codex.py 07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31' \
    'mp_send.py d3577c05a744fe45c390fb29cbe1c98cb3eb41b3ae178eafd1c885235a026569' \
    'multibot_feedback.py 0007d673e04fe9ad571c0070e39aff82ef3dff61d92ac0820ab361cf84a9bd2a'; do
    read -r path hash <<< "$entry"
    fetch "src/$path" "$hash"
    place "src/$path" "app/src/main/assets/$path"
done
for entry in \
    'src/mp_fresh.py 753260f1173595d27ca184ed7e88d784beb05ce0f3a3b7c3c433acbe94b095ef' \
    'src/mp_helper.py 129feeb61dfc65e2288e8a7786204a84cbc65be04d8022f54749d33a8c6481a2' \
    'src/mp_layout.mjs 515c4868fe0b3b04c23b1548dd3f933f1906b704e3e1fe4b4d186cef8b99985c' \
    'src/mp_sessions.py 9af389a14add75d6b58aa3f2a9f8971658004267ea8f931269bf4880c42bf333' \
    'src/mp_shell.py 72cf80380a5d5f04d18578baab9a43eed4d8e52f36a1484bd44ff55f290c79e3' \
    'src/mp_zsb.sh e25de7b85dc215ded8008cf922ae5292ef3ce71c14fa2d6ea5c7587d5c45bf8a' \
    'tools/chat-bench/reader-service.py 910d361b4ecb7f11f883a3fc28f4e9fa3a79aa0f83cd2a9e78bf16c4be2f4b6a' \
    'tools/chat-history-reader-test.py 5cf39892182f51499338c5cce4f6150dc1923651607f344c813d05395ece419a' \
    'tools/chat-shared-runtime.test.py bf100c22dfad002e198acf7e312dc2e90690857c05b968ab6c45a1bd422a5902' \
    'tools/chat-transcript-reconcile.test.py 6b8fea5966b38bae0b11e4b18a1a67615cff25abcba90d09ba32af1881359f8b' \
    'tools/check-red-run.mjs 691a2340d30e603f9c42b928ff5d3a4f618aef6bbb9b2eb380d5c46dd5293f07' \
    'tools/fixtures/IGSH-1229/historical-resolver.py b9d68415b083426a84545ff389250b69e50c41fa6a6d7d20def5287664f52896' \
    'tools/fixtures/IGSH-1544/codex-app-server.json f14aedf27582107b48b1e2f107b81f1e793f053167beb195d42cf0196ae7ca96' \
    'tools/fixtures/IGSH-1545/pi-rpc.json e421248787b0c8f8344330402a6c6a0247a6642d7050adca4604cc4746b91ee6' \
    'tools/fixtures/IGSH-722/queue-ops.jsonl 34f1178503b88bada0bcd42329818b7c1a304d6ae5120274e454b7a0c9ade3ac' \
    'tools/fixtures/IGSH-835/identical-sends.jsonl a7b816726b53465cb554d8072d5268be5efba75443d4f9a6eb05168de2d1abf7' \
    'tools/fixtures/IGSH-835/queue-replay-before.py 46a083e7fe6c7d434d6a7c7d76b64a8a2c631fee604725aaa2d0bab2dc313872' \
    'tools/fixtures/IGSH-835/recorded-transcript.jsonl 6dd2f24d2c608bdcbed8f315a5db6199509b1b75505632f48d1650e8b9e64436' \
    'tools/fixtures/MUBO-135/hax-reply-ids.jsonl 97ce377f262c2abc4222bd4ddfed4bf5da69aa6061df314e131b632a04c81a82' \
    'tools/fixtures/MUBO-135/hax-reply.jsonl 3e6d7642a01e0447fcf0834bf0037a379fb8a92f96c25e45886c366580ae19fa' \
    'tools/fixtures/MUBO-135/history-wire.txt 32dd4163473a319a81acf3323d2a62809c2d17c6c69932c48b999649259fadfe' \
    'tools/fixtures/MUBO-135/live-events.json e719dd9afde2790d7f74e63bb1e3127bbfdb62eda39f5fb9f620cbaa8816b97c' \
    'tools/fixtures/MUBO-246/blocked-by-hook.jsonl 2b0fee4d2ac3a675f9654dcf00dc596afee9ca532cc7e1d2c56bbbf6a792e931' \
    'tools/fixtures/MUBO-293/slash-command-reply.jsonl 6819cbe8e7dfcf22bf46f4cc065dbaefd051060ffb5cca758d9ad0491ffd864c' \
    'tools/fixtures/MUBO-293/unknown-slash-command.jsonl 587cf778b1c1eaa29845d4944d53474745ae1b12ce66b000d01fb66989606925' \
    'tools/fixtures/SOFT-2423/skill-quote.jsonl 589e75db8154d117ce8ec30edad19fa7b3733c76cf05e9bba0ff5f01d0e3d7ee' \
    'tools/fixtures/SOFT-2524/legacy-reader.py 162f89fb38bf23a59028dd51a7aa1751bb832bbce90ad9dbb04d8bfe97f36a42' \
    'tools/fixtures/SOFT-2779/chat-tail-tool-heavy.jsonl.gz 20d46dd9aae25599d84a0214c5b9af9e2854712d4f28bac4186f88aa289089a9' \
    'tools/fixtures/SOFT-2779/fixture.py 9b8ec7eafc902ba8343c9df4400ab6aca4eea59ea249e61293aa38d66fc5696c' \
    'tools/fixtures/SOFT-3054/scenario.json 3a83806f7d11b6cf1c568485d10f4c46d4c70c5c67794ceb8138f1c33083e859' \
    'tools/fixtures/SOFT-3097/skill-quote-echo.jsonl d810a10e281dee7bcd2f32032a6229d9dc186b851d563741df1a36c83e1a9830' \
    'tools/fixtures/igsh361/upwork-compact.json abe68a64663fd15f503a65bd35bdb34af1dc23cddebf91a3e10197071480ce72' \
    'tools/fixtures/reader-before-5880899.py a2756c37229690b09c90815fc701920adc7efd68d0d42662eefb3ee2db95a54f' \
    'tools/fixtures/reader-before-7a02ac9.py 62ef5f65770e1617a376160aa99b1c9eadf4c52834ae9ead1bb3a4889fe26842' \
    'tools/fixtures/reader-before-e85be39.py 6338d87b01753f32b73cb277c21c154afb961801b8d4917271f185d80ab7ef32' \
    'tools/mp-layout.mjs 515c4868fe0b3b04c23b1548dd3f933f1906b704e3e1fe4b4d186cef8b99985c' \
    'tools/multibot-feedback-test.py d1881053e8377aed1cbabc5acbf309429ef761f507f41ed0c7646c9cb299af88' \
    'tools/native-test-parts.mjs 4bca41622a6908a6390121bcade7eb2cf126e18cbd756972a3694da144d1af4d' \
    'tools/restart-ai-test.py 2b4a3b23ea9bae241f20d057813a6b26b15ae7493a4d7063243c9cf31af09f63' \
    'tools/strip-comments.mjs 0297b7c00a36d36c26b5309f57a9625d6bf8d065195747e471fbda24ece8a0ee' \
    'tools/ticket-id.js d5b7bdd49760b306f016f8c9815a7aa8418d3e601da90d70c0de67ac453cd65c' \
    'tools/ticket-titles.mjs 160eba11d179861eb47a26b28ae636ceafc39f422a150252782e2b8943d2ad98' \
    'tools/version-stamp.mjs 4ad833e737e9b28077676eddd810812df5260a5c4d53807555cc635920445b90'; do
    read -r path hash <<< "$entry"
    fetch "$path" "$hash"
    place "$path" "tools/vendor/chat-reader/$path"
done
python3 -B tools/chat-reader-bundle.py --revision "$revision" "$mode"
printf 'Reader, connectors, vendored tools and tests and their fixtures match canonical revision and SHA256\n'
