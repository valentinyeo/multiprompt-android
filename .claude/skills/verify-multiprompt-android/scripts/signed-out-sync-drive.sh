#!/usr/bin/env bash
tap_text verify-session
wait_for_text 'Message verify-session'
tap_desc 'Session actions'
tap_text More
wait_for_text 'Sign in to sync'
shot reader-sync
python3 tools/signed-out-sync/menu.py "$EVIDENCE/$(printf '%02d' "$step")-reader-sync.xml" > "$EVIDENCE/reader-sync.json"
cat "$EVIDENCE/reader-sync.json"
adb shell input keyevent KEYCODE_BACK
wait_for_text 'Message verify-session'
wait_for_absence 'Sign in to sync'
shot reader-sync-closed
printf 'SIGNED_OUT_SYNC_MENU_OK\n' | tee "$EVIDENCE/sync-menu-proof.txt"
