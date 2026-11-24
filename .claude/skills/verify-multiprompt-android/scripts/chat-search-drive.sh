#!/usr/bin/env bash
search_target_bounds() {
    python3 tools/chat-search/bounds.py "$EVIDENCE/current.xml" "$1" "$package"
}
search_settle_target() {
    local target=$1 attempt current before=''
    for attempt in {1..8}; do
        dump_ui || return 1
        if current=$(search_target_bounds "$target"); then
            if [[ -n "$before" && "$current" == "$before" ]]; then
                printf 'SEARCH_TARGET_SETTLED: %s at %s after %s dumps\n' "$target" "$current" "$attempt" | tee -a "$EVIDENCE/search-settle.txt"
                return 0
            fi
            before=$current
        else
            before=
        fi
        sleep 1
    done
    printf 'SEARCH_TARGET_MOVING: %s bounds never repeated in 8 dumps; shooting and retaking if the pixels disagree\n' "$target" | tee -a "$EVIDENCE/search-settle.txt" >&2
    return 1
}
search_state() {
    local name=$1 query=$2 count=$3 target=${4:-} attempt tries=1 base error
    if [[ "$count" != closed ]]; then wait_for_text "$count"; fi
    if [[ -n "$target" ]]; then
        wait_for_text "$target"
        search_settle_target "$target" || true
        tries=5
    fi
    for ((attempt = 1; attempt <= tries; attempt++)); do
        shot "search-$name"
        base="$EVIDENCE/$(printf '%02d' "$step")-search-$name"
        error="$EVIDENCE/search-$name-attempt-$attempt.txt"
        if python3 tools/chat-search/ui.py "$base.xml" "$base.png" "$query" "$count" "$target" > "$EVIDENCE/search-$name.json" 2> "$error"; then
            cat "$EVIDENCE/search-$name.json"
            rm -f "$error"
            return 0
        fi
        cat "$error" >&2
        if (( attempt < tries )); then
            printf 'SEARCH_SHOT_RETAKEN: %s attempt %s/%s: the dump bounds and the screenshot pixels disagreed, so the shot was retaken\n' "$name" "$attempt" "$tries" | tee -a "$EVIDENCE/search-settle.txt"
            sleep 1
        fi
    done
    rm -f "$EVIDENCE/search-$name.json"
    printf 'SEARCH_SHOT_UNSETTLED: %s failed on all %s shots: %s\n' "$name" "$tries" "$(tail -1 "$error")" >&2
    return 1
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
