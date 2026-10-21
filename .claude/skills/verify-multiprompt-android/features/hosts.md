# Add and trust a VPS

A user adds an SSH host, imports a private key, explicitly compares and trusts its identity, then discovers its tmux sessions. Private key bytes are encrypted in Android Keystore-backed storage.

## Sub-features

- `host-add`: label, address, username and port form.
- `host-import`: real Android document-picker key import.
- `host-first-trust`: compare ed25519 fingerprint before public-key authentication.
- `host-pinned`: host list retains the pinned fingerprint.
- `host-maintain`: edit, replace key, add another user, delete and changed-key warning (not automated).

## How to get to it (user POV)

From Inbox choose **Session list actions → Hosts**, then **Add host**. The empty Inbox also offers the first-VPS action. Existing host cards offer **Edit**, **Delete** and **Add another user on this server**. The first connection opens **Trust Verify?**; unknown and changed identities do not authenticate until explicitly accepted.

## Driving it with adb and UI Automator

Preconditions: fresh GitHub fixture, doctor passes, no saved hosts. Use `feature=hosts.md` with the Launch command.

- Driver: `tap_text 'Use without account (debug)'`, `tap_desc 'Session list actions'`, `tap_text Hosts`, `tap_desc 'Add host'`. Expect `Add VPS`.
- `fill Label Verify`, `fill 'Hostname or IP' 10.0.2.2`, `fill Username verifyssh`, `fill Port 2222` use editable labels in fresh UI XML.
- `adb push "$FIXTURE/user-key" /sdcard/Download/verify-key.pem` prepares a disposable file, not app state. `tap_text 'Import private key'`, `tap_desc 'Show roots'`, `tap_text Downloads`, `tap_text verify-key.pem` exercise the system picker. `Key:` confirms import; capture `key-picker` and `key-imported`.
- `tap_text 'Save and connect'` must yield `Trust Verify?`. `compare-host-key` must show exactly the generated `ssh-ed25519` fingerprint. The driver rejects any SSH authentication recorded before trust.
- `tap_text 'Trust and connect'` must reveal `verify-session` and the server must record accepted public-key authentication.
- Reopen Hosts through the overflow menu; `wait_for_text "Pinned $fingerprint"` and `pinned-host` prove the saved identity from a second user-facing view.

## Gotchas

No paste-key field exists: import uses ACTION_OPEN_DOCUMENT, not an injected intent or app-private file. API 35's Downloads provider must expose the adb-pushed file; if that entry is unavailable, keep picker XML and report the exact blocker rather than preloading SecretStore. Keys are generated at runtime, never committed or uploaded. The host-key comparison uses the independent runner fingerprint, never a wildcard. Do not auto-accept a changed key or add password auth. Debug offline mode bypasses only account navigation; SSH authentication and pinning are real. Editing/deleting, restart persistence and changed-key replacement need separate runs and are not claimed by this recipe.
