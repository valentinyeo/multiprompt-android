#!/usr/bin/env bash
version_screen() {
    local name=$1
    shift
    shot "$name"
    python3 tools/version-labels/screen.py \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.xml" \
        "$EXPECTED_VERSION" "$@" > "$EVIDENCE/$name.json"
    cat "$EVIDENCE/$name.json"
}

tap_desc 'Session list actions'
wait_for_text "$EXPECTED_VERSION"
version_screen inbox-version
tap_desc "Version $EXPECTED_VERSION, open release notes"
wait_for_text 'App updates'
wait_for_text "Installed: $EXPECTED_VERSION"
version_screen update-screen 'App updates'
adb shell input keyevent KEYCODE_BACK
wait_for_text Inbox
tap_desc 'Session list actions'
tap_text Settings
wait_for_text 'Session list settings'
version_screen settings-version
adb shell input keyevent KEYCODE_BACK
wait_for_text Inbox
tap_text verify-session
wait_for_text 'Message verify-session'
version_screen reader-version
tap_desc 'Session actions'
tap_text 'Open live terminal'
wait_for_text 'SSH · tmux attached'
version_screen shell-version
printf 'VERSION_LABELS_JOURNEY_OK\n' | tee "$EVIDENCE/version-proof.txt"
