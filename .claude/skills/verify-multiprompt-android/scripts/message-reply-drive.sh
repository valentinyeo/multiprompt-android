#!/usr/bin/env bash
tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text REPLY_BUBBLE_418
hold_text REPLY_BUBBLE_418
wait_for_text Reply
shot reply-menu
tap_text Reply
wait_for_text Replying
wait_for_desc() {
    local attempt
    for attempt in {1..15}; do
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" desc 'Cancel reply' >/dev/null 2>&1; then return; fi
        sleep 1
    done
    printf 'Cancel reply never appeared\n' >&2
    return 1
}
wait_for_desc
shot reply-open
python3 tools/message-reply/state.py "$EVIDENCE/$(printf '%02d' "$step")-reply-open.xml" open > "$EVIDENCE/reply-open.json"
cat "$EVIDENCE/reply-open.json"
tap_desc 'Cancel reply'
wait_for_absence Replying
shot reply-cancelled
python3 tools/message-reply/state.py "$EVIDENCE/$(printf '%02d' "$step")-reply-cancelled.xml" cancelled > "$EVIDENCE/reply-cancelled.json"
cat "$EVIDENCE/reply-cancelled.json"
hold_text REPLY_BUBBLE_418
wait_for_text Reply
tap_text Reply
wait_for_text Replying
fill 'Message verify-session' REPLY_BODY_418
tap_desc 'Send prompt'
wait_receipt 'REPLY_BODY_418'
wait_receipt '> REPLY_BUBBLE_418'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reply-receipts.txt"
grep -Fx '> REPLY_BUBBLE_418' "$EVIDENCE/reply-receipts.txt"
grep -Fx 'REPLY_BODY_418' "$EVIDENCE/reply-receipts.txt"
shot reply-sent
python3 - "$EVIDENCE/reply-sent.json" <<'PY'
import json, sys
json.dump({"quoted": "> REPLY_BUBBLE_418", "body": "REPLY_BODY_418"}, open(sys.argv[1], "w"), indent=2)
open(sys.argv[1], "a").write("\n")
PY
printf 'MESSAGE_REPLY_JOURNEY_OK\n' | tee "$EVIDENCE/reply-proof.txt"
