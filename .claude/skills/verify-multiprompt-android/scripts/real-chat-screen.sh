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
    chmod 644 "$prefs"
    sudo rm -rf "$out"
    if ! sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/real-chat-phone-cache.py prepare \
        --prefs "$prefs" --fixture /home/verifyssh/real-chat-fixture.py --home /home/verifyssh \
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

real_chat_screen() {
    local reply_newest user_newest user_middle reply_middle user_after stale_first neighbour attempt
    reply_newest=$(real_chat_marker reply_newest)
    user_newest=$(real_chat_marker user_newest)
    user_middle=$(real_chat_marker user_middle)
    reply_middle=$(real_chat_marker reply_middle)
    user_after=$(real_chat_marker user_after)
    stale_first=$(real_chat_marker stale_first)
    neighbour=$(real_chat_marker neighbour)
    real_chat_cache_seed
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity" > /dev/null
    wait_for_text Inbox
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
    for attempt in {1..16}; do
        dump_ui
        if python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$reply_middle" > /dev/null 2>&1 &&
            python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$user_middle" > /dev/null 2>&1 &&
            python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select visible --value "$user_after" > /dev/null 2>&1; then
            break
        fi
        real_chat_scroll_back "$user_newest"
        sleep 1
    done
    wait_for_text "$reply_middle"
    shot real-chat-middle
    python3 "$scripts/mpa" proof --name real-chat-middle --xml "$EVIDENCE/$(printf '%02d' "$step")-real-chat-middle.xml" \
        --order "$user_middle" --order "$reply_middle" --order "$user_after"
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

real_chat_journeys=" ai-switch.md ai-usage.md chat-cancel.md chat-choices.md chat-codex.md chat-identity.md chat-queued.md chat-reactions.md chat-resync.md chat-search.md chat-shapes.md chat-shared.md chat-table.md chat-working.md claude-remote-control.md clear-ranking.md copy-chat-id.md host-restart.md message-reply.md native-skills.md reader-controls.md reader-version.md robot-faces.md session-drafts.md session-favorite-dot.md session-favorites.md session-gestures.md session-pins.md session-reader.md session-routing.md session-stop.md session-summaries.md sessions.md signed-out-sync.md skill-pills.md snippets.md terminal.md "

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
