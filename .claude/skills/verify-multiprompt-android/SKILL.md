---
name: verify-multiprompt-android
description: Verify multiprompt Android's Jetpack Compose mobile UI on GitHub's emulator when proving SSH host setup, tmux sessions, reader actions, terminal fallback, changed host identities, session settings or sign-in entry.
---

# Verify multiprompt Android

Read [the feature index](features/README.md), then the selected recipe. Everything that builds or runs the app happens on GitHub, never on the local server. No local Gradle, Java, emulator or Actions imitation.

## Choose proof for the change

[features/registry.json](features/registry.json) owns outcome IDs, recipe ownership, runner kinds and prerequisites. The generated index, picker catalog, driver eligibility and workflow choices all come from it. Run `node .github/scripts/pstack-verifier/coverage.cjs generate` when it changes; CI runs `check` and rejects drift.

The picker schedules every registered debug journey required by changed outcome IDs. The three-journey limit applies only to optional model suggestions, not required coverage.

For every product change, identify the changed outcome IDs. Add a new ID and journey for a new behavior. The picker reports `changed_outcomes`; deterministic source matches and the independent judge can add required IDs. An unknown product outcome fails closed. Tooling-only work can use the retained hosted Node test proof instead of claiming that an Android smoke test covered it.

The second-AI verdict must include `coverage` entries for every changed ID. Each entry names its registered journey and quotes a real action from `driver-transcript.txt`, a resulting XML/JSON state, and independent receipt/server evidence where required. The gate checks these references in the downloaded head artifact after cleanup, requires a successful matching journey and exact `app-source-sha.txt`, then retains `verifier-feature-coverage`. Free-text `not_covered`, a green unrelated journey or smoke cannot satisfy this contract. For a feature, both sides may pass; a manufactured failing parent is not required.
A bug-fix pull request (its title or ticket says fix or bug, or the picker sets change_kind to bug) fails unless every picked journey fails on the parent at a product assertion and passes on head.
The driver records that failure as the line PRODUCT_ASSERTION_FAILED; a parent that fails in setup, build or fixture is not a repro.

Verification checklist item 7 is the break-it pass. The same bug classification schedules a second head replay after the normal journey passes, on the same emulator and APK. `tools/android-break-it/drive.sh` rotates during the first non-empty chat draft, backgrounds and returns, switches to another fixture chat and back, then applies 128000 bit/s upload/download bandwidth and 150–300ms latency for the remaining journey. Each checkpoint retains PNG, XML and text. The recipe must call `break_it_stream_send` with independently checked send-admission and stream-completion timestamps; `ai-switch.md` supplies this checkpoint. Other recipes must add an equivalent checkpoint before they can pass as bug-fix proof. Missing evidence fails closed.

The replay has a 20-minute limit. The verifier checks all five retained checkpoints, original journey completion, exact source SHA and the same APK hash, then cites `break-it/*.txt` in its verdict. Tooling-only changes do not claim an Android repair; product source cannot use the tooling classification.

`features/coverage-baseline.json` lists the initial uncovered IDs, including dedicated, signed and manual routes outside the debug picker. The baseline is an inventory, not permission to change those features without proof. New uncovered IDs fail even if another gap disappears. Keep dedicated workflows and account prerequisites; never make them debug recipes just to obtain a green verdict.

## Design-kit controls

For button or menu migrations, run `tools/check.sh lint` on GitHub. It checks resolved Material symbols in debug, release and m3, including aliases and fully qualified calls. `KitControlDetectorTest` rejects the exact dialog removed in #136 (`c58a41b6`) and menu removed in #132 (`0cc8e05e`). `tools/kit-controls/history.test.py` verifies their provenance and forbids new baseline allowances. The shipping inventory is empty; diagnostic debug sources and tests are explicit exclusions. The hosted `kit-controls-proof` artifact also retains rejection of a new aliased dialog, qualified dialog and menu against the real Material dependency, not only test stubs. Only `KitSheet` in `DesignKit.kt` may call Material `ModalBottomSheet`.

Do not count kit-component tests as screen proof. `DesignKitTest.deviceApprovalScreenUsesKitButtonPixelsAndActions` captures the actual approval screen's button fills and checks both callbacks. `DeviceApprovalPromptTest` checks busy, error and explicit-choice behavior. These are hosted screen regressions, not live account approval proof.

Dispatch `hosts.md` and `host-changed-key.md` for trust-sheet changes. Inspect the fingerprint, Trust and connect, Not now and independent SSH receipts. Dispatch `sign-in.md` for the Sign in button. Dispatch `device-approval.md` for Approve/No. It uses the real browser callback and PKCE exchange against a local account fixture, then verifies both device keys with independent SSH clients. It does not prove production email OTP; keep that limitation visible. Run the existing reader/menu journeys for reader button changes. New kit components or colours still require the design approval in `CLAUDE.md`.
## Android control inventory

[features/controls.json](features/controls.json) lists screens and individual controls from `app/src/main`, with stable IDs, plain labels and source `file:line` citations. Each control names an existing automated journey and its action/result, or has `journey: null` with a missing reason. [features/control-gaps.json](features/control-gaps.json) records the initial missing controls. This complements the outcome registry; it does not change the shared verifier.

Run these source-only checks locally or on GitHub. They do not launch Android:

```bash
python3 -B .claude/skills/verify-multiprompt-android/scripts/control_map.py check
python3 -B .claude/skills/verify-multiprompt-android/scripts/control_map.py report
python3 -B .claude/skills/verify-multiprompt-android/scripts/control_map.py inventory
python3 -B .claude/skills/verify-multiprompt-android/scripts/control_map.test.py
```

`check` prints `N of M controls have a live journey` and every missing control. The number measures mapped automated recipes, not successful runs at the current APK SHA. It excludes manual recipes and does not imply production accounts, speech recognition or physical-phone coverage. This inventory-only pass adds no journeys and makes no new live-app claim.

Count one source-defined control variant, including conditional and disabled sheet items, text fields, keyboard actions, notifications and gestures. Enumerated choices have separate IDs. User-supplied rows, such as saved snippets, sessions and discovered Pi models, have one template per action. Shared components list every screen context. Separate source sites for signed/debug and layout variants remain separate. `forwarding` records shared implementations and their counted callers, rather than counting the same button twice. `screens` is a navigation index, not an additional coverage denominator.

The scanner checks Kotlin calls and modifiers across `app/src/main`. It rejects added or changed interaction sites, stale locations and invalid journey references. Audited UI-file and external-enum digests also reject choice-list changes that leave a loop's button call unchanged. Those broad checks intentionally require source review for non-control edits too. After reviewing a changed file, update its mapped sites, finite options and digest. Never replace that review with an automatic map or baseline rewrite. Keep IDs when moving controls. For line-only movement, run:

```bash
python3 -B .claude/skills/verify-multiprompt-android/scripts/control_map.py locations
```

On pull requests, CI runs `check --base <PR-base-sha>` and forbids growing the initial gap list, even when an older gap disappears. New controls require journeys. CI retains `android-control-coverage` with the count and missing list. `tools/check.sh source` runs the same check and its tests. The historical test reads the actual parent and merged source of [PR #380](https://github.com/valentinyeo/multiprompt-android/pull/380), commit `50eb80bbcc560ee2813d0eb869ab2364663275a8`, and rejects its previously unmapped chat-search controls.

## Launch

`pstack-verifier.yml` owns pull-request journeys. It picks at most three and runs each on head and parent through `verify.yml`; each pair counts as one journey. `verify.yml` also runs on main pushes and nightly, and supports manual dispatch. Tooling-only work can use the retained hosted Node test proof.

From a pushed branch containing this workflow:

```bash
gh workflow run verify.yml -R valentinyeo/multiprompt-android --ref <branch> -f feature=session-reader.md
gh run list -R valentinyeo/multiprompt-android --workflow verify.yml --branch <branch> --limit 1
gh run watch <id> -R valentinyeo/multiprompt-android --interval 60 --exit-status
```

Poll GitHub at most once a minute. If dispatch says the workflow must exist on the default branch, report the exact error; do not merge it yourself.

The job uses JDK 17, Android SDK, Gradle 9.5.1 and `assembleDebug`. SDK/emulator downloads have three bounded attempts before emulator launch; `sdk-install.txt` retains each attempt, and an exhausted download fails the job rather than skipping the journey. It starts a fresh API 35 (minSdk 26, targetSdk 36) x86_64/google_apis emulator with KVM and animations disabled, installs the debug APK and starts `dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity`. Ready means `DOCTOR READY` in the job log, not just an emulator process. Each run has its own emulator, runner, keypair, SSH daemon and tmux server; never drive another run's device.

The ephemeral SSH server is the runner at `10.0.2.2:2222`, user `verifyssh`, target `verify-session`; normal debug jobs seed 40 additional inbox sessions and `cl-long-chat` with 1,882 transcript rows (>18 MiB) once. Dedicated long-chat and signed pins proofs retain their own fixtures. Both user and host keys are generated in the job. Password authentication is disabled. The console prints `VERIFY_READY_528` and records input, empty Enter and Interrupt in a receipt file. This is a real SSH/tmux boundary, not a mock or an app setter.

## Doctor

The workflow's driver calls this read-only check after starting the activity, before driving it. On the GitHub runner only:

```bash
EXPECTED_VERSION="0.1.2-verify.$GITHUB_RUN_NUMBER-debug" EVIDENCE="$GITHUB_WORKSPACE/verification-evidence" .claude/skills/verify-multiprompt-android/scripts/doctor.sh
```

It requires boot completion, the debug package's expected build versionName, resumed MainActivity and a non-empty parsed UI XML containing the app package. It writes `package.txt`, `activity.txt` and `doctor.xml`. It never launches, grants permissions, imports keys or changes app state. Rerun it first when the UI appears wrong; a browser on top intentionally fails it.

## Drive

Prove desktop-hosted chat by loading skills and returning to the live view through the desktop host; a retry or a clearer error is a failure.

The workflow calls, on its fresh GitHub emulator only:

```bash
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-routing.md
```

`EVIDENCE`, `FIXTURE` and `EXPECTED_VERSION` come from the workflow. Pass any filename in the feature index. `drive.sh` has small `tap_text`, `tap_desc`, `wait_for_text`, `dump_ui` and `shot` functions. Use `tap_text`, `tap_desc` or `tap_node` for new actions, never a raw coordinate tap. Their shared `journey_action.py` planner resolves the enabled label and clickable ancestor in the active window, requires the complete clickable bounds inside the window and ancestor scroll viewports, and rejects tap centers covered by foreground controls (drawing order, or accessibility sibling order when absent). The existing native terminal-focus action checks both toolbar/key-row anchors and the typing point between them. It chooses the clipped target's vertical or horizontal scroll ancestor, redumps after every swipe and taps only after re-resolution. A missing lazy label searches the unique active vertical container forward then backward, bounded to 18 gestures; ambiguous containers fail rather than scrolling the background or key row. Failures name the label with `JOURNEY_ACTION_UNREACHABLE`. Native picker rows without a clickable ancestor use the enabled label bounds. `tools/journey-actions/check.py` blocks new bypasses; its baseline lists old raw taps for cleanup. Observation selectors such as `contains` do not prove reachability. Update the affected recipe alongside changed interactions, then rerun that journey with its existing resulting-state and independent receipt assertions. Editable fields are found by class and their label/placeholder. Missing or ambiguous selectors fail instead of falling back to blind coordinates. While waiting, a system **isn't responding** dialog from the slow emulator is answered with **Wait** and logged in `system-anr.txt`; before launch the driver sets the emulator's `hide_error_dialogs` so other apps' ANR dialogs cannot cover the app (an app crash still fails doctor and shows in `logcat.txt`), and doctor runs at most three times, each failure logged in `doctor-retries.txt`. After doctor it waits up to 60 s for the emulator to reach `10.0.2.2` (logged in `network-wait.txt`) and fails as blocked otherwise. A single **Trust and connect** tap must reach `verify-session`; failure is logged in `trust-retries.txt`, captured as `trust-retry-1`, and fails the journey without retrying. Existing Compose accessibility text suffices; no test-only state setters or UI instrumentation are used.

Every journey requires the exact `EXPECTED_VERSION` text and `Version <version>, open release notes` description immediately after doctor, saving `version-label.txt` (no added `v` prefix). On Sign in this label is at the bottom, not beside a menu.

All SSH journeys first capture Sign in, choose **Use without account (debug)**, open **Session list actions → Hosts → Add host**, fill the real form, open **Import private key**, and select the adb-pushed `verify-key.pem` through Android's real document picker. They compare the displayed `ssh-ed25519 SHA256:…` against the job-generated host fingerprint before choosing **Trust and connect**. An accepted SSH authentication before that action fails the driver. `hosts-retired.md` is the exception: it saves a host at the retired address `45.151.123.11` (blocked on the runner with iptables), force-stops and relaunches, and requires the host card and any "unreachable" banner to be gone; it never trusts or connects.

`host-changed-key.md` closes the recorded fixture sshd and its owned authenticated child connections before rotating the host key. Replacing only the listener leaves pooled SSH connections authenticated against the old key. The journey then checks **Session list actions → Refresh → Not now**, repeat refresh and force-stop/relaunch. It requires the new fingerprint, unchanged original pin and no further authentication. Session list settings ([session-settings.md](features/session-settings.md)) is mapped but not dispatchable: saving a split switch needs account OTP and a pulled layout, which CI cannot reach.

The reaction fixture consumes the prompt and its trailing delivery marker as one transcript turn. A marker is not a second user message or an extra assistant reply. `tools/kit-controls/journeys.test.py` checks this framing and the SSH rotation ownership. Workspace split actions resolve the active horizontal viewport and search both directions; do not wait for an offscreen label before revealing it.

## Reader lifetime

For changes to connection ownership, dispatch `chat-codex.md` and cover `reader.lifecycle`. Its SSH observer counts actual history queries and snapshot captures for Hax and Claude while visible, backgrounded, resumed and switched away. A missing positive control or extra hidden request fails. Retain the raw host log, count files, resumed XML and the receipt after closing the second reader. JVM tests cover multiple observers of the same session and silent reconnects; they do not replace the phone journey.

## Evidence
Normal debug journeys check `seed-counts.json` against live tmux names and transcript bytes/rows before any action; missing, undersized or mismatched seeds fail setup. `sessions.md` records a runtime-selected reader and its exact one-result search in `seed-search.xml`; it does not assert that all 42 rows render. Find targets by exact name rather than assuming a single row.

Use the pstack proof standard: real user path; action plus resulting state; independently checked side effects; no test-only setters. A final screenshot alone is not proof. The reader recipe records the draft and Send, menu actions and resulting output, plus runner-side receipts and tmux capture. Host setup records the document picker/import, comparison dialog, authenticated session list and pinned identity. Terminal proof requires both attached status and a real tmux client. Split settings cannot be proven by debug offline entry. Sign-in proves only browser handoff, never OTP completion.

The driver uses `adb exec-out screencap -p`, `adb shell uiautomator dump` and `adb logcat -d`. The always-uploaded artifact is `verify-<feature>-<run-id>`; it contains numbered PNG/XML pairs, `driver-transcript.txt` (commands and driver exit), `doctor.xml`, package/activity metadata, `logcat.txt`, `revision.txt`, `build.txt`, visible version evidence, SSH fingerprint/auth/log evidence and feature-specific receipts/captures. Private keys and APKs are outside the artifact and never uploaded.

```bash
gh run download <id> -R valentinyeo/multiprompt-android
```

LOOK at the downloaded PNGs, read the XML and receipts, and reconcile them with the transcript. A green job without that inspection is not verified. Report entry points or sub-features not exercised, including OTP, instead of treating the map as coverage. After teardown, download again or list the artifact to establish that cleanup preserved proof.

## Cleanup

The driver EXIT trap captures the final UI and logcat on both success and failure. The emulator-runner action ends the emulator it owns, including after driver failure. The workflow always invokes:

```bash
.claude/skills/verify-multiprompt-android/scripts/cleanup.sh
```

It copies SSH logs and console receipts into evidence, kills only the tmux and sshd PIDs recorded by fixture setup, removes the generated private/public key files (including the rotated host key) and tries to remove the emulator's imported file. It never kills by process name. The fresh hosted runner's disposal removes the user's Keystore, imported app secrets and fixture user; evidence survives in the uploaded artifact. Do not cancel the job as a substitute for normal cleanup.

## Helpers

All helpers are executable. These setup/interaction commands are **GitHub-job-only**, with workflow environment variables set:

```bash
.claude/skills/verify-multiprompt-android/scripts/fixture.sh
.claude/skills/verify-multiprompt-android/scripts/doctor.sh
.claude/skills/verify-multiprompt-android/scripts/drive.sh hosts.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh hosts-retired.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh host-changed-key.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh sessions.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-drafts.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-favorite-dot.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh copy-chat-id.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-routing.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh chat-choices.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh terminal.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh sign-in.md
.claude/skills/verify-multiprompt-android/scripts/cleanup.sh
python3 .claude/skills/verify-multiprompt-android/scripts/ui.py "$EVIDENCE/current.xml" desc 'Session actions'
python3 -u .claude/skills/verify-multiprompt-android/scripts/console.py /home/verifyssh/receipts.txt
```

Fixture setup starts `console.py` inside tmux as the disposable fixture user; do not run a second copy. Ordinary driver invocations need a fresh emulator. The break-it replay alone reuses its normal journey's boot after replacing the disposable fixture, clearing app data and reinstalling the identical APK with its original permission grants.

Use `/maintain-verification-skill` when UI labels, entry points or behaviors change.
