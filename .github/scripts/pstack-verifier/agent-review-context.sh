#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$EVIDENCE/agent-review"
git log -1 --format=%s > "$EVIDENCE/agent-review/title.txt"
git log -1 --format=%b > "$EVIDENCE/agent-review/body.txt"
git fetch --no-tags --depth=200 origin main
if git diff --quiet origin/main...HEAD 2>/dev/null; then
  printf '(empty)\n' > "$EVIDENCE/agent-review/diff.txt"
else
  git diff origin/main...HEAD > "$EVIDENCE/agent-review/diff.txt" || printf '(empty)\n' > "$EVIDENCE/agent-review/diff.txt"
fi