#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?}"
: "${FIXTURE:?}"
: "${VERIFY_APK:?}"
: "${VERIFY_PACKAGE:?}"
: "${VERIFY_FEATURE:?}"
[[ "$GITHUB_ACTIONS" == true && "$(adb get-serialno)" == emulator-* ]]
sha256sum --check "$EVIDENCE/app-apk-sha256.txt"
candidate_sha=$(sha256sum "$VERIFY_APK" | cut -d' ' -f1)
adb install -r -g "$VERIFY_APK"
installed_path=$(adb shell pm path "$VERIFY_PACKAGE" | tr -d '\r' | sed -n 's/^package://p')
[[ "$installed_path" == /* && "$installed_path" != *$'\n'* ]]
adb pull "$installed_path" "$FIXTURE/installed.apk"
installed_sha=$(sha256sum "$FIXTURE/installed.apk" | cut -d' ' -f1)
test "$installed_sha" = "$candidate_sha"
printf '%s\n' "$installed_sha" > "$EVIDENCE/installed-apk-sha256.txt"
rm "$FIXTURE/installed.apk"
recorder_pid=
recorder_client=
finish() {
    code=$?
    trap - EXIT
    set +e
    if [[ "$recorder_pid" =~ ^[0-9]+$ ]]; then
        adb shell kill -0 "$recorder_pid" || code=1
        adb shell kill -2 "$recorder_pid" || code=1
        wait "$recorder_client"
        adb pull /sdcard/release-journey.mp4 "$EVIDENCE/release-journey.mp4" || code=1
        if ffprobe -v error -count_frames -show_streams -show_format -of json "$EVIDENCE/release-journey.mp4" > "$EVIDENCE/recording.json"; then
            jq -e '(.format.duration | tonumber) > 0 and any(.streams[]; .codec_type == "video" and (.nb_read_frames | tonumber) > 0)' "$EVIDENCE/recording.json" || code=1
        else
            code=1
        fi
    else
        code=1
    fi
    if [[ -f "$FIXTURE/offline-proxy.pid" ]]; then kill "$(cat "$FIXTURE/offline-proxy.pid")" || code=1; fi
    sha256sum --check "$EVIDENCE/app-apk-sha256.txt" || code=1
    jq -n --argjson exitCode "$code" --arg journey "$VERIFY_FEATURE" --arg sha256 "$candidate_sha" --arg commit "$GITHUB_SHA" --arg run "$GITHUB_RUN_ID" --arg attempt "$GITHUB_RUN_ATTEMPT" '{exitCode:$exitCode, journey:$journey, sha256:$sha256, commit:$commit, run:$run, attempt:$attempt}' > "$EVIDENCE/journey-result.json"
    if [[ "$code" == 0 ]]; then
        printf '%s\n' "$candidate_sha" > "$EVIDENCE/recorded-apk-sha256.txt"
    fi
    exit "$code"
}
trap finish EXIT
adb shell rm -f /sdcard/release-journey.mp4
: > "$FIXTURE/recorder.pid"
adb shell 'screenrecord --time-limit 0 --bit-rate 2000000 /sdcard/release-journey.mp4 & echo $!; wait' > "$FIXTURE/recorder.pid" 2> "$EVIDENCE/recording.log" &
recorder_client=$!
for attempt in {1..30}; do
    recorder_pid=$(head -1 "$FIXTURE/recorder.pid" | tr -d '\r')
    [[ "$recorder_pid" =~ ^[0-9]+$ ]] && break
    sleep 0.2
done
[[ "$recorder_pid" =~ ^[0-9]+$ ]]
adb shell kill -0 "$recorder_pid"
bash tools/release-choices/seed.sh
.claude/skills/verify-multiprompt-android/scripts/drive.sh "$VERIFY_FEATURE"
grep -Fx SIGNED_ROBOT_INBOX_AND_CHAT_VERIFIED "$EVIDENCE/robot-result.txt"
test -s "$EVIDENCE/ssh-auth.txt"
test -s "$EVIDENCE/tmux-final.txt"
