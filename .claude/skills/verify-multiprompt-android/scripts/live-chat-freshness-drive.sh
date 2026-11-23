#!/usr/bin/env bash
live_chat_user=${LIVE_CHAT_USER:-LIVE_CHAT_APPEND_2781}
live_chat_reply=${LIVE_CHAT_REPLY:-LIVE_CHAT_APPEND_REPLY_2781}
live_chat_limit=${LIVE_CHAT_LIMIT_MS:-8000}
live_chat_ms=0

live_chat_marker() {
    jq -er --arg key "$1" '.real_chat.markers[$key]' "$EVIDENCE/seed-counts.json"
}

live_chat_append() {
    local role=$1 text=$2 started finished receipt timeout_s
    receipt="$EVIDENCE/live-chat-append-$role.json"
    timeout_s=$(( live_chat_limit / 1000 + 4 ))
    started=$(date +%s%3N)
    printf 'LIVE_CHAT_APPEND_START:%s:%s\n' "$text" "$started" >> "$EVIDENCE/live-chat-host.txt"
    sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/real-chat-fixture.py append \
        --role "$role" --text "$text" > "$receipt" || return 1
    printf 'LIVE_CHAT_APPENDED:%s role=%s rows=%s bytes=%s\n' "$text" "$role" \
        "$(jq -er .entry.rows "$receipt")" "$(jq -er .entry.bytes "$receipt")" >> "$EVIDENCE/live-chat-host.txt"
    if ! python3 "$scripts/mpa" read --wait-text "$text" --timeout "$timeout_s" > "$EVIDENCE/live-chat-wait-$role.json"; then
        shot "live-chat-missed-$role"
        printf 'LIVE_CHAT_APPEND_MISSED: %s never reached the phone screen\n' "$text" >&2
        return 1
    fi
    finished=$(date +%s%3N)
    live_chat_ms=$((finished - started))
    printf 'LIVE_CHAT_VISIBLE:%s ms=%s\n' "$text" "$live_chat_ms" >> "$EVIDENCE/live-chat-host.txt"
    if (( live_chat_ms > live_chat_limit )); then
        shot "live-chat-slow-$role"
        printf 'LIVE_CHAT_TOO_SLOW: %s appeared after %s ms, over the %s ms limit\n' \
            "$text" "$live_chat_ms" "$live_chat_limit" >&2
        return 1
    fi
}

live_chat_freshness() {
    local reply_newest user_newest stale_first neighbour transcript user_ms reply_ms
    reply_newest=$(live_chat_marker reply_newest)
    user_newest=$(live_chat_marker user_newest)
    stale_first=$(live_chat_marker stale_first)
    neighbour=$(live_chat_marker neighbour)
    transcript=$(jq -er '.real_chat.transcript' "$EVIDENCE/seed-counts.json")
    : > "$EVIDENCE/live-chat-host.txt"
    tap_text "$real_session"
    wait_for_text "$reply_newest" 2
    wait_for_text "$user_newest"
    python3 "$scripts/mpa" read --select absent --value "$stale_first" > /dev/null
    python3 "$scripts/mpa" read --select absent --value "$neighbour" > /dev/null
    shot live-chat-newest
    python3 "$scripts/mpa" proof --name live-chat-newest --xml "$EVIDENCE/$(printf '%02d' "$step")-live-chat-newest.xml" \
        --require-text "$reply_newest" --require-text "$user_newest" --order "$user_newest" --order "$reply_newest"
    live_chat_append user "$live_chat_user"
    user_ms=$live_chat_ms
    live_chat_append assistant "$live_chat_reply"
    reply_ms=$live_chat_ms
    scroll_to_newest
    shot live-chat-appended
    python3 "$scripts/mpa" proof --name live-chat-appended --xml "$EVIDENCE/$(printf '%02d' "$step")-live-chat-appended.xml" \
        --require-text "$reply_newest" --require-text "$user_newest" \
        --require-text "$live_chat_user" --require-text "$live_chat_reply" \
        --forbid-text "$stale_first" --forbid-text "$neighbour" \
        --order "$live_chat_user" --order "$live_chat_reply"
    sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/real-chat-fixture.py check \
        > "$EVIDENCE/live-chat-host-check.json"
    sudo -u verifyssh tail -n 2 "$transcript" > "$EVIDENCE/live-chat-host-tail.jsonl"
    grep -q "$live_chat_user" "$EVIDENCE/live-chat-host-tail.jsonl"
    grep -q "$live_chat_reply" "$EVIDENCE/live-chat-host-tail.jsonl"
    python3 - "$EVIDENCE/live-chat-freshness.json" "$user_newest" "$reply_newest" "$stale_first" \
        "$live_chat_user" "$live_chat_reply" "$user_ms" "$reply_ms" "$live_chat_limit" <<'PY'
import json
import sys

path, user_newest, reply_newest, stale_first, user_append, reply_append, user_ms, reply_ms, limit = sys.argv[1:10]
payload = dict(
    verdict="LIVE_CHAT_NEWEST_WITHIN_SECONDS",
    newest_before=[user_newest, reply_newest],
    stale_absent=[stale_first],
    appended=[
        dict(role="user", text=user_append, visible_ms=int(user_ms)),
        dict(role="assistant", text=reply_append, visible_ms=int(reply_ms)),
    ],
    limit_ms=int(limit),
    screen="live-chat-appended",
    host_receipt="live-chat-host.txt",
)
with open(path, "w", encoding="utf-8") as stream:
    stream.write(json.dumps(payload, indent=2) + "\n")
PY
    printf 'LIVE_CHAT_NEWEST_WITHIN_SECONDS user_ms=%s reply_ms=%s limit_ms=%s\n' \
        "$user_ms" "$reply_ms" "$live_chat_limit" | tee "$EVIDENCE/live-chat-freshness.txt"
    adb shell input keyevent KEYCODE_BACK
    wait_for_text Inbox
    tap_text All
}
