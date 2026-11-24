#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
layout_tool=tools/account-layout/layout.py
account_layout_prefs() {
    local name=$1 expected=$2 attempt
    for attempt in {1..5}; do
        adb exec-out run-as "$package" cat shared_prefs/account_layout.xml > "$EVIDENCE/$name-preferences.xml"
        if python3 -B "$layout_tool" prefs "$EVIDENCE/$name-preferences.xml" "$expected" > "$EVIDENCE/$name-layout.json" 2>/dev/null; then
            cat "$EVIDENCE/$name-layout.json"
            return 0
        fi
        sleep 1
    done
    python3 -B "$layout_tool" prefs "$EVIDENCE/$name-preferences.xml" "$expected" > "$EVIDENCE/$name-layout.json"
}
account_layout_receipt() {
    local name=$1 expected=$2 attempt
    for attempt in {1..30}; do
        sudo cat /home/verifyssh/device-approval-receipts.jsonl > "$EVIDENCE/$name-receipts.txt"
        if python3 -B "$layout_tool" receipt "$EVIDENCE/$name-receipts.txt" "$expected" > "$EVIDENCE/$name-host.json" 2>/dev/null; then
            cat "$EVIDENCE/$name-host.json"
            return 0
        fi
        sleep 1
    done
    python3 -B "$layout_tool" receipt "$EVIDENCE/$name-receipts.txt" "$expected" > "$EVIDENCE/$name-host.json"
}
studio_layout='{"workspaces":["Studio"],"tabs":{"verify-session":{"workspace":"Studio","group":"Original"},"pin-00":{"workspace":"Studio","group":"Other"}},"group_order":null}'
created_layout='{"workspaces":["Studio","Review"],"tabs":{"verify-session":{"workspace":"Studio","group":"Original"},"pin-00":{"workspace":"Studio","group":"Other"}},"group_order":null}'
renamed_layout='{"workspaces":["Studio","Review"],"tabs":{"verify-session":{"workspace":"Studio","group":"Renamed"},"pin-00":{"workspace":"Studio","group":"Other"}},"group_order":null}'
ungrouped_layout='{"workspaces":["Studio","Review"],"tabs":{"verify-session":{"workspace":"Studio","group":""},"pin-00":{"workspace":"Studio","group":"Other"}},"group_order":null}'
"$mpa" tap desc 'Session list actions'
"$mpa" tap text Settings
"$mpa" tap text 'Sign in'
"$mpa" read --wait-text 'Continue as fixture account'
"$mpa" screenshot account-browser
"$mpa" tap text 'Continue as fixture account'
"$mpa" read --wait-text 'Switch / renew login'
"$mpa" screenshot account-signed-in
"$mpa" proof --name account-signed-in --require-text 'Session list settings' --require-text 'Switch / renew login'
"$mpa" tap text 'Host mapping'
"$mpa" read --wait-text 'Desktop tmux hosts'
"$mpa" tap text '1 · Contabo UK: Not mapped'
"$mpa" tap text 'Verify · verifyssh@10.0.2.2'
"$mpa" read --wait-text '1 · Contabo UK: Verify (verifyssh)'
"$mpa" screenshot account-host-mapped
"$mpa" proof --name account-host-mapped --require-text '1 · Contabo UK: Verify (verifyssh)'
"$mpa" tap text Done
"$mpa" read --wait-absent 'Desktop tmux hosts'
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text Studio --timeout 90
"$mpa" tap text Studio
"$mpa" read --wait-text ORIGINAL
"$mpa" screenshot group-headings
"$mpa" proof --name group-headings --require-text ORIGINAL --require-text OTHER --require-text RECENTS --order ORIGINAL --order OTHER --forbid-text 'Rename group'
account_layout_prefs group-headings "$studio_layout"
"$mpa" tap desc 'Session list actions'
"$mpa" tap text 'New workspace'
"$mpa" read --wait-text Create
"$mpa" screenshot new-workspace-open
"$mpa" proof --name new-workspace-open --require-text 'New workspace' --require-text Cancel --require-text Create
"$mpa" fill Name Discarded
"$mpa" read --select editor-value --value workspace --extra Discarded
"$mpa" screenshot new-workspace-draft
"$mpa" tap text Cancel
"$mpa" read --wait-absent Create
"$mpa" read --wait-absent 'New workspace'
"$mpa" screenshot new-workspace-cancelled
"$mpa" proof --name new-workspace-cancelled --forbid-text 'New workspace'
account_layout_prefs new-workspace-cancelled "$studio_layout"
"$mpa" tap desc 'Session list actions'
"$mpa" tap text 'New workspace'
"$mpa" read --wait-text Create
"$mpa" key back
"$mpa" read --wait-absent Create
"$mpa" read --wait-absent 'New workspace'
"$mpa" screenshot new-workspace-dismissed
"$mpa" proof --name new-workspace-dismissed --forbid-text 'New workspace'
account_layout_prefs new-workspace-dismissed "$studio_layout"
"$mpa" tap desc 'Session list actions'
"$mpa" tap text 'New workspace'
"$mpa" fill Name Review
"$mpa" read --select editor-value --value workspace --extra Review
"$mpa" tap text Create
"$mpa" read --wait-absent Create
"$mpa" read --wait-absent 'New workspace'
"$mpa" tap text Review
"$mpa" read --wait-text 'This split is clear'
"$mpa" screenshot workspace-created
"$mpa" proof --name workspace-created --require-text Review --require-text 'This split is clear' --forbid-text 'New workspace'
account_layout_prefs workspace-created "$created_layout"
account_layout_receipt workspace-created "$created_layout"
"$mpa" tap text Studio
"$mpa" read --wait-text ORIGINAL
"$mpa" hold text ORIGINAL
"$mpa" read --wait-text 'Rename group'
"$mpa" screenshot rename-group-open
"$mpa" proof --name rename-group-open --require-text 'Rename group' --require-text Ungroup --require-text Cancel
"$mpa" read --select editor-value --value group --extra Original
"$mpa" key back
"$mpa" read --wait-absent 'Rename group'
"$mpa" screenshot rename-group-dismissed
"$mpa" proof --name rename-group-dismissed --require-text ORIGINAL --forbid-text 'Rename group'
account_layout_prefs rename-group-dismissed "$created_layout"
"$mpa" hold text ORIGINAL
"$mpa" read --wait-text 'Rename group'
"$mpa" fill Name Renamed
"$mpa" read --select editor-value --value group --extra Renamed
"$mpa" tap text Rename
"$mpa" read --wait-absent 'Rename group'
"$mpa" read --wait-text RENAMED
"$mpa" screenshot group-renamed
"$mpa" proof --name group-renamed --require-text RENAMED --forbid-text ORIGINAL --forbid-text 'Rename group'
account_layout_prefs group-renamed "$renamed_layout"
account_layout_receipt group-renamed "$renamed_layout"
"$mpa" hold text RENAMED
"$mpa" read --wait-text 'Rename group'
"$mpa" tap text Ungroup
"$mpa" read --wait-absent 'Rename group'
"$mpa" read --wait-text RECENTS
"$mpa" screenshot group-ungrouped
"$mpa" proof --name group-ungrouped --require-text RECENTS --require-text OTHER --forbid-text RENAMED --forbid-text ORIGINAL --forbid-text 'Rename group'
account_layout_prefs group-ungrouped "$ungrouped_layout"
account_layout_receipt group-ungrouped "$ungrouped_layout"
sudo cat /home/verifyssh/device-approval-receipts.jsonl > "$EVIDENCE/device-approval-receipts.txt"
printf 'WORKSPACE_GROUP_CONTROLS_OK\n' | tee "$EVIDENCE/workspace-group-proof.txt"
