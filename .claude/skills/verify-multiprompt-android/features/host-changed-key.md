# Dismiss a changed host identity

A changed SSH identity must never authenticate without explicit replacement. **Not now** remembers that host's presented key type + fingerprint across refresh and app restart; it does not replace the trusted key or hide the connection error. A different identity must warn again.

## Sub-features

- `changed-key-compare`: server, saved fingerprint and independently generated new fingerprint in **SSH host key changed**.
- `changed-key-dismiss`: **Not now**, refresh and force-stop/relaunch keep the same warning dismissed.
- `changed-key-refuse`: original pin remains; no new accepted public-key authentication after rotation.
- `changed-key-replace`: **Replace trusted key**, another rotation, host edits and cross-device dismissal sync are not automated.

## How to get to it (user POV)

Add/trust Verify using the [hosts journey](hosts.md), remain in Inbox, then change the server's SSH host identity. **Session list actions → Refresh** opens **SSH host key changed**. Compare **Saved: ssh-ed25519 …** and **Now: ssh-ed25519 …**. Choose **Not now**, not **Replace trusted key**.

## Driving it with adb and UI Automator

Preconditions: fresh GitHub fixture; dispatch `feature=host-changed-key.md`. Doctor and visible version checks precede driving. Require `verify-session` and `Accepted publickey for verifyssh` after initial trust.

- Generate `$FIXTURE/rotated-host-key`; save its SHA256 fingerprint as `rotated-fingerprint.txt`. Snapshot all `Accepted publickey` lines as `accepted-before-rotation.txt`.
- Stop only `$FIXTURE/sshd.pid`, wait boundedly for exit, rewrite that fixture config's HostKey, restart with `-E "$FIXTURE/sshd.log"` (append). The config's PidFile records the new pid for cleanup; retain `rotated-sshd-pid.txt`. Do not restart tmux or modify the user key.
- `tap_desc 'Session list actions'`; `tap_text Refresh`; require **SSH host key changed**. Capture `changed-host-key`; XML must contain both exact independently known **Now:** and **Saved:** identities.
- `tap_text 'Not now'`; refresh again. Twelve bounded fresh dumps must contain neither the dialog title nor **Not now**, and the SSH log must show another connection attempt. Capture `changed-key-dismissed`.
- `am force-stop` then `am start` the debug app; if Sign in returns, use **Use without account (debug)** again. Require Inbox, rerun doctor, refresh and repeat the absence/connection checks; capture `changed-key-dismissed-relaunched`.
- Reopen **Hosts** and require **Pinned <original fingerprint>**; capture `original-key-still-pinned`. Save `accepted-after-rotation.txt`; it must equal the pre-rotation count or fail closed. Cleanup retains the append-only sshd.log and removes both host identities' key files.

## Gotchas

Absence alone is not proof: the initial rotated dialog is the positive control and subsequent refreshes must reach sshd. Cached session cards may remain visible while the host is unreachable; they are not new authentication. This checks one dismissed identity, not a permanent mute of future warnings. Source contract: `MainViewModel.dismissHostKey/applyRefresh`, `HostStore` serialization and `HostKeyDismissalTest` (repeated refresh, reload, unchanged pin, different type/fingerprint).
