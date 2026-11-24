#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

package=dev.multiprompt.companion.debug
activity=dev.multiprompt.companion.MainActivity
base_apk=${SPEED_BASE_APK:?Set SPEED_BASE_APK to the base revision debug APK}
head_apk=${SPEED_HEAD_APK:?Set SPEED_HEAD_APK to the head revision debug APK}
test_apk=${SPEED_TEST_APK:?Set SPEED_TEST_APK to the debug androidTest APK that seeds the fixture host}
chat=${SPEED_LONG_CHAT:-cl-long-chat}
rounds=${SPEED_ROUNDS:-3}
chat_window=${SPEED_CHAT_WINDOW:-4}
chat_steady=${SPEED_CHAT_STEADY:-8}
swipes=${SPEED_SWIPES:-6}
out=${SPEED_OUT:-build/speed-check}
helper=tools/speed-check/report.py
mpa=${SPEED_MPA:-.claude/skills/verify-multiprompt-android/scripts/mpa}
adb=${ADB:-adb}

case "$rounds" in
  ''|*[!0-9]*) echo "SPEED_ROUNDS must be a whole number, got: $rounds" >&2; exit 2 ;;
esac
if [ "$rounds" -lt 3 ] || [ "$rounds" -gt 5 ]; then
  echo "SPEED_ROUNDS must be between 3 and 5, got: $rounds" >&2
  exit 2
fi
for apk in "$base_apk" "$head_apk" "$test_apk"; do
  test -s "$apk" || { echo "APK not found: $apk" >&2; exit 2; }
done
if cmp -s "$base_apk" "$head_apk"; then
  echo "SPEED_BASE_APK and SPEED_HEAD_APK are the same file, so there is nothing to compare" >&2
  exit 2
fi

rm -rf "$out"
mkdir -p "$out"
samples="$out/samples.csv"
: > "$samples"

expect_success() {
  if ! grep -q '^Success' "$1"; then
    cat "$1" >&2
    echo "Expected Success in $1" >&2
    return 1
  fi
}

install_apk() {
  "$adb" install -r -g "$2" > "$out/install-$1.txt" 2>&1 || true
  expect_success "$out/install-$1.txt"
}

frames() {
  "$adb" shell dumpsys gfxinfo "$package" > "$out/frames-$1.txt"
}

field() {
  python3 -B "$helper" frames "$out/frames-$1.txt" "$2"
}

sample() {
  printf '%s,%s,%s,%s\n' "$1" "$2" "$3" "$4" >> "$samples"
}

setup_device() {
  "$adb" uninstall "$package" > /dev/null 2>&1 || true
  "$adb" uninstall "$package.test" > /dev/null 2>&1 || true
  "$adb" install -g "$base_apk" > "$out/install-base-first.txt" 2>&1 || true
  expect_success "$out/install-base-first.txt"
  "$adb" install -r -g "$test_apk" > "$out/install-test.txt" 2>&1 || true
  expect_success "$out/install-test.txt"
  local key
  key=$(base64 -w0 "$FIXTURE/user-key")
  "$adb" shell am instrument -w -e class dev.multiprompt.companion.account.SeedHostDeviceTest#importsCliHostFixture \
    -e host_label Verify -e ssh_host 10.0.2.2 -e ssh_port 2222 -e ssh_user verifyssh \
    -e ssh_private_key "$key" "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$out/host-seed.txt" 2>&1 || true
  grep -F 'OK (1 test)' "$out/host-seed.txt"
  "$adb" shell am start -n "$package/$activity" > /dev/null
  "$mpa" read --select text --value 'Use without account (debug)' --timeout 60 --out "$out/sign-in.json" > /dev/null
  "$mpa" tap text 'Use without account (debug)' > /dev/null
  "$mpa" read --select text --value 'Trust and connect' --timeout 60 --out "$out/trust.json" > /dev/null
  "$mpa" tap text 'Trust and connect' > /dev/null
  "$mpa" read --wait-text Inbox --timeout 60 --out "$out/inbox-seeded.json" > /dev/null
  "$mpa" tap text All > /dev/null
  "$mpa" read --wait-text "$chat" --timeout 60 > /dev/null
  "$mpa" doctor --expected-version "$EXPECTED_VERSION" --evidence "$out" > /dev/null
}

measure_round() {
  local side=$1 apk=$2 round=$3 record=$4
  install_apk "$side-$round" "$apk"
  "$adb" shell am force-stop "$package" > /dev/null 2>&1 || true
  "$adb" shell dumpsys gfxinfo "$package" reset > /dev/null 2>&1 || true
  "$adb" shell am start-activity -W -S -n "$package/$activity" > "$out/start-$side-$round.txt" 2>&1
  python3 -B "$helper" cold-start "$out/start-$side-$round.txt" > "$out/cold-open-$side-$round.txt"
  "$mpa" read --select text --value 'Use without account (debug)' --timeout 60 --out "$out/sign-in-$side-$round.json" > /dev/null
  "$mpa" tap text 'Use without account (debug)' > /dev/null
  "$mpa" read --wait-text Inbox --timeout 60 --out "$out/open-$side-$round.json" > /dev/null
  frames "open-$side-$round"
  field "open-$side-$round" p90-ms > "$out/open-p90-$side-$round.txt"
  "$adb" shell dumpsys gfxinfo "$package" reset > /dev/null 2>&1 || true
  local swipe
  for ((swipe = 1; swipe <= swipes; swipe++)); do
    "$mpa" swipe --axis vertical --direction forward > /dev/null
  done
  frames "scroll-$side-$round"
  field "scroll-$side-$round" total-frames > "$out/scroll-frames-$side-$round.txt"
  field "scroll-$side-$round" p90-ms > "$out/scroll-p90-$side-$round.txt"
  "$adb" shell dumpsys gfxinfo "$package" reset > /dev/null 2>&1 || true
  "$mpa" tap text "$chat" > /dev/null
  sleep "$chat_window"
  frames "chat-open-$side-$round"
  field "chat-open-$side-$round" p90-ms > "$out/chat-open-p90-$side-$round.txt"
  "$adb" shell dumpsys gfxinfo "$package" reset > /dev/null 2>&1 || true
  sleep "$chat_steady"
  frames "chat-steady-$side-$round"
  field "chat-steady-$side-$round" p90-ms > "$out/chat-steady-p90-$side-$round.txt"
  field "chat-steady-$side-$round" janky-percent > "$out/chat-steady-janky-$side-$round.txt"
  printf '%s\n%s\n' "$(cat "$out/chat-open-p90-$side-$round.txt")" "$(cat "$out/chat-steady-p90-$side-$round.txt")" | sort -n | tail -1 > "$out/chat-p90-$side-$round.txt"
  "$mpa" read --wait-text "$chat" --timeout 60 --out "$out/chat-open-$side-$round.json" > /dev/null
  "$mpa" key back > /dev/null
  "$mpa" read --wait-text Inbox --timeout 60 > /dev/null
  if [ "$record" = record ]; then
    sample "$side" "$round" inbox_open_ms "$(cat "$out/cold-open-$side-$round.txt")"
    sample "$side" "$round" inbox_open_p90_ms "$(cat "$out/open-p90-$side-$round.txt")"
    sample "$side" "$round" inbox_scroll_frames "$(cat "$out/scroll-frames-$side-$round.txt")"
    sample "$side" "$round" inbox_scroll_p90_ms "$(cat "$out/scroll-p90-$side-$round.txt")"
    sample "$side" "$round" chat_open_p90_ms "$(cat "$out/chat-p90-$side-$round.txt")"
  fi
}

prop() { "$adb" shell getprop "$1" | tr -d '\r'; }

"$adb" get-state > /dev/null
login_attempts=0
until setup_device; do
  login_attempts=$((login_attempts + 1))
  if [ "$login_attempts" -ge 3 ]; then
    echo "The phone never reached the seeded inbox and trusted the fixture host" >&2
    exit 2
  fi
  echo "Retrying device setup, attempt $((login_attempts + 1)) of 3" >&2
  sleep 10
done
measure_round base "$base_apk" warmup skip
measure_round head "$head_apk" warmup skip
for ((round = 1; round <= rounds; round++)); do
  measure_round base "$base_apk" "$round" record
  measure_round head "$head_apk" "$round" record
done

{
  printf 'device=%s\n' "$(prop ro.product.model) ($(prop ro.product.name), emulator: $(prop ro.kernel.qemu))"
  printf 'android=%s\n' "$(prop ro.build.version.release)"
  printf 'api=%s\n' "$(prop ro.build.version.sdk)"
  printf 'abi=%s\n' "$(prop ro.product.cpu.abi)"
  printf 'variant=debug (%s)\n' "$package"
  printf 'base_apk=%s, %s bytes, sha256 %s\n' "$base_apk" "$(stat -c%s "$base_apk")" "$(sha256sum "$base_apk" | cut -d' ' -f1)"
  printf 'head_apk=%s, %s bytes, sha256 %s\n' "$head_apk" "$(stat -c%s "$head_apk")" "$(sha256sum "$head_apk" | cut -d' ' -f1)"
  printf 'base_revision=%s\n' "${SPEED_BASE_REVISION:-unknown}"
  printf 'head_revision=%s\n' "${SPEED_HEAD_REVISION:-unknown}"
  printf 'rounds=%s\n' "$rounds"
  printf 'long_chat=%s\n' "$chat"
  printf 'chat_open_window_seconds=%s\n' "$chat_window"
  printf 'chat_steady_window_seconds=%s\n' "$chat_steady"
  printf 'scroll_swipes=%s\n' "$swipes"
  printf 'order=base,head alternating per round, one unrecorded warmup round per side\n'
} > "$out/info.txt"

status=0
python3 -B "$helper" report "$samples" "$out/report.json" "$out/report.md" "$rounds" | tee "$out/report-stdout.txt" || status=$?
printf 'speed-check exit code %s\n' "$status" | tee -a "$out/report-stdout.txt"
exit "$status"
