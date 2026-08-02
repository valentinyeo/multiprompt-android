# multiprompt Android companion

A native Android app that connects directly to public SSH hosts and provides a
mobile reader for existing tmux sessions. An embedded live terminal remains
available as a fallback. The app does not require Tailscale or a second app.

## First-run flow

1. Open **Hosts** and add each VPS.
2. Import the OpenSSH private key used for that VPS. The app encrypts it with a
   non-exportable Android Keystore key before persistence.
3. Refresh once, compare the displayed SHA-256 host fingerprint with
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the server, then trust
   it. Changed host keys fail closed.
4. Tap a tmux session to read its recent output and send prompts. Use **Open
   live terminal** only when the reader actions are insufficient; the phone
   never changes the desktop tmux dimensions.

The app uses public-key authentication only. It does not accept an unverified
host key, expose an arbitrary remote-command box, or copy MP's Windows-local
credentials to Android. Prompt text reaches tmux through SSH stdin instead of
shell interpolation.

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
./gradlew.bat test assembleDebug
```

Open this repository directly in Android Studio if the SDK is not installed on
the command line.

## VPS-first development

The normal workflow does not compile Android code on the development machine.
Edit from any Linux VPS, commit, and push `main`:

```bash
git clone https://github.com/valentinyeo/multiprompt-android.git ~/projects/multiprompt-android
cd ~/projects/multiprompt-android
git push origin main
```

GitHub Actions runs lint and tests, builds and signs the APK, and replaces the
fixed `android-latest` release assets. The installed Android app then discovers
the new version from its **Update** screen. JDK, Gradle, and the Android SDK are
only needed for optional local builds, not for ordinary VPS development.

## Signing and the update channel

Release APKs must always use the same signing key. Create it once and keep two
backups:

```powershell
./scripts/create-signing-keystore.ps1 -ConfigureGitHub
```

The script writes only ignored local files and optionally configures these
GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The **Android companion release** workflow builds on relevant pushes to `main`
or a manual dispatch. It publishes two assets to the fixed `android-latest`
release:

- `multiprompt-companion.apk`
- `multiprompt-companion-update.json`

The installed app checks that fixed manifest every six hours and on demand. An
update is downloaded to private cache and rejected unless all of these match:

- HTTPS transport
- manifest size and SHA-256
- package name
- strictly newer version code
- the installed APK signing certificate

On Android 12 and newer the app requests a self-update without extra user
action. Android may still show its own confirmation. The first update requires
enabling **Allow from this source** once for multiprompt.

The initial installation must use the signed APK from `android-latest`; a debug
APK has a different package ID and certificate and cannot enter the release
update chain.
