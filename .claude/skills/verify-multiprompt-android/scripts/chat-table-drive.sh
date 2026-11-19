#!/usr/bin/env bash
table_reveal() {
    local target=$1 axis=$2 attempt coordinates
    for attempt in {1..12}; do
        dump_ui
        if python3 "$scripts/mpa" chat-table --xml "$EVIDENCE/current.xml" --visible "$target" >/dev/null 2>&1; then return; fi
        coordinates=$(python3 "$scripts/mpa" chat-table --xml "$EVIDENCE/current.xml" --scroll "$axis" | jq -r .stdout)
        adb shell input swipe $coordinates 500
    done
    printf 'Table cell never became visible: %s\n' "$target" >&2
    return 1
}

table_state() {
    local state=$1 target=$2 hidden=${3:-}
    shot "table-$state"
    python3 "$scripts/mpa" chat-table \
        --xml "$EVIDENCE/$(printf '%02d' "$step")-table-$state.xml" \
        --png "$EVIDENCE/$(printf '%02d' "$step")-table-$state.png" \
        --state "$state" --target "$target" --hidden "$hidden" > "$EVIDENCE/table-$state.json"
    cat "$EVIDENCE/table-$state.json"
}

table_wait_open() {
    local attempt
    for attempt in {1..30}; do
        if select_node desc Close >/dev/null 2>&1; then return; fi
        sleep 1
    done
    printf 'Timed out waiting for the table Close control\n' >&2
    return 1
}

tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text TABLE_READY_2544
table_reveal Plot older
table_state inline Plot 'R01-C06 harvest'
tap_desc 'Expand table'
table_wait_open
table_state open 'R01-C01 orchard' 'R01-C06 harvest'
table_reveal 'R01-C06 harvest' sideways
table_state sideways 'R01-C06 harvest' 'R24-C06 harvest'
table_reveal 'R24-C06 harvest' down
table_state down 'R24-C06 harvest' 'R01-C06 harvest'
tap_desc Close
wait_for_text 'Message verify-session'
table_state closed Plot
if sudo test -f /home/verifyssh/receipts.txt; then
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/table-host-input.txt"
    [[ ! -s "$EVIDENCE/table-host-input.txt" ]]
fi
printf 'CHAT_TABLE_JOURNEY_OK\n' | tee "$EVIDENCE/table-proof.txt"
