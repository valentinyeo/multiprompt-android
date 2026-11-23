#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
product_checks=1
"$mpa" read --wait-text Inbox
"$mpa" tap text live-resumed-2715
"$mpa" read --select contains --value RESUMED_READY_2715 --timeout 60
"$mpa" screenshot resumed-chat
"$mpa" proof --name resumed-chat --require-text RESUMED_READY_2715 --forbid-text RESUMED_NEIGHBOUR_2715 \
    --note RESUMED_CHAT_SHOWS_RESUMED_SESSION_2715 --proof-file "$EVIDENCE/resumed-chat-proof.txt"
if ! "$mpa" read --select contains --value CODE_BLOCK_2975_START --timeout 3 >/dev/null 2>&1; then
    "$mpa" swipe --inside RESUMED_READY_2715 --axis vertical --direction forward
fi
"$mpa" read --select contains --value CODE_BLOCK_2975_START --timeout 60
"$mpa" screenshot code-block
"$mpa" proof --name code-block --require-text CODE_BLOCK_2975_START \
    --note RESUMED_CHAT_SHOWS_CODE_BLOCK_2975 --proof-file "$EVIDENCE/code-block-proof.txt"
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" tap text live-fresh-2715
"$mpa" read --select contains --value FRESH_READY_2715 --timeout 60
"$mpa" screenshot fresh-chat
"$mpa" proof --name fresh-chat --require-text FRESH_READY_2715 --forbid-text FRESH_NEIGHBOUR_2715 \
    --note FRESH_CHAT_STAYS_ON_ITS_OWN_TRANSCRIPT_2715 --proof-file "$EVIDENCE/fresh-chat-proof.txt"
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" tap text live-compacted-2715
"$mpa" read --select contains --value COMPACTED_AFTER_2715 --timeout 60
"$mpa" screenshot compacted-chat
"$mpa" proof --name compacted-chat --require-text COMPACTED_AFTER_2715 --forbid-text COMPACTED_BEFORE_2715 \
    --forbid-text COMPACTED_NEIGHBOUR_2715 --note COMPACTED_CHAT_FOLLOWS_LIVE_SESSION_2715 \
    --proof-file "$EVIDENCE/compacted-chat-proof.txt"
printf 'CHAT_SHAPES_VISIBLE_2715\n'
exit 0
