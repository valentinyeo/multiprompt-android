#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
"$mpa" tap text verify-session
"$mpa" read --wait-text 'Message verify-session'
"$mpa" read --wait-text 'Native session skills are ready.'
"$mpa" screenshot clear-ready
"$mpa" proof --name clear-ready --require-text 'Native session skills are ready.'
"$mpa" fill 'Message verify-session' /cl
"$mpa" read --wait-text /client
"$mpa" read --wait-text /aaa-paper
"$mpa" read --select editor-value --value composer --extra '/cl'
"$mpa" screenshot clear-query
"$mpa" proof --name clear-query \
    --order /clear \
    --order /client \
    --order /aaa-paper \
    --require-text 'Start a new session with empty context'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-before.txt"
[[ ! -s "$EVIDENCE/clear-before.txt" ]]
"$mpa" tap text /clear
"$mpa" read --select editor-value --value composer --extra '/clear '
"$mpa" screenshot clear-selected
"$mpa" proof --name clear-selected --forbid-text /client --forbid-text /aaa-paper
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-selected-host.txt"
[[ ! -s "$EVIDENCE/clear-selected-host.txt" ]]
"$mpa" tap desc 'Send prompt'
for attempt in {1..30}; do
    if sudo grep -Fx 'VERIFY_RECEIVED:/clear' /home/verifyssh/receipts.txt; then break; fi
    sleep 1
done
"$mpa" read --wait-text 'Received /clear. Claude'
"$mpa" read --select editor-value --value composer --extra ''
"$mpa" screenshot clear-sent
"$mpa" proof --name clear-sent --require-text 'Received /clear. Claude' --forbid-text '[mp:'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/clear-receipts.txt"
receipt_lines=$(wc -l < "$EVIDENCE/clear-receipts.txt")
[[ "$receipt_lines" == 1 || "$receipt_lines" == 2 ]]
sed -n 1p "$EVIDENCE/clear-receipts.txt" | grep -Fx 'VERIFY_RECEIVED:/clear'
delivery_tag=
if [[ "$receipt_lines" == 2 ]]; then
    sed -n 2p "$EVIDENCE/clear-receipts.txt" | grep -Eqx 'VERIFY_RECEIVED:\[mp:[0-9a-f]{16}\]'
    delivery_tag=$(sed -n 2p "$EVIDENCE/clear-receipts.txt" | sed -E 's/^VERIFY_RECEIVED:\[mp:([0-9a-f]{16})\]$/\1/')
fi
printf '{"command":"/clear","command_count":1,"delivery_tag":"%s","raw_receipts":"clear-receipts.txt"}\n' "$delivery_tag" > "$EVIDENCE/clear-host.json"
printf 'CLAUDE_CLEAR_RANKING_JOURNEY_OK\n' | tee "$EVIDENCE/clear-proof.txt"
step=$((step + 5))
