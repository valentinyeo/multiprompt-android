#!/usr/bin/env bash
set -euo pipefail
: "${HOME:?}"
packages=("$@")
test "${#packages[@]}" -gt 0
cache="${ANDROID_PROOF_DEB_CACHE:-$HOME/.cache/android-proof/debs}"
fetch_jobs="${ANDROID_PROOF_FETCH_JOBS:-16}"
fetch_bound="${ANDROID_PROOF_FETCH_BOUND:-600}"
file_bound="${ANDROID_PROOF_FETCH_FILE_BOUND:-180}"
install_bound="${ANDROID_PROOF_INSTALL_BOUND:-600}"
installed_count() {
  dpkg-query -W -f='${Status}\n' "${packages[@]}" 2>/dev/null | grep -c '^install ok installed$' || true
}
cache_has_debs() {
  [ -n "$(find "$cache" -maxdepth 1 -name '*.deb' -print -quit 2>/dev/null)" ]
}
install_from_cache() {
  local attempt
  cache_has_debs || return 1
  for attempt in 1 2 3; do
    sudo env DEBIAN_FRONTEND=noninteractive dpkg -i "$cache"/*.deb > /dev/null 2>&1 || true
    [ "$(installed_count)" -eq "${#packages[@]}" ] && return 0
  done
  echo ANDROID_PROOF_PACKAGES_CACHE_REJECTED
  sudo env DEBIAN_FRONTEND=noninteractive apt-get -f install -y > /dev/null 2>&1 || true
  sudo rm -f "$cache"/*.deb
  return 1
}
verify_sum() {
  local kind=$1 want=$2 path=$3 got
  case "$kind" in
    MD5Sum) got=$(md5sum "$path" | cut -d' ' -f1) ;;
    SHA1) got=$(sha1sum "$path" | cut -d' ' -f1) ;;
    SHA256) got=$(sha256sum "$path" | cut -d' ' -f1) ;;
    SHA512) got=$(sha512sum "$path" | cut -d' ' -f1) ;;
    *) return 1 ;;
  esac
  [ "$got" = "$want" ]
}
fast_uri() {
  case "$1" in
    http://*ec2.archive.ubuntu.com/ubuntu/*)
      printf 'https://archive.ubuntu.com/ubuntu/%s\n' "${1#*ec2.archive.ubuntu.com/ubuntu/}"
      ;;
    *) printf '%s\n' "$1" ;;
  esac
}
fetch_deb() {
  local fast=$1 slow=$2 file=$3 kind=$4 sum=$5 target uri
  local -a tries=("$fast")
  target="$cache/$file"
  [ -s "$target" ] && return 0
  [ "$fast" = "$slow" ] || tries+=("$slow")
  for uri in "${tries[@]}"; do
    if ! curl -fsSL --connect-timeout 10 --max-time "$file_bound" --retry 3 --retry-delay 1 --retry-all-errors -o "$target.part" "$uri";
    then
      rm -f "$target.part"
      continue
    fi
    if verify_sum "$kind" "$sum" "$target.part"; then
      mv "$target.part" "$target"
      return 0
    fi
    echo "ANDROID_PROOF_DEB_CHECKSUM_FAILED=$file"
    rm -f "$target.part"
  done
  echo "ANDROID_PROOF_DEB_DOWNLOAD_FAILED=$file"
  return 1
}
deb_plan() {
  sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y --print-uris -o Dir::Cache::archives="$cache" "${packages[@]}" |
    sed -n "s/^'\([^']*\)' \([^ ]*\) \([0-9][0-9]*\) \([A-Za-z0-9]*\):\([0-9a-fA-F]*\)$/\1 \2 \4 \5/p" |
    grep -E '^https?://' || true
}
fetch_debs() {
  local uri file kind sum plan
  command -v curl > /dev/null || return 1
  plan="$(deb_plan)"
  [ -n "$plan" ] || return 1
  rm -f "$cache"/*.part
  while read -r uri file kind sum; do
    printf '%s %s %s %s %s\n' "$(fast_uri "$uri")" "$uri" "$file" "$kind" "$sum"
  done <<< "$plan" |
    timeout --signal=TERM --kill-after=30 "$fetch_bound" xargs -P "$fetch_jobs" -n 5 bash -c 'fetch_deb "$@"' _
}
export cache file_bound
export -f verify_sum fetch_deb
if [ "$(installed_count)" -eq "${#packages[@]}" ]; then
  echo ANDROID_PROOF_PACKAGES_ALREADY_INSTALLED
  exit 0
fi
mkdir -p "$cache"
if install_from_cache; then
  echo ANDROID_PROOF_PACKAGES_FROM_CACHE
else
  sudo apt-get update -qq
  fetch_debs || true
  sudo rm -rf "$cache/partial" "$cache/lock"
  sudo chmod -R a+rX "$cache"
  install_from_cache || timeout --signal=TERM --kill-after=30 "$install_bound" sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
fi
count=$(installed_count)
test "$count" -eq "${#packages[@]}"
echo "ANDROID_PROOF_PACKAGES_INSTALLED=$count/${#packages[@]}"
