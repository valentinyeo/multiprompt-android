# multiprompt Android verification map

This is the maintained map of the primary Compose mobile surface. Read SKILL.md before dispatching. The SSH reader is the core product; account sign-in, trusted host setup, discovery/search and the terminal fallback are the five starting features. Dictation, workspace sync and signed self-updates need additional external credentials or release conditions and are not automated here.

## Baseline preconditions

- Dispatch `verify.yml` on the pushed branch; never build or launch an emulator locally.
- Each invocation owns a fresh GitHub emulator, app data, runner SSH daemon and tmux fixture. Never double-drive an instance.
- The APK is `dev.multiprompt.companion.debug`, expected version `0.1.2-verify.<run-number>-debug`.
- Require `DOCTOR READY` before driving. Installation grants the app's declared runtime permissions so a notification prompt cannot obscure Sign in.
- SSH journeys use runner address `10.0.2.2`, port `2222`, user `verifyssh`, host label `Verify`, session `verify-session`, and job-generated ed25519 keys. Import through the real document picker and compare the fingerprint; never preseed host preferences or Keystore.
- Debug-only **Use without account (debug)** does not create an account or authenticate any account service. Release and m3 have no offline route.

## Driving conventions

Use the matching filename as workflow `feature` input. `drive.sh` uses `adb shell input` and UI Automator XML: exact text for visible names, contentDescription for icon buttons, label/placeholder plus EditText class for fields. Bounds come from a fresh dump, not hard-coded coordinates. The driver's functions are shell-internal; use the shipped driver, not a nonexistent adb selector API. Each recipe lists exact driver selectors and observable proof.

## Proof and skip reporting

Numbered PNG/XML pairs show action and result. The transcript records commands and exit; runner receipts, SSH auth log and tmux captures independently verify side effects. Read the artifact after cleanup. A mapped sub-feature or entry point is not necessarily covered by the dispatch recipe: report unexercised paths explicitly. Never claim OTP, changed-key handling, or release security was exercised merely because the debug fixture passed.

## Features

- [Sign in](sign-in.md): real account browser handoff; OTP remains manual.
- [Hosts](hosts.md): add VPS, import encrypted-at-rest key, compare/trust and retain the host identity.
- [Sessions](sessions.md): authenticated tmux discovery and search.
- [Session reader](session-reader.md): native output, Send, Enter/approve and Interrupt, with independent console receipts.
- [Terminal](terminal.md): real SSH PTY/tmux fallback attachment.
