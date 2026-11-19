#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
"$mpa" tap desc 'Session list actions'
"$mpa" read --wait-text 'AI usage'
"$mpa" screenshot ai-usage-menu
"$mpa" proof --name ai-usage-menu --require-text 'AI usage' --require-text Refresh
if sudo test -s /home/verifyssh/ai-usage-receipts.txt; then
    printf 'Usage was read before opening AI usage\n' >&2
    exit 1
fi
"$mpa" tap text 'AI usage'
"$mpa" read --wait-text Claude
"$mpa" read --wait-text Codex
"$mpa" read --wait-text '37.5%'
"$mpa" read --wait-text '62.5%'
sudo cat /home/verifyssh/ai-usage-receipts.txt > "$EVIDENCE/ai-usage-receipts.txt"
"$mpa" screenshot ai-usage-open
"$mpa" proof --name ai-usage-open \
    --require-text 'AI usage' \
    --forbid-text 'Loading usage' \
    --forbid-text 'No usage yet' \
    --forbid-text 'Usage is out of date' \
    --forbid-text 'Could not read AI usage' \
    --order Claude \
    --order '37.5%' \
    --order BEHIND \
    --order 'WEEK 37.5% | 5H 12.5% | EMPTY IN 42H' \
    --order Codex \
    --order '62.5%' \
    --order AHEAD \
    --order 'WEEK 62.5% | 5H 25% | EMPTY IN 18H'
[[ -s "$EVIDENCE/ai-usage-receipts.txt" ]]
[[ $(grep -Fc '"command": "cat ~/.local/state/token-ladder/status.json", "exitCode": 0' "$EVIDENCE/ai-usage-receipts.txt") == "$(wc -l < "$EVIDENCE/ai-usage-receipts.txt")" ]]
[[ $(sed -E 's/^.*"stdout": //' "$EVIDENCE/ai-usage-receipts.txt" | sort -u | wc -l) == 1 ]]
grep -F '\"provider\": \"claude\"' "$EVIDENCE/ai-usage-receipts.txt"
grep -F '\"provider\": \"codex\"' "$EVIDENCE/ai-usage-receipts.txt"
grep -F '\"used\": 37.5' "$EVIDENCE/ai-usage-receipts.txt"
grep -F '\"used\": 62.5' "$EVIDENCE/ai-usage-receipts.txt"
"$mpa" key back
"$mpa" read --wait-absent 'AI usage'
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text verify-session
"$mpa" screenshot ai-usage-closed
"$mpa" proof --name ai-usage-closed \
    --require-text Inbox \
    --require-text verify-session \
    --forbid-text 'AI usage' \
    --forbid-text Claude \
    --forbid-text Codex \
    --note AI_USAGE_OPEN_NUMBERS_CLOSE_OK \
    --proof-file "$EVIDENCE/ai-usage-proof.txt"
step=$((step + 3))
