#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
tool=tools/long-drafts
work="$EVIDENCE/long-drafts"
state="$work/state.json"
rm -rf "$work"
mkdir -p "$work"
python3 -B "$tool/drafts.py" --out "$work" --lengths 100 1000 5000 --skill /review-native

pair() {
    "$mpa" screenshot "$1" | python3 -B -c 'import json, sys; shot = json.load(sys.stdin); print(shot["xml"], shot["png"])'
}
capture() {
    local length=$1 phase=$2 expected=$3 xml png
    shift 3
    read -r xml png <<< "$(pair "long-drafts-$length-$phase")"
    python3 -B "$tool/ui.py" capture --xml "$xml" --png "$png" --length "$length" --phase "$phase" \
        --expected "$expected" --state "$state" "$@"
}
fill_draft() {
    local length=$1 draft=$2 attempt
    for attempt in 1 2; do
        tap_node editor ''
        adb shell input keycombination 113 29
        adb shell input keyevent KEYCODE_DEL
        "$mpa" read --select editor-value --value composer --extra ''
        if python3 -B "$tool/ui.py" fill --expected "$draft" --dump "$work/pending.xml" --tail 4; then
            return 0
        fi
        printf 'LONG_DRAFTS_FILL_ATTEMPT_FAILED length=%s attempt=%s\n' "$length" "$attempt"
    done
    printf 'LONG_DRAFTS_FILL_FAILED length=%s\n' "$length" >&2
    return 1
}

middle_edit() {
    local length=$1 draft=$2 pill=$3 round offset target low high step
    marker=MIDEDIT
    target=$((length / 2))
    low=$((length / 10))
    high=$((length * 9 / 10))
    press KEYCODE_DPAD_UP $((length / 52))
    for round in 1 2 3; do
        adb shell input text "$marker"
        capture "$length" middle "$draft" --pill any --insert "$marker"
        offset=$(python3 -B -c 'import json, sys; print(json.load(open(sys.argv[1]))["insert_offset"])' "$work/long-drafts-$length-middle.json")
        if ((offset >= low && offset <= high)); then return 0; fi
        printf 'LONG_DRAFTS_MIDDLE_ADJUST length=%s round=%s offset=%s\n' "$length" "$round" "$offset"
        press KEYCODE_DEL "${#marker}"
        if ((offset > target)); then
            step=$(((offset - target) / 15 + 1))
            press KEYCODE_DPAD_UP "$step"
        else
            step=$(((target - offset) / 15 + 1))
            press KEYCODE_DPAD_DOWN "$step"
        fi
    done
    printf 'LONG_DRAFTS_MIDDLE_FAILED length=%s offset=%s\n' "$length" "$offset" >&2
    return 1
}

press() {
    local key=$1 count=$2 batch=20 args
    while ((count > 0)); do
        args=()
        local take=$((count < batch ? count : batch))
        for ((index = 0; index < take; index++)); do args+=("$key"); done
        adb shell input keyevent "${args[@]}"
        count=$((count - take))
        sleep 0.3
    done
}

"$mpa" tap text verify-session
"$mpa" read --wait-text 'Native session skills are ready.'
"$mpa" read --select desc --value 'Session actions' --timeout 20
"$mpa" screenshot long-drafts-open

for length in 100 1000 5000; do
    draft="$work/draft-$length.txt"
    pill=no
    if ((length <= 100)); then pill=yes; fi
    fill_draft "$length" "$draft"
    capture "$length" end "$draft" --pill "$pill"
    travel=$((length / 10 + 40))
    press KEYCODE_DPAD_UP "$travel"
    capture "$length" top "$draft" --pill "$pill"
    press KEYCODE_DPAD_UP 1
    capture "$length" top-again "$draft" --pill "$pill"
    press KEYCODE_DPAD_DOWN "$travel"
    capture "$length" bottom "$draft" --pill "$pill"
    press KEYCODE_DPAD_DOWN 1
    capture "$length" bottom-again "$draft" --pill "$pill"
    middle_edit "$length" "$draft" "$pill"
done

sleep 2
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" tap text verify-session
"$mpa" read --wait-text 'Native session skills are ready.'
capture 5000 reopened "$work/draft-5000-edited.txt" --pill no

python3 -B "$tool/ui.py" verify --state "$state"
cp "$work/long-drafts-result.json" "$EVIDENCE/long-drafts-result.json"
printf 'LONG_DRAFTS_JOURNEY_OK\n' | tee "$EVIDENCE/long-drafts-proof.txt"
