#!/usr/bin/env bash
set -euo pipefail
: "${FIXTURE:?Set fixture directory}"
: "${EVIDENCE:?Set evidence directory}"
exec python3 "$(dirname "$0")/mpa" cleanup --fixture "$FIXTURE"
