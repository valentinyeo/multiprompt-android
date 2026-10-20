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

Requires JDK 17 and Android SDK 36. Before every push run `tools/check.sh`: it is exactly what CI runs on every pull request and push to `main` (`.github/workflows/test.yml`).

| Rule | Enforced by |
|---|---|
| Never push code that does not compile or pass tests | `tools/check.sh` in `test.yml` |
| No code comments (license headers and tool directives allowed) | `tools/strip-comments.mjs --check` in `tools/check.sh` |
| No scratch output (logs, OCR dumps, screenshots) at the repo root | `tools/root-files.mjs` allowlist in `tools/check.sh` |
| Merged branches do not linger on GitHub | repo setting: delete branch on merge |
| No keys in Git; whole history rescanned weekly | `secret-scan` in `gitleaks.yml`, required on `main` |

## Release and install

- **Release:** merge the PR to `main`: `gh pr merge <n> --squash --delete-branch`. `release.yml` lints, tests, signs and republishes `android-latest`. Watch it: `gh run watch $(gh run list -w release.yml -L 1 --json databaseId -q ".[0].databaseId") --exit-status`. Trigger another release without a code change: `gh workflow run release.yml` (optionally add `-f version_name=0.2.0`).
- **Prove it:** `node tools/release-status.mjs` passes only when the live `android-latest` manifest serves the local `origin/main` ref (`--commit <sha>` for another commit).
- **Install:** the release app schedules an hourly background manifest check and notifies for a newer version: "multiprompt <version> is ready". Tap it to open Update, then tap "Download and update" to download, verify and install (Android may require install permission or confirmation). The Update screen also works directly. No adb or Play Store step. Debug builds (`./gradlew installDebug`, package suffix `.debug`) never receive release updates.

## Git workflow

- `main` is protected: every change lands through a pull request that passes `test` and `secret-scan`, then squash-merges. Never push to `main` directly.
- Never finish a task with changes parked in a branch, in the stash, or as untracked files. A task is done when its PR is merged and its branch deleted.

## Architecture

`MultipromptApplication` is the manual DI container (lazy singletons). `MainViewModel` holds one `AppUiState` `StateFlow`; `ui/MultipromptApp.kt` is the whole Compose tree, including full-screen reader and terminal overlays. There is no navigation library and no DI framework — keep it that way.

Data flow for the core feature:

1. `HostStore` — host profiles in plain SharedPreferences (`hosts`), JSON-encoded. No secrets here, only a `keySecretId` / `passphraseSecretId` reference.
2. `SecretStore` — private key bytes and passphrase, encrypted with a non-exportable Android Keystore AES key, stored in the `encrypted_secrets` prefs.
3. `SshRepository.listSessions` — one short-lived connection retrieves session metadata and encoded output previews through a fixed tmux command.
4. `SessionReaderConnection` — one long-lived authenticated connection streams framed tmux snapshots. Send, Enter, and Interrupt each use a fresh short-lived connection so an action cannot disrupt the reader stream. Prompt content travels through SSH stdin into a tmux buffer, never through shell interpolation.
5. `TerminalConnection` — its own long-lived PTY (`xterm-256color`) attaches with tmux `ignore-size`. The phone stays out of tmux's size calculation, so it cannot shrink the desktop pane.
6. `DeepgramDictation` — streams 16 kHz mono PCM from `AudioRecord` to Deepgram and exposes interim/final transcript state. Its API key is stored only through `SecretStore`; never add it to Git, Gradle properties, `BuildConfig`, or release assets.

7. `CrashReportStore` — persists crash-time metadata and stable ids; pending reports upload on startup/sign-in through account auth on IO, and failed uploads retain login and reports (including dismissed reports). Debug-only crash proof: `adb shell am start -n dev.multiprompt.companion.debug/dev.multiprompt.companion.DebugCrashActivity`.

### Security invariants — do not relax these

- **Host keys fail closed.** `SshRepository.PinningHostKeyVerifier` pins type + SHA-256 fingerprint from `HostProfile`. An unknown or changed key throws `SshProblem.HostKeyRequired` and the UI makes the user compare and trust it explicitly. Never auto-accept.
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

Debug builds use applicationId suffix `.debug`, so they are a different package with a different certificate and cannot receive release updates.

## Conventions

- Add dependencies via `gradle/libs.versions.toml`, never inline coordinates.
- Business logic that can be tested on the JVM (parsing, manifest validation) lives in pure objects/data classes with tests in `app/src/test`; there is no instrumentation test suite.
- Suspend + `Dispatchers.IO` for all SSH work; the ViewModel exposes only state, the UI never touches `SshRepository` directly.
