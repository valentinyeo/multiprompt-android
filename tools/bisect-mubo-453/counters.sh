#!/usr/bin/env bash
set -uo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
sha=${1:?Usage: counters.sh SHA}
out=${EVIDENCE:-bisect-evidence}
mkdir -p "$out"
probes=(app/src/test/java/dev/multiprompt/companion/ui/ChatListRemeasureTest.kt
    app/src/test/java/dev/multiprompt/companion/ui/ChatListSnapshotTest.kt)
source="bisect-source/$sha"
if [ ! -d "$source" ]; then
    git worktree add --detach "$source" "$sha"
fi
for probe in "${probes[@]}"; do
    mkdir -p "$source/$(dirname "$probe")"
    cp "$probe" "$source/$probe"
done
python3 tools/speed-check/inbox-list-state.py "$source"
log="$out/counters-$sha.txt"
tools/ci/gradle-retry.sh -p "$source" -Dorg.gradle.jvmargs=-Xmx3g :app:testDebugUnitTest \
    --tests '*ChatListRemeasureTest*' --tests '*ChatListSnapshotTest*' > "$log" 2>&1
status=$?
readings=""
for candidate in "$source/app/build" "$source/build"; do
    for name in chat-list-remeasures.txt chat-list-snapshots.txt; do
        if [ -s "$candidate/$name" ]; then
            readings="$readings
$(cat "$candidate/$name")"
        fi
    done
done
printf '%s\n' "$readings" > "$out/readings-$sha.txt"
idle=$(printf '%s\n' "$readings" | sed -n 's/^CHAT_LIST_REMEASURES idle=\([0-9]*\).*/\1/p' | tail -1)
live=$(printf '%s\n' "$readings" | sed -n 's/^CHAT_LIST_REMEASURES .*live=\([0-9]*\).*/\1/p' | tail -1)
rows=$(printf '%s\n' "$readings" | sed -n 's/^CHAT_LIST_SNAPSHOTS .*recompositions=\([0-9]*\).*/\1/p' | tail -1)
remeasures=$(printf '%s\n' "$readings" | sed -n 's/^CHAT_LIST_SNAPSHOTS .*remeasures=\([0-9]*\).*/\1/p' | tail -1)
printf 'COUNTERS sha=%s status=%s idle=%s live=%s inbox_rows=%s inbox_remeasures=%s\n' \
    "$sha" "$status" "${idle:-missing}" "${live:-missing}" "${rows:-missing}" "${remeasures:-missing}" \
    | tee "$out/counters-$sha.line"
exit 0
