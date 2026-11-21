# Add and trust a VPS

A user adds an SSH host, explicitly compares and trusts its identity, then discovers its tmux sessions. Private key bytes are encrypted in Android Keystore-backed storage. The app has no host form and no key picker: hosts arrive from the CLI or the account, and these journeys seed one with instrumentation before the app talks to it.

## Sub-features

- `host-seed`: instrumentation stores the label, address, username, port and private key for one disposable host.
- Account-pulled hosts take the account's name (proof: `AccountHostNamesTest`; account OTP sign-in is not reachable in the emulator journey).
- `host-first-trust`: compare the ed25519 fingerprint in **Trust <label>?** before public-key authentication.
- `host-pinned`: **Settings → Host mapping** keeps showing the pinned fingerprint.
- `host-maintain`: changed-key dismissal has its own [journey](host-changed-key.md); editing, replacing a key, adding another user and deleting have no in-app screen, so they are not automated.

## How to get to it (user POV)

Inbox has no Hosts entry and no Add host action. The harness seeds the host (label, address, port, user and private key) through instrumentation and relaunches the app, which opens **Trust Verify?** for that host. Unknown and changed identities do not authenticate until explicitly accepted. **Settings → Host mapping** lists the host with **Pinned <fingerprint>**.

## Driving it with adb and UI Automator

Preconditions: fresh GitHub fixture, doctor passes, no saved hosts. Use `feature=hosts.md` with the Launch command.

- Driver: `tap_text 'Use without account (debug)'`, then `seed_host Verify 10.0.2.2 2222 verifyssh`. The helper runs `SeedHostDeviceTest#importsCliHostFixture` on the debug build and `dev.multiprompt.verification.Seed` on the signed build with the generated `$FIXTURE/user-key`. There is no **Add VPS** form, no **Import private key** button and no document picker to drive; `host-fingerprint.txt` keeps the independently generated value.
- `wait_for_text 'Trust Verify?'` plus `compare-host-key` must show exactly the generated `ssh-ed25519` fingerprint. The driver rejects any SSH authentication recorded before trust.
- A single `tap_text 'Trust and connect'` must reveal `verify-session` and the server must record accepted public-key authentication. Failure writes `trust-retries.txt`, captures `trust-retry-1` and exits without retrying.
- `tap_desc 'Session list actions'`, `tap_text Settings`, `tap_text 'Host mapping'`, then `wait_for_text "Pinned $fingerprint"`; `pinned-host` proves the saved identity from a user-facing screen.

## Gotchas

The seeded key is the fixture's own file; the app never imports it through a picker, so picker behaviour is out of scope here. Keys are generated at runtime, never committed or uploaded. The host-key comparison uses the independent runner fingerprint, never a wildcard. Do not auto-accept a changed key or add password auth. Debug offline mode bypasses only account navigation; SSH authentication and pinning are real. Editing/deleting, restart persistence and changed-key replacement need separate runs and are not claimed by this recipe.
