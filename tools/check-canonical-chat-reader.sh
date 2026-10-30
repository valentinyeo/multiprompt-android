#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tools/sync-chat-reader.sh --check
(cd tools/vendor/chat-reader && sha256sum --check SHA256SUMS)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/src"
cp -R tools/vendor/chat-reader/tools "$tmp/tools"
cp app/src/main/assets/chat_history_reader.py "$tmp/src/chat_history_reader.py"
cp -R app/src/main/assets/chat_connectors "$tmp/src/chat_connectors"
python3 -B "$tmp/tools/chat-history-reader-test.py" -v
python3 -B "$tmp/tools/chat-transcript-reconcile.test.py"
python3 -B "$tmp/tools/chat-shared-runtime.test.py"
