#!/usr/bin/env bash
search_state() {
    local name=$1 query=$2 count=$3 target=${4:-}
    if [[ "$count" != closed ]]; then wait_for_text "$count"; fi
    if [[ -n "$target" ]]; then wait_for_text "$target"; fi
    shot "search-$name"
    python3 "$scripts/mpa" chat-search \
        --xml "$EVIDENCE/$(printf '%02d' "$step")-search-$name.xml" \
        --png "$EVIDENCE/$(printf '%02d' "$step")-search-$name.png" \
        --query "$query" --count "$count" --target "$target"
}

tap_text verify-session
wait_for_text VERIFY_SEARCH_READY_2540
wait_for_text 'Message verify-session'
wait_for_text 'orchard south'
wait_for_absence 'orchard north'
search_state chat '' closed
tap_desc 'Session actions'
wait_for_text 'Search in chat'
shot search-menu
tap_text 'Search in chat'
search_state open '' '0 of 0'
fill 'Search this chat' orchard
search_state newest orchard '2 of 2' 'orchard south'
tap_desc 'Previous match'
search_state previous orchard '1 of 2' 'orchard north'
tap_desc 'Previous match'
search_state wrap-previous orchard '2 of 2' 'orchard south'
tap_desc 'Next match'
search_state wrap-next orchard '1 of 2' 'orchard north'
tap_desc 'Next match'
search_state next orchard '2 of 2' 'orchard south'
fill orchard NO_MATCH_2540
search_state no-match NO_MATCH_2540 '0 of 0'
fill NO_MATCH_2540 orchard
search_state restored orchard '2 of 2' 'orchard south'
tap_desc 'Close search'
wait_for_text 'Message verify-session'
search_state closed '' closed
if sudo test -f /home/verifyssh/receipts.txt; then
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/search-host-input.txt"
    [[ ! -s "$EVIDENCE/search-host-input.txt" ]]
fi
printf 'CHAT_SEARCH_JOURNEY_OK\n' | tee "$EVIDENCE/search-proof.txt"
