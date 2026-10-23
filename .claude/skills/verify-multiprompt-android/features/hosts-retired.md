# Retired servers disappear on update

A saved host that points at a retired server is removed the next time the app starts, with no action on the phone. The retired addresses live in `RetiredHosts.kt`: the old inne agent server `157.173.109.227` and the old Inne CRO server `45.151.123.11` (inne now runs on Hetzner, host 0). Account sync never adds a retired server back, so the desktop's slot 2 "Inne CRO" entry stays unmapped on the phone.

## Sub-features

- `retired-startup-removal`: `MainViewModel` init deletes every saved host whose whole trimmed hostname is retired, whatever its label or user, before the first refresh or account sync. `deleteHost` also clears slot overrides, cached sessions and the Inbox "Cached · … unreachable" banner state for that host.
- `retired-account-import`: `AccountHosts.merge` skips retired remote hosts (proof: `AccountHostsTest`; account OTP sign-in is not reachable in the emulator journey).
- `retired-kept`: other hosts, including Hetzner `46.224.218.39`, are kept (proof: `RetiredHostsTest`).

## How to get to it (user POV)

Nothing to tap. After the update installs and the app opens, **Session list actions → Hosts** no longer lists the retired server and Inbox shows no "unreachable" banner for it.

## Driving it with adb and UI Automator

Preconditions: fresh GitHub fixture, doctor passes, no saved hosts. Use `feature=hosts-retired.md` with the Launch command; the artifact is `verify-hosts-retired.md-<run-id>`.

- Before saving, the runner adds `iptables -I OUTPUT -d 45.151.123.11 -p tcp -j REJECT` (emulator traffic leaves through the runner) and saves `retired-address-blocked.txt`. The journey never needs the internet address; every connection attempt fails at once, so no trust prompt can appear. `retired-address-attempts.txt` records the rule's packet counter at the end.
- Driver: `tap_text 'Use without account (debug)'`, `tap_desc 'Session list actions'`, `tap_text Hosts`, `tap_desc 'Add host'`, `wait_for_text 'Add VPS'`.
- `fill Label 'Inne%sCRO'` (`%s` types a space), `fill 'Hostname or IP' 45.151.123.11`, `fill Username valentin`, `fill Port 22`, then import the disposable `verify-key.pem` through the real picker exactly like [hosts.md](hosts.md); saving requires a key. Capture `retired-form`.
- `tap_text 'Save and connect'`: `saveHost` stores the profile (`hosts.upsert`) before `refresh()` tries to connect. Require `Inbox` and no `Trust Inne CRO?`.
- Positive control: **Session list actions → Hosts** must show `Inne CRO` and `valentin@45.151.123.11:22`; capture `retired-host-saved`.
- Relaunch like [host-changed-key.md](host-changed-key.md): `adb shell am force-stop`, `am start -W` MainActivity, re-enter debug offline mode if Sign in shows, `wait_for_text Inbox`, doctor.
- Inbox: ten fresh dumps over ten seconds must contain `Inbox` and no text containing `unreachable` or `Inne CRO` (`ui.py … lacks`, which also fails on a blank UI); capture `relaunched-inbox`.
- Hosts: wait for the `Add host` button (proves the Hosts screen is shown), then require no text containing `Inne CRO` or `45.151.123.11`; capture `relaunched-hosts`. Success prints `RETIRED HOST REMOVED ON RELAUNCH VERIFIED`.

## Gotchas

On current main the saved CRO host survives the relaunch, so the Hosts check fails there. The Inbox banner check alone does not discriminate on a fresh emulator: the banner needs cached sessions from an earlier successful refresh, which a blocked address never produces. The Hosts card absence is the deciding assertion. Account-sync re-import needs OTP and is proven only by JVM tests.
