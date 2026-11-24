#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
fix=${1:?Fix commit}
method=${2:?Replay method}
root=$(pwd)
gradle=("${GRADLE:-$root/tools/ci/gradle-retry.sh}")
output="$root/app/build/reports/message-projection/$fix"
mkdir -p "$output"
for side in before after; do
    ref=$fix
    if [[ "$side" == before ]]; then ref="$fix^"; fi
    source=$(git rev-parse "$ref")
    directory="$RUNNER_TEMP/projection-$fix-$side"
    mkdir -p "$directory"
    git archive "$source" | tar -x -C "$directory"
    tests=app/src/test/java/dev/multiprompt/companion/reader
    if [[ "$fix" == ce2d32d9 ]]; then
        git show "$fix:$tests/QueuedMessageKeysTest.kt" > "$directory/$tests/QueuedMessageKeysTest.kt"
        target='*QueuedMessageKeysTest.distinctWaitingMessagesKeepDistinctKeysWhenTextHashesCollide'
        name=distinctWaitingMessagesKeepDistinctKeysWhenTextHashesCollide
        class=QueuedMessageKeysTest
    else
        sed 's/\.projectMessages(/.managedMessages(/g' "$root/$tests/HistoricalProjectionReplayTest.kt" > "$directory/$tests/HistoricalProjectionReplayTest.kt"
        target="*HistoricalProjectionReplayTest.$method"
        name=$method
        class=HistoricalProjectionReplayTest
    fi
    status=0
    "${gradle[@]}" -p "$directory" --no-daemon -Dorg.gradle.jvmargs=-Xmx3g testDebugUnitTest --tests "$target" > "$output/$side-gradle.txt" 2>&1 || status=$?
    cat "$output/$side-gradle.txt"
    result="$directory/app/build/test-results/testDebugUnitTest/TEST-dev.multiprompt.companion.reader.$class.xml"
    if [[ ! -f "$result" ]]; then
        printf 'MESSAGE_PROJECTION_REPLAY_INVALID: %s %s never ran %s.%s. Fix the replay build, do not weaken the assertion.\n' "$fix" "$side" "$class" "$name" >&2
        exit 1
    fi
    cp "$result" "$output/$side.xml"
    printf '%s\n' "$source" > "$output/$side-source-sha.txt"
    python3 - "$result" "$side" "$name" "$status" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
case = root.find("testcase[@name='" + sys.argv[3] + "']")
assert case is not None
failure = case.find('failure')
if sys.argv[2] == 'before':
    assert int(sys.argv[4]) != 0 and failure is not None
    assert failure.get('type') in ('java.lang.AssertionError', 'org.junit.ComparisonFailure'), failure.attrib
    if sys.argv[3] != 'distinctWaitingMessagesKeepDistinctKeysWhenTextHashesCollide':
        assert 'MESSAGE_PROJECTION_' in ''.join(failure.itertext())
    print('MESSAGE_PROJECTION_PAST_MISTAKE_REJECTED', sys.argv[3])
else:
    assert int(sys.argv[4]) == 0 and failure is None
    print('MESSAGE_PROJECTION_HISTORICAL_FIX_PASSED', sys.argv[3])
PY
done
