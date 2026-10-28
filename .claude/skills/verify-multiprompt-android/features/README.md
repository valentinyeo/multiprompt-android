# multiprompt Android verification map

This is the maintained map of the primary Compose mobile surface. Read SKILL.md before dispatching. The SSH reader is the core product. The journeys below cover local SSH and account entry; split-setting mutations require a real signed-in account. Dictation, account sync and signed self-updates have additional prerequisites.

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

Numbered PNG/XML pairs show action and result. The transcript records commands and exit; runner receipts, SSH auth log and tmux captures independently verify side effects. Read the artifact after cleanup. A mapped sub-feature or entry point is not necessarily covered by the dispatch recipe: report unexercised paths explicitly. Never claim OTP, synced settings, Chat View or release security was exercised merely because the debug fixture passed. The changed-key recipe covers dismissal of one rotated identity, not accepting replacements or subsequent rotations.

## Features

- [Sign in](sign-in.md): real account browser handoff; OTP remains manual.
- [Hosts](hosts.md): add VPS, import encrypted-at-rest key, compare/trust and retain the host identity.
- [Retired hosts](hosts-retired.md): a saved host at a retired server address is gone after relaunch; account sync never adds it back.
- [Changed host key](host-changed-key.md): compare a rotated identity, persist Not now and refuse authentication.
- [Sessions](sessions.md): authenticated tmux discovery and search.
- [Session settings](session-settings.md): full-screen split switches; saving is blocked without account OTP and a pulled layout.
- [Session routing](session-routing.md): Inbox and swipe switching, typed and simulated dictated sends, independent SSH receipts.
- [Chat choices](chat-choices.md): latest-reply option taps and live question number keys, with independent SSH/tmux receipts.
- [Working dots on the signed release](chat-working.md): quiet tools, lock/unlock, real SSH reconnect, recording and timed visible-state samples. Uses `working-dots.yml`.
- [Queued label](chat-queued.md): sent messages show Queued until taken in and answered, then return to normal.
- [Native Claude controls](native-skills.md): slash filtering, shared plus entry, actual session model/effort and retained image picker.
- [Claude reactions](chat-reactions.md): quoted sends, one badge, restart identity, fallback and signed update offers.
- [Resync chat](chat-resync.md): the three-dot Resync chat entry clears stale Queued labels, keeps the draft, resends nothing.
- [Session reader](session-reader.md): native output, Send, Enter/approve and Interrupt, with independent console receipts.
- [Terminal](terminal.md): real SSH PTY/tmux fallback attachment.

## Shipped but not automated

Prerequisites below are coverage gaps, never green skips. Account routes need human email OTP; never inject tokens or app state.

- **IGSH-449 device approval:** OTP, a second pending device and trusted SSH access to every saved host. Sign in on the new device; on the approving phone compare **Approve this device?** and fingerprint, tap **Approve**, then connect from the new device.
- **IGSH-429 crash upload:** a queued real app crash plus account-service receipt access. On a disposable debug install crash, relaunch, complete **Sign in**, and verify the stable report id server-side. Check startup/sign-in drain, including dismissed reports; the console never crashes the app.
- **IGSH-422 account hosts / first screen:** first-screen entry is automated. Sync needs OTP, account servers and host-sync capability. Complete **Sign in**, inspect **Session list actions → Hosts** and compare another device; offline hosts do not prove sync.
- **IGSH-432 Inbox badge:** an agent transcript with multiple new assistant messages after a recorded read position. Read a session, leave, generate replies on the host, check Inbox's count (`<n> unread messages`), reopen and check it clears. The console is not an agent.
- **IGSH-434 dictation:** Deepgram credential, microphone permission, controlled speech/silence and network/audio evidence. Reader **Session actions → More → Dictation API key**, **API key → Save and dictate**, then **Start dictation / Stop dictation**. Verify transcript and silence gating; short pre-roll/hangover is intentional.
- **IGSH-423 split settings:** saving needs OTP and a pulled account layout. **Session list actions → Settings → Show Frequent split**, Back, check **Frequent**; repeat off and compare another device. Not dispatchable in CI.
- **IGSH-415 Frequent:** account-mapped sessions with input in 24 h, or three watch visits of at least 10 s in 6 h. Send input, return to **Frequent**, compare ordering; check watch-only fallback and **Settings → Newest sessions at bottom** separately.
- **IGSH-426 version placement:** prefix checks Sign in's exact version only. No credential blocks local-screen layout inspection; navigation/bounds proof is unimplemented. Inspect the label on Inbox, reader, shell, Hosts, editor and Settings; tap for **Update**. Workspaces need OTP. Sign in's label is bottom-centred, not beside three dots.
- **IGSH-424 / IGSH-428 / IGSH-420 Chat View:** a supported agent (Claude) transcript on the SSH host, including tables/nested lists and model/effort/ETA state. Open from Inbox, scroll the complete transcript, inspect tables horizontally and top-bar subtitle; no **Conversation** header/status row. **Session actions → Session history** opens alternate-screen history. Console output proves none of this.
- **IGSH-418 reply:** a real bubble plus swipe/long-press input, beyond the tap-only driver. Swipe right or long-press → **Reply**; inspect **Reply to …**, **Cancel reply**, and send a quoted reply.
- **IGSH-425 shell:** multi-touch and long/wide tmux output, no extra credential. **Open live terminal**, pinch (8–28 sp, retained on reopen), pan, swipe down or **Open shell history**, then **Jump to live** / **Back to the live view**. Terminal.md proves attachment only.
- **IGSH-414 search layout:** matching/close are automated; no credential blocks layout proof, but keyboard-visible bounds inspection is unimplemented. Inbox **Search sessions**, type in **Search all sessions**, inspect results below the box and above the keyboard before closing.
- **IGSH-413 shared menu:** no credential blocks comparing signed-out **Sign in to sync** in Inbox **Session list actions** and reader **Session actions**; that comparison is unimplemented. OTP is needed for **Sync: signed in — sign out** and synced splits. Compare both menus, then complete sign-in manually.
