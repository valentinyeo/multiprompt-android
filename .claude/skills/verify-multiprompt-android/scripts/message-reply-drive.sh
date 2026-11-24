#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
shot_pair() {
    "$mpa" screenshot "$1" | python3 -B -c 'import json, sys; shot = json.load(sys.stdin); print(shot["xml"], shot["png"])'
}
# The mention chat chooses its own bot look through the app first, so the row
# that names it can be compared with the bot that chat draws for itself.
"$mpa" tap text 'Mention 2953'
"$mpa" read --select desc --value 'Session actions' --timeout 20
read -r before_xml before_png <<< "$(shot_pair mention-bot-before)"
"$mpa" tap desc 'Session actions'
"$mpa" tap text 'Bot look'
"$mpa" read --wait-text Face
"$mpa" tap desc 'Face 3'
"$mpa" tap desc 'Colour 4'
"$mpa" key back
"$mpa" read --wait-absent 'Bot look'
read -r chosen_xml chosen_png <<< "$(shot_pair mention-bot-chosen)"
"$mpa" key back
"$mpa" read --wait-text Inbox
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
read -r mention_xml mention_png <<< "$(shot_pair mention-list)"
python3 -B tools/message-reply/bot-face.py --session live-cl_mention-2953 \
    --before "$before_xml" "$before_png" \
    --chosen "$chosen_xml" "$chosen_png" \
    --mention "$mention_xml" "$mention_png" \
    --out "$EVIDENCE/mention-bot-face.json"
"$mpa" proof --name mention-list --require-text 'Mention 2953' --require-desc 'Robot live-cl_mention-2953' --forbid-text 'live-cl_mention-2953' --note MENTION_LIST_NAMED_BY_CHAT --proof-file "$EVIDENCE/mention-proof.txt"
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
step=$((step + 4))
