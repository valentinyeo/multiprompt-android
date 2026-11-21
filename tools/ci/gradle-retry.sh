#!/usr/bin/env bash
set -uo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

backoff="${GRADLE_RETRY_BACKOFF_SECONDS:-20}"
max_backoff="${GRADLE_RETRY_MAX_BACKOFF_SECONDS:-90}"
window="${GRADLE_RETRY_WINDOW_SECONDS:-300}"
attempts_allowed="${GRADLE_RETRY_ATTEMPTS:-6}"
modules="${GRADLE_MODULES_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2}"
args=("$@")
repaired=0
SECONDS=0
attempt=1

while true; do
  "${GRADLE_BIN:-gradle}" "${args[@]}" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"

  if [[ "$status" -eq 0 ]]; then
    exit 0
  fi

  if grep -Eqi 'could not find [^ ]+\.(jar|aar)|could not read|does not exist.*caches' "$log"; then
    if [[ "$repaired" -eq 0 ]]; then
      echo "gradle-retry: restored download cache is incomplete, clearing $modules and retrying with --refresh-dependencies" >&2
      rm -rf "$modules"
      args+=("--refresh-dependencies")
      repaired=1
      continue
    fi
    exit "$status"
  fi

  if ! grep -Eqi 'status code 429|too many requests|could not resolve|could not get|read timed out|connect timed out|sockettimeoutexception|connection reset|timed out waiting|status code 5[0-9][0-9]' "$log"; then
    exit "$status"
  fi

  if [[ "$attempt" -ge "$attempts_allowed" ]]; then
    echo "gradle-retry: gave up after $attempt attempts in ${SECONDS}s" >&2
    exit "$status"
  fi

  remaining=$((window - SECONDS))
  if [[ "$remaining" -le 0 ]]; then
    echo "gradle-retry: gave up after $attempt attempts, past the ${window}s retry window" >&2
    exit "$status"
  fi

  wait_seconds=$((backoff * (1 << (attempt - 1))))
  if [[ "$wait_seconds" -gt "$max_backoff" ]]; then
    wait_seconds="$max_backoff"
  fi
  jitter=$((wait_seconds / 5))
  if [[ "$jitter" -gt 0 ]]; then
    wait_seconds=$((wait_seconds + RANDOM % (jitter + 1)))
  fi
  if [[ "$wait_seconds" -gt "$remaining" ]]; then
    wait_seconds="$remaining"
  fi

  echo "gradle-retry: network failure on attempt $attempt, retrying in ${wait_seconds}s" >&2
  sleep "$wait_seconds"
  attempt=$((attempt + 1))
done
