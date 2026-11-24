#!/usr/bin/env bash
# Keep the ANR evidence the UI dump cannot show: /data/anr traces, dropbox, logcat.
set -uo pipefail
dir=${1:?usage: anr.sh <out-dir> [tag]}
tag=${2:-post}
mkdir -p "$dir"

say() { printf 'ANR_PROBE %s %s\n' "$tag" "$*"; }
shell() { timeout 120 adb shell "$@" 2>&1 | tr -d '\r'; }

say "state=$(shell 'id -un' | tr -d '\n') debuggable=$(shell getprop ro.debuggable | tr -d '\n') build=$(shell getprop ro.build.type | tr -d '\n')"

{
    echo "== $tag list before root"
    shell ls -la /data/anr/
} >> "$dir/ls-$tag.txt" 2>&1

if ! shell ls /data/anr/ > /dev/null 2>&1; then
    say 'adb root'
    timeout 60 adb root >> "$dir/root.txt" 2>&1
    timeout 180 adb wait-for-device >> "$dir/root.txt" 2>&1
    sleep 2
    {
        echo "== $tag list after root"
        shell ls -la /data/anr/
    } >> "$dir/ls-$tag.txt" 2>&1
fi

files=0
if timeout 300 adb pull /data/anr/ "$dir/data-anr" >> "$dir/pull-$tag.txt" 2>&1; then
    files=$(find "$dir/data-anr" -type f 2> /dev/null | wc -l)
fi
if [ "$files" -eq 0 ] && grep -q 'anr_' "$dir/ls-$tag.txt" 2> /dev/null; then
    say 'traces listed but not pulled, retrying as root'
    timeout 60 adb root >> "$dir/root.txt" 2>&1
    timeout 180 adb wait-for-device >> "$dir/root.txt" 2>&1
    sleep 2
    timeout 300 adb pull /data/anr/ "$dir/data-anr" >> "$dir/pull-$tag.txt" 2>&1
    files=$(find "$dir/data-anr" -type f 2> /dev/null | wc -l)
fi
say "pulled=$files"

timeout 120 adb shell dumpsys dropbox --print data_app_anr > "$dir/dropbox-$tag.txt" 2>&1 || rm -f "$dir/dropbox-$tag.txt"
timeout 120 adb logcat -d -b all 2> /dev/null \
    | grep -i -E 'ANR in |Reason: |Input dispatching timed out|not responding' > "$dir/logcat-$tag.txt" 2>&1 || true

anr_signs=0
if grep -qiE 'ANR in |not responding|Input dispatching timed out' "$dir/logcat-$tag.txt" 2> /dev/null; then anr_signs=1; fi
if grep -qE 'ANR in |Cmd line: ${VERIFY_PACKAGE:-multiprompt}' "$dir/dropbox-$tag.txt" 2> /dev/null; then anr_signs=1; fi
if grep -q . "$dir/ls-$tag.txt" 2> /dev/null && grep -q 'anr_' "$dir/ls-$tag.txt" 2> /dev/null; then anr_signs=1; fi
say "files=$files signs=$anr_signs"

if [ "$anr_signs" -eq 1 ] && [ "$files" -eq 0 ] && [ ! -f "$dir/bugreport-tried" ]; then
    touch "$dir/bugreport-tried"
    say 'no traces, falling back to bugreport'
    timeout 900 adb bugreport "$dir/bugreport.zip" >> "$dir/bugreport-$tag.txt" 2>&1 || true
fi

python3 -B "$(dirname "$0")/anr-main.py" "$dir" "${VERIFY_PACKAGE:-}" > "$dir/main-$tag.txt" 2>&1 || true
cat "$dir/main-$tag.txt"
