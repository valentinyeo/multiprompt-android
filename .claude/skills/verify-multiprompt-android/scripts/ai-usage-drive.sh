#!/usr/bin/env bash
usage_state() {
    local phase=$1
    shot "ai-usage-$phase"
    python3 "$scripts/mpa" ai-usage \
        --phase "$phase" \
        --xml "$EVIDENCE/$(printf '%02d' "$step")-ai-usage-$phase.xml" \
        --png "$EVIDENCE/$(printf '%02d' "$step")-ai-usage-$phase.png" \
        --receipts "$EVIDENCE/ai-usage-receipts.txt" \
        --out "$EVIDENCE/ai-usage-$phase.json"
}
tap_desc 'Session list actions'
wait_for_text 'AI usage'
usage_state menu
if sudo test -s /home/verifyssh/ai-usage-receipts.txt; then
    printf 'Usage was read before opening AI usage\n' >&2
    exit 1
fi
tap_text 'AI usage'
wait_for_text Claude
wait_for_text Codex
wait_for_text '37.5%'
wait_for_text '62.5%'
sudo cat /home/verifyssh/ai-usage-receipts.txt > "$EVIDENCE/ai-usage-receipts.txt"
usage_state open
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'AI usage'
wait_for_text Inbox
wait_for_text verify-session
usage_state closed
printf 'AI_USAGE_OPEN_NUMBERS_CLOSE_OK\n' | tee "$EVIDENCE/ai-usage-proof.txt"
