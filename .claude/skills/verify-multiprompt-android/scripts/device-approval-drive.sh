#!/usr/bin/env bash
set -euo pipefail

tap_desc 'Session list actions'
tap_text Settings
tap_text 'Sign in'
wait_for_text 'Continue as fixture account'
shot device-account-browser
tap_text 'Continue as fixture account'
wait_for_text 'Fixture laptop wants to open your servers.'
wait_for_text 'Approve this device?'
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "$(cat "$EVIDENCE/pending-device-fingerprint.txt")"
shot device-approve-prompt
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/device-approve-ui.json"

printf '[127.0.0.1]:2222 %s\n' "$(cat "$FIXTURE/host-key.pub")" > "$FIXTURE/device-known-hosts"
ssh_args=(-o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$FIXTURE/device-known-hosts" -p 2222)
if ssh "${ssh_args[@]}" -i "$FIXTURE/pending-device" verifyssh@127.0.0.1 'printf SHOULD_NOT_AUTHENTICATE' > "$EVIDENCE/device-before-approval.txt" 2>&1; then
    printf 'Pending device authenticated before approval\n' >&2
    exit 1
fi
grep -F 'Permission denied (publickey)' "$EVIDENCE/device-before-approval.txt"

tap_text Approve
wait_for_text 'Stale laptop wants to open your servers.'
ssh "${ssh_args[@]}" -i "$FIXTURE/pending-device" verifyssh@127.0.0.1 'printf "APPROVED_DEVICE_SSH_OK\n"' > "$EVIDENCE/device-approved-ssh.txt"
grep -Fx APPROVED_DEVICE_SSH_OK "$EVIDENCE/device-approved-ssh.txt"
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "$(cat "$EVIDENCE/denied-device-fingerprint.txt")"
shot device-deny-prompt
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/device-deny-ui.json"
ssh "${ssh_args[@]}" -i "$FIXTURE/denied-device" verifyssh@127.0.0.1 'printf "STALE_DEVICE_KEY_PRESENT\n"' > "$EVIDENCE/device-stale-key.txt"

tap_text No
wait_for_absence 'Approve this device?'
wait_for_text Inbox
shot device-decisions-complete
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/device-decisions-ui.json"
if ssh "${ssh_args[@]}" -i "$FIXTURE/denied-device" verifyssh@127.0.0.1 'printf SHOULD_NOT_AUTHENTICATE' > "$EVIDENCE/device-after-denial.txt" 2>&1; then
    printf 'Denied device still authenticated\n' >&2
    exit 1
fi
grep -F 'Permission denied (publickey)' "$EVIDENCE/device-after-denial.txt"
sudo cat /home/verifyssh/device-approval-receipts.jsonl > "$EVIDENCE/device-approval-receipts.txt"
sudo cat /home/verifyssh/.ssh/authorized_keys > "$EVIDENCE/authorized-keys-final.txt"
python3 - "$EVIDENCE" <<'PYTHON'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
receipts = [json.loads(line) for line in (root / 'device-approval-receipts.txt').read_text().splitlines()]
assert receipts == [dict(action='browser_pkce_exchange', verified=True), dict(action='approve', device_id='fixture-approve', authorized_key_present=True), dict(action='deny', device_id='fixture-deny', authorized_key_present=False)], receipts
PYTHON
printf 'DEVICE_APPROVAL_AND_DENIAL_PROVEN_WITH_REAL_SSH\n'
