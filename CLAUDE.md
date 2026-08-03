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

Requires JDK 17 and Android SDK 36. CI runs `gradle --no-daemon lintDebug test assembleRelease`.

**VPS-first workflow:** normal development does not compile locally. Edit, commit, push `main`; GitHub Actions (`.github/workflows/release.yml`) lints, tests, signs, and republishes the fixed `android-latest` release. The installed app pulls the new build from its Update screen. Only reach for a local Gradle build when you specifically need one.

## Architecture

`MultipromptApplication` is the manual DI container (lazy singletons). `MainViewModel` holds one `AppUiState` `StateFlow`; `ui/MultipromptApp.kt` is the whole Compose tree, including full-screen reader and terminal overlays. There is no navigation library and no DI framework — keep it that way.

Data flow for the core feature:

1. `HostStore` — host profiles in plain SharedPreferences (`hosts`), JSON-encoded. No secrets here, only a `keySecretId` / `passphraseSecretId` reference.
2. `SecretStore` — private key bytes and passphrase, encrypted with a non-exportable Android Keystore AES key, stored in the `encrypted_secrets` prefs.
3. `SshRepository.listSessions` — one short-lived connection retrieves session metadata and encoded output previews through a fixed tmux command.
4. `SessionReaderConnection` — one long-lived authenticated connection streams framed tmux snapshots. Send, Enter, and Interrupt each use a fresh short-lived connection so an action cannot disrupt the reader stream. Prompt content travels through SSH stdin into a tmux buffer, never through shell interpolation.
5. `TerminalConnection` — its own long-lived PTY (`xterm-256color`) attaches with tmux `ignore-size`. The phone stays out of tmux's size calculation, so it cannot shrink the desktop pane.
6. `DeepgramDictation` — streams 16 kHz mono PCM from `AudioRecord` to Deepgram and exposes interim/final transcript state. Its API key is stored only through `SecretStore`; never add it to Git, Gradle properties, `BuildConfig`, or release assets.

### Security invariants — do not relax these

- **Host keys fail closed.** `SshRepository.PinningHostKeyVerifier` pins type + SHA-256 fingerprint from `HostProfile`. An unknown or changed key throws `SshProblem.HostKeyRequired` and the UI makes the user compare and trust it explicitly. Never auto-accept.
- **Public-key auth only.** No password auth, no arbitrary remote-command box in the UI.
- **Everything interpolated into a remote command goes through `TmuxParser.shellQuote`.** There is a test asserting it blocks injection.
- **Reader commands are allowlisted.** Prompt content must travel through SSH stdin and tmux buffer/paste operations, never inside a shell command.
- **`UpdateManager` verifies before installing:** HTTPS-only transport, declared size and SHA-256, package name, strictly newer versionCode, and the installed APK's signing certificate digest. Every one of those checks is load-bearing; a downgrade or a differently-signed APK must be rejected.

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
