#!/system/bin/sh
pkg=$1
pids=$(pidof "$pkg" 2>/dev/null)
if [ -z "$pids" ]; then
  printf 'NO_PID\n'
  exit 0
fi
for pid in $pids; do
  for f in /proc/$pid/task/*/stat; do
    [ -r "$f" ] || continue
    read -r line < "$f" || continue
    rest=${line##*')'}
    set -- $rest
    ticks=$(( ${12} + ${13} ))
    tid=${f%/stat}
    tid=${tid##*/}
    name=''
    [ -r "/proc/$pid/task/$tid/comm" ] && read -r name < "/proc/$pid/task/$tid/comm"
    printf '%s %s %s\n' "$tid" "$ticks" "$name"
  done
done
exit 0
