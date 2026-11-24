#!/usr/bin/env bash
set -uo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
: "${EVIDENCE:?Set EVIDENCE to the evidence directory}"
rounds=${ROUNDS:-.bisect/rounds.json}
apks=${APKS:-bisect-apks}
out="$EVIDENCE/bisect"
mkdir -p "$out"
readings="$out/readings.csv"
printf 'pair,side,sha,trial,ticks,proc_ticks,window_s,replies,rendered,anr,skipped_frames\n' > "$readings"
export VERIFY_PACKAGE=dev.multiprompt.companion.debug
package=$VERIFY_PACKAGE
scripts="$root/.claude/skills/verify-multiprompt-android/scripts"
repeats=$(python3 -B tools/bisect-mubo-453/plan.py "$rounds" --repeats)
replies=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["replies_per_window"])' "$rounds")
interval=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["reply_interval_s"])' "$rounds")
window_s=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["window_s"])' "$rounds")
source_json=/home/verifyssh/long-chat-source.json
marker=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["latest"])' "$source_json")
source_path=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["path"])' "$source_json")
source_backup="${source_path}.bisect-orig"

mpa() { python3 -B "$scripts/mpa" "$@" < /dev/null; }

adb shell settings put global hide_error_dialogs 1 < /dev/null > /dev/null 2>&1 || true
adb shell dumpsys window < /dev/null > "$out/window.txt" 2>&1 || true

dismiss_anr() {
    local xml="$out/anr.xml" point x y
    mpa read --out "$xml" > /dev/null 2>&1 || return 1
    python3 "$scripts/ui.py" "$xml" contains "isn't responding" > /dev/null 2>&1 || return 1
    point=$(python3 "$scripts/ui.py" "$xml" text Wait) || return 0
    read -r x y <<< "$point"
    adb shell input tap "$x" "$y" < /dev/null
    printf 'ANR dismissed with Wait\n' >> "$out/anr.txt"
    return 0
}

wait_text() {
    local label=$1 attempt
    for attempt in 1 2 3; do
        if mpa read --wait-text "$label" --timeout 60 > "$out/wait.json" 2>&1; then
            return 0
        fi
        dismiss_anr || true
        sleep 2
    done
    printf 'MISSING_TEXT %s\n' "$label" >> "$out/setup-failures.txt"
    return 1
}

ticks() {
    adb shell cat "/proc/$1/task/$1/stat" < /dev/null 2> /dev/null | awk '{print $14+$15}' | tr -d '\r'
}

proc_ticks() {
    adb shell cat "/proc/$1/stat" < /dev/null 2> /dev/null | awk '{print $14+$15}' | tr -d '\r'
}

install_side() {
    local sha=$1
    adb install -r -d -g "$apks/$sha/app-debug.apk" < /dev/null > "$out/install-$sha.txt" 2>&1
    grep -q Success "$out/install-$sha.txt"
}

launch_inbox() {
    local attempt
    adb shell am force-stop "$package" < /dev/null
    mpa launch > "$out/launch.json" 2>&1
    for attempt in 1 2 3 4 5; do
        mpa read --out "$out/screen.json" > /dev/null 2>&1
        if grep -Fq 'Trust and connect' "$out/screen.json"; then
            mpa tap text 'Trust and connect' > "$out/tap-trust.json" 2>&1
            sleep 5
            continue
        fi
        if grep -Fq 'Use without account (debug)' "$out/screen.json"; then
            mpa tap text 'Use without account (debug)' > "$out/tap-account.json" 2>&1
            sleep 3
            continue
        fi
        if grep -Fq 'Inbox' "$out/screen.json"; then
            return 0
        fi
        dismiss_anr || true
        sleep 5
    done
    printf 'MISSING_TEXT Inbox\n' >> "$out/setup-failures.txt"
    return 1
}

wait_chat() {
    wait_text "$marker"
}

open_chat() {
    launch_inbox || return 1
    mpa tap text All > "$out/tap-all.json" 2>&1
    wait_text cl-long-chat || return 1
    mpa tap text cl-long-chat > "$out/tap-chat.json" 2>&1 || return 1
    wait_chat || return 1
}

reset_transcript() {
    sudo -u verifyssh cp "$source_backup" "$source_path"
}

window() {
    local pair=$1 side=$2 sha=$3 trial=$4 pid start end pstart pend begin finish rendered=missing delivered=0 anr=0 skipped=0
    reset_transcript
    if ! install_side "$sha"; then
        printf '%s,%s,%s,%s,,,,,,install-failed,0,0\n' "$pair" "$side" "$sha" "$trial" >> "$readings"
        return 1
    fi
    if ! open_chat; then
        printf '%s,%s,%s,%s,,,,,,open-failed,0,0\n' "$pair" "$side" "$sha" "$trial" >> "$readings"
        return 1
    fi
    sleep 5
    pid=$(adb shell pidof "$package" < /dev/null 2> /dev/null | tr -d '\r ' | awk '{print $1}')
    if [ -z "$pid" ]; then
        printf '%s,%s,%s,%s,,,,,,no-pid,0,0\n' "$pair" "$side" "$sha" "$trial" >> "$readings"
        return 1
    fi
    adb shell log -t BISECT "START pair=$pair side=$side sha=$sha trial=$trial pid=$pid" < /dev/null 2> /dev/null || true
    start=$(ticks "$pid")
    pstart=$(proc_ticks "$pid")
    begin=$(python3 -c 'import time;print(f"{time.time():.3f}")')
    sudo -u verifyssh python3 -B /home/verifyssh/bisect-stream.py "$source_json" "$interval" "$replies" \
        < /dev/null > "$out/stream-$pair-$side-$trial.log" 2>&1 &
    local writer=$!
    sleep "$window_s"
    wait "$writer" 2> /dev/null || true
    finish=$(python3 -c 'import time;print(f"{time.time():.3f}")')
    end=$(ticks "$pid")
    pend=$(proc_ticks "$pid")
    adb shell log -t BISECT "END pair=$pair side=$side sha=$sha trial=$trial pid=$pid" < /dev/null 2> /dev/null || true
    if dismiss_anr; then
        anr=1
    fi
    delivered=$(grep -c '^WROTE SPIKE_STREAM_STEP_' "$out/stream-$pair-$side-$trial.log" 2> /dev/null || true)
    if [ "$delivered" -gt 0 ] && mpa read --wait-text "SPIKE_STREAM_STEP_$((delivered - 1))" --timeout 20 > "$out/rendered-$pair-$side-$trial.json" 2>&1; then
        rendered=ok
    else
        if dismiss_anr; then
            anr=$((anr + 1))
        fi
    fi
    mpa screenshot "bisect-$pair-$side-$trial" > /dev/null 2>&1 || true
    skipped=$(adb logcat -d < /dev/null 2> /dev/null | awk -v s="START pair=$pair side=$side sha=$sha trial=$trial" -v e="END pair=$pair side=$side sha=$sha trial=$trial" '
        index($0, s) { on=1; next }
        index($0, e) { on=0 }
        on && /Choreographer: Skipped / { for (i=1;i<=NF;i++) if ($i=="Skipped") sum+=$(i+1) }
        END { print sum+0 }
    ')
    local log_anr
    log_anr=$(adb logcat -d < /dev/null 2> /dev/null | awk -v s="START pair=$pair side=$side sha=$sha trial=$trial" -v e="END pair=$pair side=$side sha=$sha trial=$trial" -v pkg="$package" '
        index($0, s) { on=1; next }
        index($0, e) { on=0 }
        on && index($0, "ANR in " pkg) { count++ }
        END { print count+0 }
    ')
    anr=$((anr + log_anr))
    local main_delta=$(( ${end:-0} - ${start:-0} ))
    local proc_delta=$(( ${pend:-0} - ${pstart:-0} ))
    printf '%s,%s,%s,%s,%s,%s,%.2f,%s,%s,%s,%s\n' "$pair" "$side" "$sha" "$trial" \
        "$main_delta" "$proc_delta" "$(python3 -c "print($finish - $begin)")" "$delivered" "$rendered" "$anr" "$skipped" >> "$readings"
    printf 'WINDOW pair=%s side=%s sha=%s trial=%s ticks=%s proc_ticks=%s rendered=%s anr=%s skipped=%s\n' \
        "$pair" "$side" "$sha" "$trial" "$main_delta" "$proc_delta" "$rendered" "$anr" "$skipped"
}

sudo install -m 755 tools/bisect-mubo-453/stream.py /home/verifyssh/bisect-stream.py
sudo -u verifyssh cp "$source_path" "$source_backup"
trap 'sudo -u verifyssh cp "$source_backup" "$source_path" 2>/dev/null || true; sudo -u verifyssh rm -f "$source_backup" 2>/dev/null || true' EXIT

first=$(python3 -B tools/bisect-mubo-453/plan.py "$rounds" | head -1)
if ! install_side "$first"; then
    printf 'SETUP_FAILED install %s\n' "$first" | tee "$out/setup-failures.txt"
    exit 1
fi
adb install -r -g "$apks/$first/app-debug-androidTest.apk" < /dev/null > "$out/install-test.txt" 2>&1
grep -q Success "$out/install-test.txt" || { printf 'SETUP_FAILED test apk\n' | tee -a "$out/setup-failures.txt"; exit 1; }
launch_inbox || exit 1
key=$(base64 -w0 "$FIXTURE/user-key")
adb shell "am instrument -w -e class dev.multiprompt.companion.account.SeedHostDeviceTest#importsCliHostFixture -e host_label 'Bisect probe' -e ssh_host 10.0.2.2 -e ssh_port 2222 -e ssh_user verifyssh -e ssh_private_key '$key' $package.test/androidx.test.runner.AndroidJUnitRunner" < /dev/null > "$out/host-seed.txt" 2>&1
grep -q 'OK (1 test)' "$out/host-seed.txt" || { printf 'SETUP_FAILED host seed\n' | tee -a "$out/setup-failures.txt"; exit 1; }
launch_inbox || exit 1
wait_text Inbox || exit 1
mpa tap text All > /dev/null 2>&1
wait_text cl-long-chat || exit 1
printf 'SETUP_READY long_chat=%s marker=%s repeats=%s window_s=%s replies=%s\n' \
    cl-long-chat "$marker" "$repeats" "$window_s" "$replies"

pair_index=0
exec 3< <(python3 -B tools/bisect-mubo-453/plan.py "$rounds" --pairs)
while read -r -u 3 base suspect; do
    [ -n "$base" ] || continue
    pair_index=$((pair_index + 1))
    trial=1
    while [ "$trial" -le "$repeats" ]; do
        if [ $((trial % 2)) -eq 1 ]; then
            order="$base $suspect"
        else
            order="$suspect $base"
        fi
        for sha in $order; do
            side=base
            [ "$sha" = "$suspect" ] && side=suspect
            window "$pair_index" "$side" "$sha" "$trial"
        done
        trial=$((trial + 1))
    done
done
exec 3<&-

adb logcat -d < /dev/null > "$out/logcat.txt" 2>&1 || true
python3 -B tools/bisect-mubo-453/report.py --readings "$readings" --versions .bisect/versions.json --out "$out" | tee "$out/report.txt"
