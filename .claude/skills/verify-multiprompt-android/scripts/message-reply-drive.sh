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
grep -Fx 'live-cl_mention-2953|Mention 2953' "$EVIDENCE/mention-session.txt"
"$mpa" fill 'Message verify-session' '@Mention'
"$mpa" read --wait-text 'Mention 2953'
"$mpa" screenshot mention-list
"$mpa" proof --name mention-list --require-text 'Mention 2953' --forbid-text 'live-cl_mention-2953' --note MENTION_LIST_NAMED_BY_CHAT --proof-file "$EVIDENCE/mention-proof.txt"
"$mpa" tap text 'Mention 2953'
"$mpa" read --wait-text '@Mention 2953'
"$mpa" tap desc 'Send prompt'
for attempt in {1..30}; do
    if sudo grep -Fx '@Mention 2953{live-cl_mention-2953}' /home/verifyssh/receipts.txt; then break; fi
    sleep 1
done
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/mention-receipts.txt"
grep -Fx '@Mention 2953{live-cl_mention-2953}' "$EVIDENCE/mention-receipts.txt"
"$mpa" read --select editor-value --value composer --extra ''
"$mpa" read --wait-text '@Mention 2953'
"$mpa" screenshot mention-sent
"$mpa" proof --name mention-sent --require-text '@Mention 2953' --forbid-text 'live-cl_mention-2953' --forbid-text '{live-cl_mention-2953}' --note MENTION_ROUTES_TO_SESSION --proof-file "$EVIDENCE/mention-sent-proof.txt"
"$mpa" fill 'Message verify-session' '@'
"$mpa" read --select contains --value 'Bot' --timeout 15
"$mpa" fill 'Message verify-session' '@verify-session'
"$mpa" read --select text --value 'verify-session' --inside Bot --timeout 15
"$mpa" screenshot self-mention-list
"$mpa" proof --name self-mention-list --require-text 'verify-session' --note MENTION_OFFERS_TYPING_CHAT --proof-file "$EVIDENCE/self-mention-list-proof.txt"
"$mpa" tap text Bot
"$mpa" read --wait-text '@verify-session'
"$mpa" tap desc 'Send prompt'
for attempt in {1..30}; do
    if sudo grep -Fx '@verify-session{verify-session}' /home/verifyssh/receipts.txt; then break; fi
    sleep 1
done
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/mention-receipts.txt"
grep -Fx '@verify-session{verify-session}' "$EVIDENCE/mention-receipts.txt"
"$mpa" read --select editor-value --value composer --extra ''
"$mpa" read --wait-text '@verify-session'
"$mpa" screenshot self-mention-sent
"$mpa" proof --name self-mention-sent --require-text '@verify-session' --forbid-text '{verify-session}' --note MENTION_OF_TYPING_CHAT_ROUTES --proof-file "$EVIDENCE/self-mention-sent-proof.txt"
step=$((step + 4))
