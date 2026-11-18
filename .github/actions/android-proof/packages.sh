#!/usr/bin/env bash
set -euo pipefail
: "${HOME:?}"
packages=("$@")
test "${#packages[@]}" -gt 0
cache="$HOME/.cache/android-proof/debs"
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
  return 1
}
if [ "$(installed_count)" -eq "${#packages[@]}" ]; then
  echo ANDROID_PROOF_PACKAGES_ALREADY_INSTALLED
  exit 0
fi
mkdir -p "$cache"
if install_from_cache; then
  echo ANDROID_PROOF_PACKAGES_FROM_CACHE
else
  sudo apt-get update -qq
  sudo apt-get install -y --download-only -o Dir::Cache::archives="$cache" "${packages[@]}"
  install_from_cache || sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
fi
count=$(installed_count)
test "$count" -eq "${#packages[@]}"
echo "ANDROID_PROOF_PACKAGES_INSTALLED=$count/${#packages[@]}"
