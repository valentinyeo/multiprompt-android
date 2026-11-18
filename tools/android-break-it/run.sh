#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
[[ "$(adb get-serialno)" == emulator-* ]]
: "${EVIDENCE:?}" "${FIXTURE:?}" "${VERIFY_APK:?}" "${VERIFY_FEATURE:?}"
scripts="$GITHUB_WORKSPACE/.claude/skills/verify-multiprompt-android/scripts"
root=$EVIDENCE
started=$SECONDS
"$scripts/cleanup.sh"
uid=$(id -u verifyssh)
[[ "$uid" -ne 0 && "$uid" -ne "$(id -u)" && "$(getent passwd verifyssh | cut -d: -f6)" == /home/verifyssh ]]
[[ "$(stat -c %u /home/verifyssh)" == "$uid" ]]
sudo pkill -KILL -u "$uid" || [[ "$?" == 1 ]]
sudo userdel --force --remove verifyssh
rm -f "$FIXTURE/tmux.pid" "$FIXTURE/sshd.pid"
sudo rm -f "$FIXTURE/sshd.log"
export EVIDENCE="$root/break-it" BREAK_IT_MODE=true
mkdir -p "$EVIDENCE"
phase=setup
write_result() {
    python3 - "$root" "$VERIFY_FEATURE" "$1" "$((SECONDS - started))" "$VERIFY_APK" "$phase" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
code = int(sys.argv[3])
phase = sys.argv[6]
report = dict(journey=sys.argv[2], sourceSha=(root / 'app-source-sha.txt').read_text().strip(),
    apkSha256=hashlib.sha256(Path(sys.argv[5]).read_bytes()).hexdigest(),
    baselinePassed=True, driverExit=0 if phase == 'inspector' else code,
    inspectorExit=code if phase == 'inspector' else None,
    elapsedSeconds=max(1, int(sys.argv[4])))
(root / 'break-it/result.json').write_text(json.dumps(report, indent=2) + '\n')
PY
}
record() {
    local code=$?
    trap - EXIT
    write_result "$code"
    exit "$code"
}
trap record EXIT
trap 'exit 124' TERM INT
adb shell am force-stop dev.multiprompt.companion.debug
adb shell pm clear dev.multiprompt.companion.debug | grep -Fx Success
adb install -r -g "$VERIFY_APK"
"$scripts/fixture.sh"
phase=driver
"$scripts/drive.sh" "$VERIFY_FEATURE"
phase=inspector
write_result 0
node - "$root" "$VERIFY_FEATURE" <<'JS'
const fs = require('node:fs');
const proof = require('./.github/scripts/pstack-verifier/break-it.cjs');
const sha = fs.readFileSync(`${process.argv[2]}/app-source-sha.txt`, 'utf8').trim();
const result = proof.inspect(process.argv[2], process.argv[3], sha);
fs.writeFileSync(`${process.argv[2]}/break-it/inspector.json`, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
if (result.errors.length) process.exitCode = 1;
JS
