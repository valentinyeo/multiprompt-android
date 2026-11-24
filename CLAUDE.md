# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single Android app (`dev.multiprompt.companion`) that SSHes straight into public VPS hosts and provides a mobile reader for their tmux sessions. An embedded terminal remains an optional fallback. Deepgram WebSocket dictation can fill the native composer. No Tailscale or companion desktop app. Kotlin + Jetpack Compose, Gradle version catalog, ConnectBot `sshlib` + `termlib` for transport and emulation, and OkHttp for dictation.

## Build and test

```bash
./gradlew test              # JVM unit tests (app/src/test)
./gradlew lintDebug assembleDebug
./gradlew testDebugUnitTest --tests '*TmuxParserTest*'   # single test class
```

Start time with and without the baseline profile: dispatch `startup-time.yml` (`gh workflow run startup-time.yml --ref <branch> -f runs=10`). One emulator job builds the release APK with a throwaway key, runs `tools/startup-time/measure.sh`, and writes the medians and the difference in milliseconds to the job summary and the `startup-time-report` artifact. It never runs on pull requests and does not gate merges.

Emulator scroll-under proof: `tools/top-fade-proof.sh`. GitHub runs it in `top-fade.yml`. Dispatch with `release_smoke=true` to verify the published APK.

The shared emulator action installs the two packages it needs (`gdb` for the crash tracer, `libpulse0` for audio) from the runner cache, and only falls back to a package download when that cache is empty, so a slow mirror cannot add minutes to every journey job. `tools/emulator.test.py` proves the cache-first order, the empty-cache fallback and the install assertion.

Requires JDK 17 and Android SDK 36. Before every push run the focused source checks. Run `tools/check.sh` on GitHub, never local Gradle. On every PR and main push, `test.yml` runs its `source`, `unit`, `m3` and `lint` phases plus the debug build. Historical mutations and phone regressions run in `android-full.yml` nightly.

| Rule | Enforced by |
|---|---|
| Every PR builds, unit-tests and lints without unrelated phone jobs | `test.yml` runs build and `tools/check.sh source/unit/m3/lint`; `tools/ci/pr-workflows.test.mjs` checks workflow ownership |
| Every PR runs a head phone journey for each feature it touches, and nothing else | `pstack-verifier.yml`, `verifier.cjs` and `tools/pstack-verifier.test.js` |
| A journey red in the latest nightly run on main never blocks a PR; the verify chat files a ticket for it | the `red-journeys` artifact from `android-full.yml`, `applyRules` in `verifier.cjs` and `tools/pstack-verifier.test.js` |
| Independent PR verification | [pstack shipping step 1](https://github.com/cursor/plugins/blob/main/pstack/skills/poteto-mode/playbooks/shipping.md); `pstack-verifier.yml`, `verifier.cjs`, `tools/pstack-verifier.test.js` |
| Reuse base proof only for the same source SHA, journey and unchanged driver inputs | `parent-cache.cjs`, retained base artifacts and `tools/parent-cache.test.js` |
| No code comments (license headers and tool directives allowed) | `tools/strip-comments.mjs --check` in `tools/check.sh` |
| An old-screen check builds that complete revision in its own worktree, or uses a retained APK. Do not copy old source into the current app | `tools/historical_control.py` in `tools/check.sh`; `tools/check-session-pins.sh` builds commit `93f137d8` |
| Changed product outcomes need matching head-journey action/result proof at the exact APK SHA; smoke is not coverage. Initial gaps do not exempt future changes | `features/registry.json`, the `coverage.cjs` ratchet in `maintain-verification.yml`, and structured artifact validation in `pstack-verifier` |
| Retrying a check replaces only its own per-run proof artifact | `overwrite: true` on proof uploads; PR #392 `c2f673c6` HTTP 409 replay in `tools/pstack-verifier.test.js` |
| Table readiness recognises the icon-only Close description without text or exported test tags | retained phone-screen and expand-handshake replays in `tools/chat-table/ui.test.py`, run by `verify.yml` |
| One registry owns verification recipes, picker eligibility and dispatch options | `coverage.cjs check` in `tools/check.sh` and `test.yml` |
| Journey text replacement never types into an uncleared field | `drive.sh clear_field/fill`, `ui.py` readback and `tools/journey-actions/action.test.py` query/draft/numeric failure replays |
| Journey taps resolve an enabled action in the active window, reveal its full clickable bounds and scroll the matching axis; new raw taps are forbidden | `journey_action.py` through `drive.sh tap_node`, `tools/journey-actions/check.py` baseline ratchet and `action.test.py` historical replays in `tools/check.sh` |
| Shipping buttons and menus use `ui/DesignKit.kt`; no legacy button wrappers. Baseline allowances can only shrink | Resolved-symbol `KitControlRequired` Android lint in `tools/kit-controls`, run for debug/release/m3 by `tools/check.sh lint`; historical #132/#136 replays and `history.test.py` baseline guard |
| No Material button, `AlertDialog`, `DropdownMenu`/`DropdownMenuItem`, `Switch`, `ModalBottomSheet`, `FilledTonal*` control, `tonalElevation` or purple colour in any `app/src/main` screen outside `ui/DesignKit.kt` and `ui/Theme.kt`; the fix is always the Kit* equivalent, never an allowance | `tools/kit-controls/check.py` `KIT_MATERIAL_BYPASSED` source scan in `tools/check.sh source`, proved on the #136 (`c58a41b6`) and #392 (`38878ce0^`) revisions by `check.test.py` |
| One session owns snapshot/history reads; screens hold foreground leases and release only their own lease. New read implementations cannot bypass the owner. Canceled unissued actions are rejected | `ChatPollGate`, `SessionReaderConnection`, `tools/reader-ownership/check.py` per-file baseline ratchet and historical replays, plus hosted reader lifecycle tests/journey |
| ChatHistory.projectMessages owns visible message identity; protocols and UI never append independent echoes. Legacy text matching stays at LegacyMessageIdentity | `tools/message-projection/check.py` per-path baseline ratchet, `MessageProjectionTest`, and historical JVM replay in `android-full.yml` |
| One `MessageIdentity` owns every chat row key and every text match, and its matcher takes `MessageText`, not `String` | `tools/message-projection/check.py` `MESSAGE_IDENTITY_BYPASSED`, `MessageIdentityTest`, and the PR #539 replay |
| Android never appends a mention wire note or picks mention recipients; the send path marks the picked chat and the shared desktop send decides delivery | `MentionDecisionTest` |
| All six phone regressions run nightly, not per PR. Fixture users need no runner workspace access; named inbox targets and off-screen search resolve the exact seeded row | `android-full.yml`, `tools/ci/pr-workflows.test.mjs` and `tools/verify-seed.test.py` / `.js` |
| No scratch output (logs, OCR dumps, screenshots) at the repo root | `tools/root-files.mjs` allowlist in `tools/check.sh` |
| Merged branches do not linger on GitHub | repo setting: delete branch on merge |
| A release reads the Gradle cache and never writes it, so one failed release cannot poison every retry. Test workflows keep writing it | `cache-read-only: true` on the release `setup-gradle` step, checked by `tools/ci/release-cache.test.mjs` in `tools/check.sh source` |
| Every historical replay runs Gradle through `tools/ci/gradle-retry.sh`, so a Maven Central 429 cannot red a nightly, and a replay that never reaches its assertion says `MESSAGE_PROJECTION_REPLAY_INVALID` | `tools/ci/gradle-cache.test.mjs` and `tools/message-projection/replay.sh` |
| Every PR title starts with its ticket, like `IGSH-1684: what changed`; add that ticket's exact Hypertask title as one line in its own file, `tools/release-notes/titles/IGSH-N.txt`. Update notes show one `IGSH-N exact title` line per ticket, never filler | `tools/release-notes/notes.mjs` in `test.yml` and `release.yml` |
| The Android feature map gets weekly source and registered-journey coverage; drift opens one correction PR, never auto-merges. Pull requests never run the control list check | `maintain-verification.yml`, `control_map.py check/locations` and `control_map.test.py` with `CONTROL_MAP_LIVE=1`, its weekly base comparison, and the registry-driven hosted journey matrix |
| No keys in Git; whole history rescanned weekly | `secret-scan` in `gitleaks.yml`, required on `main` |

## Release and install

- **Release:** merge the PR to `main`: `gh pr merge <n> --squash --delete-branch`. `release.yml` lints, tests, signs and republishes `android-latest`. Watch it: `gh run watch $(gh run list -w release.yml -L 1 --json databaseId -q ".[0].databaseId") --exit-status`. Trigger another release without a code change: `gh workflow run release.yml` (optionally add `-f version_name=0.2.0`).
- **Release gate:** both channels wait for `Launch signed M3 APK`, which installs the signed M3 build and checks that `MainActivity` resumes before anything is published.
- **Prove it:** `node tools/release-status.mjs` passes only when the live `android-latest` manifest serves the local `origin/main` ref (`--commit <sha>` for another commit).
- **Trust the download:** `node tools/verify-published.mjs android-m3-latest` (or `android-latest`) fetches the manifest, downloads that APK, and fails unless the size, sha256, package, version and Multi Bot label all match.
- **Install:** the release app schedules an hourly background manifest check and notifies for a newer version: "multiprompt <version> is ready". Tap it to open Update, then tap "Download and update" to download, verify and install (Android may require install permission or confirmation). The Update screen also works directly. No adb or Play Store step. Debug builds (`./gradlew installDebug`, package suffix `.debug`) never receive release updates.

## Git workflow

- Follow [pstack shipping step 1](https://github.com/cursor/plugins/blob/main/pstack/skills/poteto-mode/playbooks/shipping.md). Acquire the shared merge lock with `node tools/merge-lock.mjs acquire`, wait on CI with `node tools/pr-checks.mjs <PR> --wait`, merge the checked head with `node tools/merge-lock.mjs merge <PR> <checked-sha>`, then `node tools/merge-lock.mjs release`. The lock is held per chat, so a later call in the same chat renews or releases it. Merge-gating check names are in `tools/pr-checks.mjs`; compare branch protection with `node tools/branch-protection.mjs --check`. Never push directly to `main`.
- One wait per PR; five-minute polls; thirty-minute maximum. A new push to the PR head stops the wait. Update the branch only when it conflicts with main (GitHub shows mergeable CONFLICTING); main's post-merge runs are the integration gate. Never use bare `gh pr checks --watch` or `gh-green` as complete-check proof.
- Emergency regression: revert the bad change first through a PR with the same verifier and cheap checks. Restore service before attempting a forward fix. Never bypass the signed launch gate or branch protection.
- Merge the checked head with `node tools/merge-lock.mjs merge <PR> <checked-sha>`. It merges through the GitHub API with a squash and then deletes the branch, so it works from a worktree where `gh pr merge --delete-branch` fails trying to check out and delete the local branch.
- Never finish a task with changes parked in a branch, in the stash, or as untracked files. A task is done when its PR is merged and its branch deleted.

## Design system

All Android UI is built from the kit in `app/src/main/java/dev/multiprompt/companion/ui/DesignKit.kt` with the `Reader*` colours in `Theme.kt`. Figma page: https://www.figma.com/design/8Yzu3N21UlCL7lA3YXm45D?node-id=41-441 (Yeo UX team).

- Use only `Kit*` components and `Kit` values in new screens. No Material `DropdownMenu`, `AlertDialog`, `Switch`, tonal fills or purple colours; every menu is `KitSheet`.
- One surface style: fill `Kit.Surface`, 1 dp `Kit.Outline`, fully round pills and buttons, 20 dp corners on cards and bubbles.
- Blue (`Kit.AccentUnread`) only for unread. Red (`Kit.Danger`) only for destructive actions.
- A component or colour the kit lacks needs a Figma frame and Valentin's yes before it is added. `DesignKitTest` fails when a component stops painting its colour and when `DesignKit.kt` uses Material colour schemes or raw colour literals.

## Architecture

`MultipromptApplication` is the manual DI container (lazy singletons). `MainViewModel` holds one `AppUiState` `StateFlow`; `ui/MultipromptApp.kt` is the whole Compose tree, including full-screen reader and terminal overlays. There is no navigation library and no DI framework — keep it that way.

Data flow for the core feature:

1. `HostStore` — host profiles in plain SharedPreferences (`hosts`), JSON-encoded. No secrets here, only a `keySecretId` / `passphraseSecretId` reference.
2. `SecretStore` — private key bytes and passphrase, encrypted with a non-exportable Android Keystore AES key, stored in the `encrypted_secrets` prefs.
3. `SshRepository.listSessions` — one short-lived connection retrieves session metadata and encoded output previews through a fixed tmux command.
4. `SessionReaderConnection` — one long-lived authenticated connection streams framed tmux snapshots. The stream runs only while its chat is on screen (`ReaderStreamPump`, gated by `ChatPollGate`); a hidden warm chat keeps the authenticated connection but opens no stream channel, and opening it starts one that sends a snapshot at once. Send, Enter, and Interrupt each use a fresh short-lived connection so an action cannot disrupt the reader stream. Prompt content travels through SSH stdin into a tmux buffer, never through shell interpolation.
5. `TerminalConnection` — its own long-lived PTY (`xterm-256color`) attaches with tmux `ignore-size`. The phone stays out of tmux's size calculation, so it cannot shrink the desktop pane.
6. `DeepgramDictation` — streams 16 kHz mono PCM from `AudioRecord` to Deepgram and exposes interim/final transcript state. Its API key is stored only through `SecretStore`; never add it to Git, Gradle properties, `BuildConfig`, or release assets.

7. `CrashReportStore` — persists crash-time metadata and stable ids; pending reports upload on startup/sign-in through account auth on IO, and failed uploads retain login and reports (including dismissed reports). Debug-only crash proof: `adb shell am start -n dev.multiprompt.companion.debug/dev.multiprompt.companion.DebugCrashActivity`.

### Security invariants — do not relax these

- **Host keys reject unknown or changed fingerprints.** `SshRepository.PinningHostKeyVerifier` pins type + SHA-256 fingerprint from `HostProfile`. An unknown or changed key throws `SshProblem.HostKeyRequired` and the UI makes the user compare and trust it explicitly. Never auto-accept.
- **Public-key auth only.** No password auth, no arbitrary remote-command box in the UI.
- **Everything interpolated into a remote command goes through `TmuxParser.shellQuote`.** There is a test asserting it blocks injection.
- **Reader commands are allowlisted.** Prompt content must travel through SSH stdin and tmux buffer/paste operations, never inside a shell command.
- **`UpdateManager` verifies before installing:** HTTPS-only transport, declared size and SHA-256, package name, strictly newer versionCode, and the installed APK's signing certificate digest. Every one of those checks is load-bearing; a downgrade or a differently-signed APK must be rejected.
- **No Cloudflare service token or other long-lived secret is ever embedded in the APK.** Android uses the desktop account service at `account.multiprompt.dev`: Access email OTP in a Custom Tab, exact app callback with state + PKCE S256, and rotating per-device user tokens in Keystore-backed `SecretStore`. Network errors must never clear login. Current schema-1 workspaces are plaintext metadata over HTTPS, not the older encrypted-sync/OIDC scaffolding; see `docs/account-workspaces.md`.

Cleartext traffic is off (`res/xml/network_security_config.xml`).

## Release channel

Release APKs must always be signed with the same key, or the self-update chain breaks. Signing config comes from a gitignored `keystore.properties` at the repo root (see `keystore.properties.example`, `scripts/create-signing-keystore.ps1`); when the file is absent the release build simply has no signing config. CI restores it from `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`.

`versionCode` / `versionName` are injected as Gradle properties (`-PversionCode=... -PversionName=...`), defaulting to `1` / `0.1.0-dev` locally. CI derives versionCode from `100000 + GITHUB_RUN_NUMBER`, so it only ever increases.

The update manifest URL is baked in as a `BuildConfig.UPDATE_MANIFEST_URL` field in `app/build.gradle.kts` and points at the fixed `android-latest` release asset. Changing the repo or release tag means changing both that field and the workflow. Manifest shape is parsed strictly in `UpdateManifest.kt` — add fields there and in the workflow's `jq` block together.

`app/src/main/baseline-prof.txt` is a hand-written baseline profile that AGP compiles into the release and m3 APKs; `androidx.profileinstaller` installs it on self-updated and sideloaded installs. `node tools/baseline-profile.mjs source` checks the rules and `node tools/baseline-profile.mjs apk <apk>` checks a built APK. Rules must use the documented form `HSPLdev/multiprompt/companion/ui/**->**(**)**` and name real packages.

Debug builds use applicationId suffix `.debug`, so they are a different package with a different certificate and cannot receive release updates.

## Conventions

- Add dependencies via `gradle/libs.versions.toml`, never inline coordinates.
- Business logic that can be tested on the JVM (parsing, manifest validation) lives in pure objects/data classes with tests in `app/src/test`; emulator UI checks live in `app/src/androidTest`.
- Suspend + `Dispatchers.IO` for all SSH work; the ViewModel exposes only state, the UI never touches `SshRepository` directly.
