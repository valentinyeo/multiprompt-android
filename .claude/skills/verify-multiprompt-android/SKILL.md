---
name: verify-multiprompt-android
description: Verify multiprompt Android's Jetpack Compose mobile UI on GitHub's emulator when proving SSH host setup, tmux sessions, reader actions, terminal fallback or sign-in entry.
---

# Verify multiprompt Android

Read [the feature index](features/README.md), then the selected recipe. Everything that builds or runs the app happens on GitHub, never on the local server. No local Gradle, Java, emulator or Actions imitation.

## Launch

From a pushed branch containing this workflow:

```bash
gh workflow run verify.yml -R valentinyeo/multiprompt-android --ref <branch> -f feature=session-reader.md
gh run list -R valentinyeo/multiprompt-android --workflow verify.yml --branch <branch> --limit 1
gh run watch <id> -R valentinyeo/multiprompt-android --interval 60 --exit-status
```

Poll GitHub at most once a minute. If dispatch says the workflow must exist on the default branch, report the exact error; do not merge it yourself.

The job uses JDK 17, Android SDK, Gradle 9.5.1 and `assembleDebug`. SDK/emulator downloads have three bounded attempts before emulator launch; `sdk-install.txt` retains each attempt, and an exhausted download fails the job rather than skipping the journey. It starts a fresh API 35 (minSdk 26, targetSdk 36) x86_64/google_apis emulator with KVM and animations disabled, installs the debug APK and starts `dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity`. Ready means `DOCTOR READY` in the job log, not just an emulator process. Each run has its own emulator, runner, keypair, SSH daemon and tmux server; never drive another run's device.

The ephemeral SSH server is the runner at `10.0.2.2:2222`, user `verifyssh`, session `verify-session`. Both user and host keys are generated in the job. Password authentication is disabled. The console prints `VERIFY_READY_528` and records input, empty Enter and Interrupt in a receipt file. This is a real SSH/tmux boundary, not a mock or an app setter.

## Doctor

The workflow's driver calls this read-only check after starting the activity, before driving it. On the GitHub runner only:

```bash
EXPECTED_VERSION="0.1.2-verify.$GITHUB_RUN_NUMBER-debug" EVIDENCE="$GITHUB_WORKSPACE/verification-evidence" .claude/skills/verify-multiprompt-android/scripts/doctor.sh
```

It requires boot completion, the debug package's expected build versionName, resumed MainActivity and a non-empty parsed UI XML containing the app package. It writes `package.txt`, `activity.txt` and `doctor.xml`. It never launches, grants permissions, imports keys or changes app state. Rerun it first when the UI appears wrong; a browser on top intentionally fails it.

## Drive

The workflow calls, on its fresh GitHub emulator only:

```bash
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
```

`EVIDENCE`, `FIXTURE` and `EXPECTED_VERSION` come from the workflow. Pass any filename in the feature index. `drive.sh` has small `tap_text`, `tap_desc`, `wait_for_text`, `dump_ui` and `shot` functions. It dumps `uiautomator` XML before locating an exact text or contentDescription and taps the matched clickable ancestor's bounds (or the exact enabled text node for native picker rows that do not expose a clickable ancestor) using `adb shell input`. Editable fields are found by class and their label/placeholder. Missing or ambiguous selectors fail instead of falling back to blind coordinates. Existing Compose accessibility text suffices; no test-only state setters or UI instrumentation are used.

All SSH journeys first capture Sign in, choose **Use without account (debug)**, open **Session list actions → Hosts → Add host**, fill the real form, open **Import private key**, and select the adb-pushed `verify-key.pem` through Android's real document picker. They compare the displayed `ssh-ed25519 SHA256:…` against the job-generated host fingerprint before choosing **Trust and connect**. An accepted SSH authentication before that action fails the driver.

## Evidence

Use the pstack proof standard: real user path; action plus resulting state; independently checked side effects; no test-only setters. A final screenshot alone is not proof. The reader recipe records the draft and Send, menu actions and resulting output, plus runner-side receipts and tmux capture. Host setup records the document picker/import, comparison dialog, authenticated session list and pinned identity. Terminal proof requires both attached status and a real tmux client. Sign-in proves only browser handoff, never OTP completion.

The driver uses `adb exec-out screencap -p`, `adb shell uiautomator dump` and `adb logcat -d`. The always-uploaded artifact is `verify-<feature>-<run-id>`; it contains numbered PNG/XML pairs, `driver-transcript.txt` (commands and driver exit), `doctor.xml`, package/activity metadata, `logcat.txt`, `revision.txt`, `build.txt`, SSH fingerprint/auth/log evidence and feature-specific receipts/captures. Private keys and APKs are outside the artifact and never uploaded.

```bash
gh run download <id> -R valentinyeo/multiprompt-android
```

LOOK at the downloaded PNGs, read the XML and receipts, and reconcile them with the transcript. A green job without that inspection is not verified. Report entry points or sub-features not exercised, including OTP, instead of treating the map as coverage. After teardown, download again or list the artifact to establish that cleanup preserved proof.

## Cleanup

The driver EXIT trap captures the final UI and logcat on both success and failure. The emulator-runner action ends the emulator it owns, including after driver failure. The workflow always invokes:

```bash
.claude/skills/verify-multiprompt-android/scripts/cleanup.sh
```

It copies SSH logs and console receipts into evidence, kills only the tmux and sshd PIDs recorded by fixture setup, removes the generated private/public key files and tries to remove the emulator's imported file. It never kills by process name. The fresh hosted runner's disposal removes the user's Keystore, imported app secrets and fixture user; evidence survives in the uploaded artifact. Do not cancel the job as a substitute for normal cleanup.

## Helpers

All helpers are executable. These setup/interaction commands are **GitHub-job-only**, with workflow environment variables set:

```bash
.claude/skills/verify-multiprompt-android/scripts/fixture.sh
.claude/skills/verify-multiprompt-android/scripts/doctor.sh
.claude/skills/verify-multiprompt-android/scripts/drive.sh hosts.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh sessions.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh terminal.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh sign-in.md
.claude/skills/verify-multiprompt-android/scripts/cleanup.sh
python3 .claude/skills/verify-multiprompt-android/scripts/ui.py "$EVIDENCE/current.xml" desc 'Session actions'
python3 -u .claude/skills/verify-multiprompt-android/scripts/console.py /home/verifyssh/receipts.txt
```

Fixture setup starts `console.py` inside tmux as the disposable fixture user; do not run a second copy. Each driver invocation needs a fresh emulator, not the state left by a previous driver.

Use `/maintain-verification-skill` when UI labels, entry points or behaviors change.
