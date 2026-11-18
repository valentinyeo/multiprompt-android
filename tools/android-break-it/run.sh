#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
[[ "$(adb get-serialno)" == emulator-* ]]
: "${EVIDENCE:?}" "${FIXTURE:?}" "${VERIFY_APK:?}" "${VERIFY_FEATURE:?}"
scripts="$GITHUB_WORKSPACE/.claude/skills/verify-multiprompt-android/scripts"
root=$EVIDENCE
journey=${VERIFY_JOURNEY:-$VERIFY_FEATURE}
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
record() {
    local code=$?
    trap - EXIT
    python3 - "$root" "$journey" "$code" "$((SECONDS - started))" "$VERIFY_APK" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
report = dict(journey=sys.argv[2], sourceSha=(root / 'app-source-sha.txt').read_text().strip(),
    apkSha256=hashlib.sha256(Path(sys.argv[5]).read_bytes()).hexdigest(),
    baselinePassed=True, driverExit=int(sys.argv[3]), elapsedSeconds=max(1, int(sys.argv[4])))
(root / 'break-it/result.json').write_text(json.dumps(report, indent=2) + '\n')
PY
    exit "$code"
}
trap record EXIT
trap 'exit 124' TERM INT
package=${VERIFY_PACKAGE:-dev.multiprompt.companion.debug}
adb shell am force-stop "$package"
adb shell pm clear "$package" | grep -Fx Success
adb install -r -g "$VERIFY_APK"
"$scripts/fixture.sh"
if [[ "$package" == dev.multiprompt.companion.debug ]]; then
    "$scripts/drive.sh" "$VERIFY_FEATURE"
else
    bash tools/release-choices/run.sh
fi
python3 - "$root" "$journey" "$((SECONDS - started))" <<'PY'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
(root / 'break-it/result.json').write_text(json.dumps(dict(journey=sys.argv[2],
    sourceSha=(root / 'app-source-sha.txt').read_text().strip(),
    apkSha256=(root / 'app-apk-sha256.txt').read_text().split()[0],
    baselinePassed=True, driverExit=0, elapsedSeconds=max(1, int(sys.argv[3])))))
PY
node - "$root" "$journey" <<'JS'
const fs = require('node:fs');
const proof = require('./.github/scripts/pstack-verifier/break-it.cjs');
const sha = fs.readFileSync(`${process.argv[2]}/app-source-sha.txt`, 'utf8').trim();
const result = proof.inspect(process.argv[2], process.argv[3], sha);
console.log(JSON.stringify(result, null, 2));
if (result.errors.length) process.exitCode = 1;
JS
