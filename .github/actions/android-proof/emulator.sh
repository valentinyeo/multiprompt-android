#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${RUNNER_TEMP:?}"
cache="$HOME/.cache/android-proof"
archive="$cache/emulator-linux_x64-13610412.zip"
checksum=2fe2b56fe93ce75e1d478a40162131381d911c355efeaedb54dd1e0d0897a5cf
mkdir -p "$cache"
if ! printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check; then
  curl --fail --location --retry 3 --retry-all-errors --output "$archive.part" https://dl.google.com/android/repository/emulator-linux_x64-13610412.zip
  printf '%s  %s\n' "$checksum" "$archive.part" | sha256sum --check
  mv "$archive.part" "$archive"
fi
unzip -tq "$archive"
mkdir -p "$RUNNER_TEMP/android-proof-sdk"
unzip -oq "$archive" -d "$RUNNER_TEMP/android-proof-sdk"
rm -rf "$ANDROID_HOME/emulator"
mv "$RUNNER_TEMP/android-proof-sdk/emulator" "$ANDROID_HOME/emulator"
"$ANDROID_HOME/emulator/emulator" -version | tee "$RUNNER_TEMP/android-proof/emulator-version.txt"
grep -F 'Android emulator version 35.6.11' "$RUNNER_TEMP/android-proof/emulator-version.txt"
LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib64/qt/lib:${LD_LIBRARY_PATH:-}" "$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64" -version
echo PINNED_EMULATOR_READY
