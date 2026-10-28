#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${PROOF_TARGET:?}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
(set +o pipefail; yes | sdkmanager --licenses > /dev/null)
image="system-images;android-35;$PROOF_TARGET;x86_64"
for attempt in 1 2 3; do
  if sdkmanager --install "$image" && test -s "$ANDROID_HOME/system-images/android-35/$PROOF_TARGET/x86_64/system.img" && test -s "$ANDROID_HOME/system-images/android-35/$PROOF_TARGET/x86_64/package.xml"; then
    echo ANDROID_IMAGE_READY
    exit 0
  fi
  echo "System image installation incomplete: attempt $attempt" >&2
done
exit 1
