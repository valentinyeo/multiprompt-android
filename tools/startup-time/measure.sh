#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

package=dev.multiprompt.companion
activity=dev.multiprompt.companion.MainActivity
receiver=androidx.profileinstaller.ProfileInstallReceiver
apk=${STARTUP_APK:-app/build/outputs/apk/release/app-release.apk}
runs=${STARTUP_RUNS:-10}
warmup=${STARTUP_WARMUP:-2}
out=${STARTUP_OUT:-build/startup-time}
helper=tools/startup-time/report.py
segments=(without with with without)

case "$runs" in
  ''|*[!0-9]*) echo "STARTUP_RUNS must be a whole number, got: $runs" >&2; exit 2 ;;
esac
case "$warmup" in
  ''|*[!0-9]*) echo "STARTUP_WARMUP must be a whole number, got: $warmup" >&2; exit 2 ;;
esac
if [ "$runs" -lt 3 ] || [ "$runs" -gt 20 ]; then
  echo "STARTUP_RUNS must be between 3 and 20, got: $runs" >&2
  exit 2
fi
test -s "$apk" || { echo "APK not found: $apk" >&2; exit 2; }

rm -rf "$out"
mkdir -p "$out"
samples="$out/samples.csv"
info="$out/info.txt"
: > "$samples"

dexopt_status() {
  adb shell dumpsys package "$package" > "$out/dumpsys-$1.txt"
  python3 -B "$helper" dexopt-status "$out/dumpsys-$1.txt"
}

expect_success() {
  if ! grep -q '^Success' "$1"; then
    cat "$1" >&2
    echo "Expected Success in $1" >&2
    return 1
  fi
}

fresh_install() {
  adb uninstall "$package" > /dev/null 2>&1 || true
  adb install -g "$apk" > "$out/install-$1.txt" 2>&1 || true
  expect_success "$out/install-$1.txt"
}

compile_package() {
  adb shell cmd package compile "$@" "$package" > "$out/compile-$label.txt" 2>&1 || true
  expect_success "$out/compile-$label.txt"
}

prepare_without() {
  compile_package --reset
  compile_package -f -m verify
}

prepare_with() {
  adb shell am broadcast --include-stopped-packages -a androidx.profileinstaller.action.INSTALL_PROFILE "$package/$receiver" > "$out/profile-install-$label.txt" 2>&1 || true
  profile_result=$(python3 -B "$helper" broadcast-result "$out/profile-install-$label.txt")
  if [ "$profile_result" != 1 ]; then
    cat "$out/profile-install-$label.txt" >&2
    echo "Profile install result $profile_result, expected 1" >&2
    return 1
  fi
  compile_package -f -m speed-profile
}

measure_segment() {
  local mode=$1 index=$2 label=$3 expected status total start
  case "$mode" in
    without) expected=verify ;;
    with) expected=speed-profile ;;
  esac
  fresh_install "$label"
  if [ "$mode" = with ]; then prepare_with; else prepare_without; fi
  status=$(dexopt_status "$label-before")
  if [ "$status" != "$expected" ]; then
    echo "Segment $label: dexopt status is $status, expected $expected" >&2
    return 1
  fi
  for ((start = 1; start <= warmup + runs; start++)); do
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb shell am start-activity -W -S -n "$package/$activity" > "$out/start-$label-$start.txt" 2>&1
    total=$(python3 -B "$helper" total-time "$out/start-$label-$start.txt")
    echo "segment=$label mode=$mode start=$start total_ms=$total"
    if [ "$start" -gt "$warmup" ]; then
      printf '%s,%s,%s,%s\n' "$mode" "$index" "$((start - warmup))" "$total" >> "$samples"
    fi
  done
  status=$(dexopt_status "$label-after")
  if [ "$status" != "$expected" ]; then
    echo "Segment $label: dexopt status changed to $status during the run, expected $expected" >&2
    return 1
  fi
  printf '%s_status=%s\n' "$mode" "$status" >> "$info.parts"
  if [ "$mode" = with ]; then printf 'with_install_result=%s\n' "$profile_result" >> "$info.parts"; fi
}

adb get-state > /dev/null
node tools/baseline-profile.mjs apk "$apk"

rm -f "$info.parts"
profile_result=
for position in 0 1 2 3; do
  label="s$((position + 1))-${segments[position]}"
  measure_segment "${segments[position]}" "$((position + 1))" "$label"
done

prop() { adb shell getprop "$1" | tr -d '\r'; }
{
  printf 'device=%s\n' "$(prop ro.product.model) ($(prop ro.product.name), emulator: $(prop ro.kernel.qemu))"
  printf 'android=%s\n' "$(prop ro.build.version.release)"
  printf 'api=%s\n' "$(prop ro.build.version.sdk)"
  printf 'abi=%s\n' "$(prop ro.product.cpu.abi)"
  printf 'variant=release (R8 minified, not debuggable, signed with a throwaway key)\n'
  printf 'apk=%s, %s bytes, sha256 %s\n' "$apk" "$(stat -c%s "$apk")" "$(sha256sum "$apk" | cut -d' ' -f1)"
  printf 'warmup=%s\n' "$warmup"
  printf 'order=%s\n' "${segments[*]}"
  sort -u "$info.parts"
} > "$info"
rm -f "$info.parts"

python3 -B "$helper" report "$samples" "$info" "$out" "$((runs * 2))" | tee "$out/report-stdout.txt"
echo STARTUP_TIME_REPORT_OK
