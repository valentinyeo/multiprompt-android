# React to a Claude reply

## Sub-features

- Three reactions in the existing message sheet.
- One quoted send through real SSH and tmux.
- One badge; no extra matched message bubble.
- The session's single answer to a reaction stays hidden; other replies show.
- Stable reply identity after restart.
- Unmatched reaction lines remain visible.
- Existing composer drafts stay unchanged.

## How to get to it (user POV)

Long-press a Claude reply. Choose 👍, 👎 or ❤️. The badge stays on that reply. Claude receives the quote and reaction.

## Driving it with the CI journey

Dispatch `verify.yml` with `feature=chat-reactions.md`.
The existing host-import and fingerprint comparison run first. The fixture provides two replies with identical prefixes. Its foreground process records every received message.

The driver keeps a draft, long-presses the first reply, checks all three choices, and selects 👍. It requires one quoted transport receipt. UI XML and screenshots require one badge between the first and second replies, no new reaction bubble, no "Reaction received." answer bubble, and the unchanged draft. The driver restarts the app and repeats the target check. An unmatched transcript line must remain visible.

For the negative control, set `app_ref` to the pre-feature commit. The same journey must fail with `IGSH975_REACTIONS_MISSING` after opening the real sheet.

For release proof, set `app_ref` to the live manifest's commit. Set `update_baseline_run` to the earlier reaction run that retained a signed baseline APK. The source journey runs unchanged. Then the old signed app's normal update worker must offer the new release. The candidate must pass package, version, size, checksum and same-certificate checks. The workflow installs it on its own emulator and records the version.

## Gotchas

No real Claude provider runs in CI. SSH, tmux, transcript discovery, UI gestures and message transport are real. The fixture consumes messages without paid model calls.

The feature journey uses the supported debug guest route. Production account OTP remains outside this proof. Signed APK identity and normal update notification are separate checks. Never call a debug APK the signed release.

Artifacts retain source and APK hashes, PNG/XML pairs, exact receipts, raw transcript, update manifests, certificates, update notification, installed version and cleanup logs. Keys stay outside the evidence directory.
