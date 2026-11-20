#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
fixture_session=live-fresh-start-3000
fresh_fixture() {
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/session-fresh-start-fixture.py "$@"
}
seed_stale_cache() {
    local prefs="$EVIDENCE/hosts-prefs.xml" remote=/data/local/tmp/fresh-start-cache.jsonl
    if [[ "$package" != dev.multiprompt.companion.debug ]]; then
        printf 'FRESH_START_CACHE_SKIPPED: %s is not debuggable\n' "$package" > "$EVIDENCE/fresh-start-cache.txt"
        return 0
    fi
    if ! adb shell run-as "$package" cat shared_prefs/hosts.xml > "$prefs" 2>/dev/null || [[ ! -s "$prefs" ]]; then
        printf 'FRESH_START_CACHE_SKIPPED: no saved host preferences yet\n' > "$EVIDENCE/fresh-start-cache.txt"
        return 0
    fi
    fresh_fixture cache > "$EVIDENCE/fresh-start-cache.json"
    name=$(jq -er .name "$EVIDENCE/fresh-start-cache.json")
    jq -r '.lines[]' "$EVIDENCE/fresh-start-cache.json" > "$EVIDENCE/fresh-start-cache-lines.jsonl"
    adb push "$EVIDENCE/fresh-start-cache-lines.jsonl" "$remote" > /dev/null
    adb shell run-as "$package" mkdir -p files/chat-history
    adb shell "run-as $package sh -c 'cat $remote > files/chat-history/$name'"
    adb shell rm -f "$remote"
    printf 'FRESH_START_CACHE_SEEDED %s\n' "$name" | tee "$EVIDENCE/fresh-start-cache.txt"
}
assert_one_inbox_row() {
  local phase=$1
  dump_ui
  python3 tools/session-fresh-start/inbox.py "$EVIDENCE/current.xml" "$fixture_session" > "$EVIDENCE/fresh-start-inbox-$phase.json"
}
assert_chat() {
  local phase=$1
  "$mpa" read --select contains --value HANDOVER_3000 --timeout 60
  "$mpa" read --select contains --value FRESH_REPLY_3000 --timeout 60
  "$mpa" screenshot "fresh-start-$phase"
  if "$mpa" read --xml "$EVIDENCE/current.xml" --select contains --value OLD_STALE_3000 > /dev/null 2>&1; then
    printf 'FRESH_START_STALE_VISIBLE_%s\n' "$phase" >&2
    return 1
  fi
  "$mpa" proof --name "fresh-start-$phase" --require-text HANDOVER_3000 --require-text FRESH_REPLY_3000 \
    --forbid-text OLD_STALE_3000 --forbid-text HOST_GHOST_STALE_3000 \
    --order HANDOVER_3000 --order FRESH_REPLY_3000 \
    --note FRESH_START_SHOWS_NEW_TRANSCRIPT_3000 --proof-file "$EVIDENCE/fresh-start-$phase-proof.txt"
}
"$mpa" read --wait-text Inbox
seed_stale_cache
fresh_fixture apply-fresh-start > "$EVIDENCE/fresh-start-applied.json"
fresh_fixture evidence > "$EVIDENCE/fresh-start-host.json"
assert_one_inbox_row inbox-after-fresh-start
"$mpa" tap text "$fixture_session"
assert_chat open
"$mpa" key back
"$mpa" read --wait-text Inbox
assert_one_inbox_row inbox-after-open
"$mpa" launch --force-stop --wait-text Inbox
"$mpa" tap text All
"$mpa" read --wait-text "$fixture_session"
assert_one_inbox_row inbox-after-relaunch
"$mpa" tap text "$fixture_session"
assert_chat after-relaunch
"$mpa" tap desc 'Session actions'
"$mpa" tap text 'Resync chat'
assert_chat after-resync
fresh_fixture evidence > "$EVIDENCE/fresh-start-final-host.json"
python3 - "$EVIDENCE" <<'PYPROOF'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
host = json.loads((root / 'fresh-start-final-host.json').read_text())
transcript = host['transcript']['visible']
assert 'OLD_STALE_3000' not in transcript, transcript
assert transcript[-2:] == ['HANDOVER_3000', 'FRESH_REPLY_3000'], transcript
assert host['tmux_sid'] == host['sid']
inbox = json.loads((root / 'fresh-start-inbox-inbox-after-fresh-start.json').read_text())
assert inbox['text'] == 'live-fresh-start-3000', inbox
(root / 'fresh-start-state.json').write_text(json.dumps(dict(inbox=inbox, transcript=transcript), indent=2) + '\n')
PYPROOF
printf 'FRESH_START_ONE_ROW_AND_NEW_TRANSCRIPT_3000\n'
exit 0