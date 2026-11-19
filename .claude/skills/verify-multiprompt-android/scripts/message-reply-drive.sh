#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
"$mpa" tap text verify-session
"$mpa" read --wait-text 'Message verify-session'
"$mpa" read --wait-text REPLY_BUBBLE_418
"$mpa" hold text REPLY_BUBBLE_418
"$mpa" read --wait-text Reply
"$mpa" screenshot reply-menu
"$mpa" tap text Reply
"$mpa" read --wait-text Replying
"$mpa" read --select desc --value 'Cancel reply' --timeout 15
"$mpa" screenshot reply-open
"$mpa" proof --name reply-open --require-text Replying --require-text REPLY_BUBBLE_418 --require-desc 'Cancel reply'
"$mpa" tap desc 'Cancel reply'
"$mpa" read --wait-absent Replying
if "$mpa" read --select desc --value 'Cancel reply' >/dev/null 2>&1; then
    printf 'Reply bar stayed open\n' >&2
    exit 1
fi
"$mpa" screenshot reply-cancelled
"$mpa" proof --name reply-cancelled --forbid-text Replying --forbid-desc 'Cancel reply'
"$mpa" hold text REPLY_BUBBLE_418
"$mpa" read --wait-text Reply
"$mpa" tap text Reply
"$mpa" read --wait-text Replying
"$mpa" fill 'Message verify-session' REPLY_BODY_418
"$mpa" tap desc 'Send prompt'
for attempt in {1..30}; do
    if sudo grep -Fx 'REPLY_BODY_418' /home/verifyssh/receipts.txt && sudo grep -Fx '> REPLY_BUBBLE_418' /home/verifyssh/receipts.txt; then break; fi
    sleep 1
done
"$mpa" read --select editor-value --value composer --extra ''
"$mpa" read --wait-text REPLY_DELIVERED_418
"$mpa" screenshot reply-sent
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reply-receipts.txt"
grep -Fx '> REPLY_BUBBLE_418' "$EVIDENCE/reply-receipts.txt"
grep -Fx 'REPLY_BODY_418' "$EVIDENCE/reply-receipts.txt"
"$mpa" proof --name reply-sent --require-text REPLY_BODY_418 --require-text REPLY_DELIVERED_418 --forbid-text Replying --forbid-text 'Not sent' --forbid-text 'Send again' --forbid-text Remove --forbid-desc 'Cancel reply' --note MESSAGE_REPLY_JOURNEY_OK --proof-file "$EVIDENCE/reply-proof.txt"
step=$((step + 4))
