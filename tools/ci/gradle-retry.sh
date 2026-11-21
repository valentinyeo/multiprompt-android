#!/usr/bin/env bash
set -uo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

backoff="${GRADLE_RETRY_BACKOFF_SECONDS:-15}"
attempt=1

while true; do
  "${GRADLE_BIN:-gradle}" "$@" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"

  if [[ "$status" -eq 0 ]]; then
    exit 0
  fi

  if [[ "$attempt" -ge 3 ]]; then
    echo "gradle-retry: gave up after $attempt attempts" >&2
    exit "$status"
  fi

  if ! grep -Eqi 'status code 429|too many requests|could not resolve|could not get|read timed out|connect timed out|sockettimeoutexception|connection reset|timed out waiting' "$log"; then
    exit "$status"
  fi

  echo "gradle-retry: network failure on attempt $attempt, retrying in ${backoff}s" >&2
  sleep "$backoff"
  backoff=$((backoff * 2))
  attempt=$((attempt + 1))
done
