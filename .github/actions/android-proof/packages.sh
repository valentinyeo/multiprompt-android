#!/usr/bin/env bash
set -euo pipefail
: "${HOME:?}"
packages=("$@")
test "${#packages[@]}" -gt 0
cache="$HOME/.cache/android-proof/debs"
repair_apt_state() {
  sudo env DEBIAN_FRONTEND=noninteractive dpkg --configure -a > /dev/null 2>&1 || true
  sudo env DEBIAN_FRONTEND=noninteractive apt-get -f install -y > /dev/null 2>&1 || true
}
apt_install_once() {
  sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
}
apt_install_with_retry() {
  repair_apt_state
  if apt_install_once "$@"; then
    return 0
  fi
  repair_apt_state
  apt_install_once "$@"
}
fail_setup() {
  echo ANDROID_PROOF_SETUP_FAILED
  exit 1
}
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
    repair_apt_state
    sudo env DEBIAN_FRONTEND=noninteractive dpkg -i "$cache"/*.deb > /dev/null 2>&1 || true
    [ "$(installed_count)" -eq "${#packages[@]}" ] && return 0
  done
  echo ANDROID_PROOF_PACKAGES_CACHE_REJECTED
  repair_apt_state
  sudo rm -f "$cache"/*.deb
  return 1
}
if [ "$(installed_count)" -eq "${#packages[@]}" ]; then
  echo ANDROID_PROOF_PACKAGES_ALREADY_INSTALLED
  exit 0
fi
repair_apt_state
mkdir -p "$cache"
if install_from_cache; then
  echo ANDROID_PROOF_PACKAGES_FROM_CACHE
else
  sudo apt-get update -qq
  sudo apt-get install -y --download-only -o Dir::Cache::archives="$cache" "${packages[@]}"
  sudo rm -rf "$cache/partial" "$cache/lock"
  sudo chmod -R a+rX "$cache"
  install_from_cache || apt_install_with_retry "${packages[@]}" || fail_setup
fi
count=$(installed_count)
[ "$count" -eq "${#packages[@]}" ] || fail_setup
echo "ANDROID_PROOF_PACKAGES_INSTALLED=$count/${#packages[@]}"