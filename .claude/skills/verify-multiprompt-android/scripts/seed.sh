#!/usr/bin/env bash
set -euo pipefail
exec python3 "$(dirname "$0")/mpa" seed --session "${1:-verify-session}"
