#!/usr/bin/env bash
real_chat_marker() {
    jq -er --arg key "$1" '.real_chat.markers[$key]' "$EVIDENCE/seed-counts.json"
}

real_chat_scroll_back() {
    if ! python3 "$scripts/mpa" swipe --axis vertical --direction backward > "$EVIDENCE/real-chat-swipe.json" 2> "$EVIDENCE/real-chat-swipe-error.txt"; then
        python3 "$scripts/mpa" swipe --inside "$1" --axis vertical --direction backward > "$EVIDENCE/real-chat-swipe.json"
    fi
}

real_chat_cache_seed() {
    local prefs="$EVIDENCE/hosts-prefs.xml" prepared name remote=/data/local/tmp/real-chat-cache.jsonl
    local out=/home/verifyssh/real-chat-cache-2781 manifest=/home/verifyssh/chat-real.json
    if [[ "$package" != dev.multiprompt.companion.debug ]]; then
        printf 'REAL_CHAT_CACHE_SKIPPED: %s is not a debuggable package\n' "$package" > "$EVIDENCE/real-chat-cache.txt"
        return 0
    fi
    adb shell am force-stop "$package"
    if ! adb shell run-as "$package" cat shared_prefs/hosts.xml > "$prefs" 2>/dev/null || [[ ! -s "$prefs" ]]; then
        printf 'REAL_CHAT_CACHE_SKIPPED: the app has no saved host preferences yet\n' > "$EVIDENCE/real-chat-cache.txt"
        return 0
    fi
    cp "$prefs" /tmp/real-chat-hosts-2781.xml
    chmod 644 /tmp/real-chat-hosts-2781.xml
    sudo rm -rf "$out"
    if ! sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/real-chat-phone-cache.py prepare \
        --prefs /tmp/real-chat-hosts-2781.xml --fixture /home/verifyssh/real-chat-fixture.py --home /home/verifyssh \
        --out-dir "$out" --session "$real_session" > "$EVIDENCE/real-chat-cache.json"; then
        setup_failure=1
        cat "$EVIDENCE/real-chat-cache.json" >&2
        printf 'REAL_CHAT_CACHE_FAILED: the older cached position was not built\n' >&2
        return 1
    fi
    name=$(jq -er .name "$EVIDENCE/real-chat-cache.json")
    mkdir -p "$EVIDENCE/real-chat" "$EVIDENCE/real-chat-cache"
    sudo cat "$out/$name" > "$EVIDENCE/real-chat/$name"
    cp "$EVIDENCE/real-chat/$name" "$EVIDENCE/real-chat-cache/$name"
    adb push "$EVIDENCE/real-chat/$name" "$remote" > /dev/null
    adb shell run-as "$package" mkdir -p files/chat-history
    adb shell "run-as $package sh -c 'cat $remote > files/chat-history/$name'"
    adb shell rm -f "$remote"
    adb shell run-as "$package" wc -c "files/chat-history/$name" | tee "$EVIDENCE/real-chat-cache.txt"
}

real_chat_interleaved() {
    local numbers
    numbers=$(python3 - "$EVIDENCE/current.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

rows = []
for node in ET.parse(sys.argv[1]).getroot().iter('node'):
    text = node.get('text') or ''
    bounds = re.findall(r'-?\d+', node.get('bounds') or '')
    if len(bounds) == 4 and (text.startswith('Synthetic prompt ') or text.startswith('Synthetic reply ')):
        rows.append((int(bounds[1]), text))
rows.sort()
prompts = [(index, text.split()[2]) for index, (top, text) in enumerate(rows) if text.startswith('Synthetic prompt ')]
replies = [(index, text.split()[2]) for index, (top, text) in enumerate(rows) if text.startswith('Synthetic reply ')]
for index, number in prompts:
    answered = [position for position, reply in replies if position > index and reply == number]
    if not answered:
        continue
    if any(position > answered[0] for position, _ in prompts):
        print(number)
        sys.exit(0)
sys.exit(1)
PY
) || return 1
    read -r real_number <<< "$numbers"
    [[ -n "$real_number" ]]
    real_prompt="Synthetic prompt $real_number for the phone fixture."
    real_reply="Synthetic reply $real_number starts the fixture turn."
    real_next_prompt="Synthetic prompt $((real_number + 1)) for the phone fixture."
    python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$real_prompt" > /dev/null &&
        python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$real_reply" > /dev/null &&
        python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$real_next_prompt" > /dev/null
}

real_chat_screen() {
    local reply_newest user_newest stale_first neighbour attempt real_prompt real_reply real_next_prompt real_number
    reply_newest=$(real_chat_marker reply_newest)
    user_newest=$(real_chat_marker user_newest)
    stale_first=$(real_chat_marker stale_first)
    neighbour=$(real_chat_marker neighbour)
    real_chat_cache_seed
    relaunch_app Inbox
    tap_text All
    wait_for_text Inbox
    tap_text "$real_session"
    product_checks=1
    if ! wait_for_text "$reply_newest" 2; then
        shot real-chat-missing-reply
        printf 'REAL_CHAT_REPLY_MISSING: the newest assistant reply %s is not on the rendered chat screen\n' "$reply_newest" >&2
        return 1
    fi
    wait_for_text "$user_newest"
    python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select absent --value "$stale_first"
    python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select absent --value "$neighbour"
    shot real-chat-open
    python3 "$scripts/mpa" proof --name real-chat-open --xml "$EVIDENCE/$(printf '%02d' "$step")-real-chat-open.xml" \
        --require-text "$reply_newest" --require-text "$user_newest" --order "$user_newest" --order "$reply_newest"
    for attempt in {1..10}; do
        real_chat_scroll_back "$user_newest"
        sleep 1
        dump_ui
        if python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select absent --value "$user_newest" > /dev/null 2>&1 &&
            real_chat_interleaved; then
            break
        fi
    done
    python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select absent --value "$user_newest"
    real_chat_interleaved
    shot real-chat-scrolled
    python3 "$scripts/mpa" proof --name real-chat-scrolled --xml "$EVIDENCE/$(printf '%02d' "$step")-real-chat-scrolled.xml" \
        --require-text "$real_prompt" --require-text "$real_reply" --require-text "$real_next_prompt" \
        --order "$real_prompt" --order "$real_reply" --order "$real_next_prompt"
    scroll_to_newest
    wait_for_text "$reply_newest"
    wait_for_text "$user_newest"
    shot real-chat-newest-again
    python3 "$scripts/mpa" proof --name real-chat-newest-again --xml "$EVIDENCE/$(printf '%02d' "$step")-real-chat-newest-again.xml" \
        --require-text "$reply_newest" --require-text "$user_newest" --order "$user_newest" --order "$reply_newest"
    printf 'REAL_CHAT_SCREEN_OK: %s\n' "$reply_newest" | tee "$EVIDENCE/real-chat-screen.txt"
    adb shell input keyevent KEYCODE_BACK
    wait_for_text Inbox
    tap_text All
}

real_chat_journeys=" chat-clear.md chat-identity.md chat-shapes.md chat-shared.md live-chat-freshness.md session-reader.md "

real_chat_step() {
    if [[ "$real_chat_journeys" != *" $feature "* ]]; then
        return 0
    fi
    if ! jq -e '.real_chat.session' "$EVIDENCE/seed-counts.json" > /dev/null; then
        printf 'REAL_CHAT_SEED_MISSING: seed-counts.json has no real_chat block\n' >&2
        return 1
    fi
    real_session=$(jq -er '.real_chat.session' "$EVIDENCE/seed-counts.json")
    real_chat_screen
}
