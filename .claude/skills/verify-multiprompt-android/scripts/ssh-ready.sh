#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?}"
timeout 5s adb shell 'echo | toybox nc -w 2 10.0.2.2 2222' > "$EVIDENCE/ssh-reachability.txt" 2>&1 || true
grep -E '^SSH-2\.0-' "$EVIDENCE/ssh-reachability.txt"
