#!/usr/bin/env bash
set -euo pipefail
adb reconnect offline >&2
timeout 120s adb -s emulator-5554 wait-for-device
test "$(timeout 20s adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1
timeout 20s adb -s emulator-5554 shell 'test "$(getprop init.svc.bootanim)" = stopped'
printf 'EMULATOR_READY %s\n' "$(adb -s emulator-5554 shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')"
