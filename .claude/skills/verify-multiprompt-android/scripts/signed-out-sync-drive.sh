#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
"$mpa" tap text verify-session
"$mpa" read --wait-text 'Message verify-session'
"$mpa" tap desc 'Session actions'
"$mpa" tap text More
"$mpa" read --wait-text 'Sign in to sync'
"$mpa" screenshot reader-sync
"$mpa" proof --name reader-sync --require-text 'Sign in to sync' --forbid-text 'Sync: signed in'
"$mpa" key back
"$mpa" read --wait-text 'Message verify-session'
"$mpa" read --wait-absent 'Sign in to sync'
"$mpa" screenshot reader-sync-closed
"$mpa" proof --name reader-sync-closed --require-text 'Message verify-session' --forbid-text 'Sign in to sync' --note SIGNED_OUT_SYNC_MENU_OK --proof-file "$EVIDENCE/sync-menu-proof.txt"
step=$((step + 2))
