#!/usr/bin/env bash
table_reveal() {
    local target=$1 axis=$2 attempt coordinates
    for attempt in {1..12}; do
        dump_ui
        if python3 tools/chat-table/ui.py visible "$EVIDENCE/current.xml" "$target"; then return; fi
        coordinates=$(python3 tools/chat-table/ui.py swipe "$EVIDENCE/current.xml" "$axis")
        adb shell input swipe $coordinates 500
    done
    printf 'Table cell never became visible: %s\n' "$target" >&2
    return 1
}

table_state() {
    local state=$1 target=$2 hidden=${3:-}
    shot "table-$state"
    python3 tools/chat-table/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-table-$state.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-table-$state.png" \
        "$state" "$target" "$hidden" > "$EVIDENCE/table-$state.json"
    cat "$EVIDENCE/table-$state.json"
}

tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text TABLE_READY_2544
table_reveal Plot older
table_state inline Plot 'R01-C06 harvest'
tap_desc 'Expand table'
wait_for_text 'Close'
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
