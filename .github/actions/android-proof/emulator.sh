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
python3 - "$ANDROID_HOME/emulator/package.xml" "$RUNNER_TEMP/android-proof-sdk/emulator/package.xml" <<'PYTHON'
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text()
text, count = re.subn(r'<revision>.*?</revision>', '<revision><major>35</major><minor>6</minor><micro>11</micro></revision>', text, count=1, flags=re.S)
assert count == 1
Path(sys.argv[2]).write_text(text)
PYTHON
rm -rf "$ANDROID_HOME/emulator"
mv "$RUNNER_TEMP/android-proof-sdk/emulator" "$ANDROID_HOME/emulator"
"$ANDROID_HOME/emulator/emulator" -version | tee "$RUNNER_TEMP/android-proof/emulator-version.txt"
grep -F 'Android emulator version 35.6.11' "$RUNNER_TEMP/android-proof/emulator-version.txt"
LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib64/qt/lib:${LD_LIBRARY_PATH:-}" "$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64" -version
if [[ "${VERIFY_FEATURE:-}" == dictation-cancel.md ]]; then
  avd_root="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
  shopt -s nullglob
  for config in "$avd_root"/*.avd/config.ini; do
    if grep -q '^hw.audioInput=' "$config"; then
      sed -i 's/^hw.audioInput=.*/hw.audioInput=yes/' "$config"
    else
      printf '\nhw.audioInput=yes\n' >> "$config"
    fi
    if grep -q '^hw.audioOutput=' "$config"; then
      sed -i 's/^hw.audioOutput=.*/hw.audioOutput=yes/' "$config"
    else
      printf '\nhw.audioOutput=yes\n' >> "$config"
    fi
  done
fi
echo PINNED_EMULATOR_READY
