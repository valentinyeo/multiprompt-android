#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${PROOF_TARGET:?}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
image="system-images;android-35;$PROOF_TARGET;x86_64"
image_dir="$ANDROID_HOME/system-images/android-35/$PROOF_TARGET/x86_64"
if test -s "$image_dir/system.img" && test -s "$image_dir/package.xml"; then
  echo ANDROID_IMAGE_READY
  exit 0
fi
(set +o pipefail; yes | sdkmanager --licenses > /dev/null)
for attempt in 1 2 3; do
  if sdkmanager --install "$image" && test -s "$image_dir/system.img" && test -s "$image_dir/package.xml"; then
    echo ANDROID_IMAGE_READY
    exit 0
  fi
  echo "System image installation incomplete: attempt $attempt" >&2
done
exit 1
