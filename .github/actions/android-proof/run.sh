#!/usr/bin/env bash
set -euo pipefail
action=$(cd "$(dirname "$0")" && pwd)
bash "$action/ready.sh" | tee "$RUNNER_TEMP/android-proof/ready-before.txt"
bash -euo pipefail "$RUNNER_TEMP/android-proof/stage.sh"
bash "$action/ready.sh" | tee "$RUNNER_TEMP/android-proof/ready-after.txt"
cmp "$RUNNER_TEMP/android-proof/ready-before.txt" "$RUNNER_TEMP/android-proof/ready-after.txt"
echo EMULATOR_STAGE_OK
