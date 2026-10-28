#!/usr/bin/env bash
set -euo pipefail
action=$(cd "$(dirname "$0")" && pwd)
bash "$action/ready.sh" | tee "$RUNNER_TEMP/android-proof/ready-before.txt"
emulator_pid=$(pgrep -f "^$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64")
test "$(wc -w <<< "$emulator_pid")" -eq 1
sudo gdb --batch -p "$emulator_pid" -ex "set pagination off" -ex "handle SIGUSR1 nostop noprint pass" -ex continue -ex 'p $_siginfo' -ex 'x/16i $pc' -ex "thread apply all bt" -ex "info registers" -ex "info proc mappings" > "$RUNNER_TEMP/android-proof/exit.txt" 2>&1 &
tracer=$!
trap 'sudo kill "$tracer" 2>/dev/null || true; wait "$tracer" 2>/dev/null || true' EXIT
bash -euo pipefail "$RUNNER_TEMP/android-proof/stage.sh"
bash "$action/ready.sh" | tee "$RUNNER_TEMP/android-proof/ready-after.txt"
cmp "$RUNNER_TEMP/android-proof/ready-before.txt" "$RUNNER_TEMP/android-proof/ready-after.txt"
echo EMULATOR_STAGE_OK
