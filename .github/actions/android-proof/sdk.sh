#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${PROOF_TARGET:?}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
image="system-images;android-35;$PROOF_TARGET;x86_64"
image_dir="$ANDROID_HOME/system-images/android-35/$PROOF_TARGET/x86_64"
emulator_dir="$ANDROID_HOME/emulator"
packages_ready() {
  test -s "$image_dir/system.img" && test -s "$image_dir/package.xml" &&
    test -s "$emulator_dir/package.xml" && "$emulator_dir/emulator" -version > /dev/null 2>&1
}
if packages_ready; then
  echo ANDROID_IMAGE_READY
  exit 0
fi
(set +o pipefail; yes | sdkmanager --licenses > /dev/null)
for attempt in 1 2 3; do
  if sdkmanager --install "$image" emulator && packages_ready; then
    echo ANDROID_IMAGE_READY
    exit 0
  fi
  echo "Android package installation incomplete: attempt $attempt" >&2
  rm -rf "$emulator_dir"
done
exit 1
