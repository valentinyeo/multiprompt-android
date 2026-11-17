#!/usr/bin/env bash
clear_state() {
    local phase=$1
    shot "clear-$phase"
    python3 tools/clear-ranking/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-clear-$phase.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-clear-$phase.png" \
        "$phase" > "$EVIDENCE/clear-$phase.json"
    cat "$EVIDENCE/clear-$phase.json"
}

tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text 'Native session skills are ready.'
clear_state ready
fill 'Message verify-session' /cl
wait_for_text /client
wait_for_text /aaa-paper
clear_state query
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-before.txt"
[[ ! -s "$EVIDENCE/clear-before.txt" ]]
tap_text /clear
select_node focused-field '/clear ' >/dev/null
clear_state selected
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-selected-host.txt"
[[ ! -s "$EVIDENCE/clear-selected-host.txt" ]]
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:/clear'
wait_for_text 'Received /clear. Claude'
clear_state sent
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-receipts.txt"
python3 tools/clear-ranking/ui.py receipts "$EVIDENCE/clear-receipts.txt" > "$EVIDENCE/clear-host.json"
cat "$EVIDENCE/clear-host.json"
printf 'CLAUDE_CLEAR_RANKING_JOURNEY_OK\n' | tee "$EVIDENCE/clear-proof.txt"
