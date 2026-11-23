---
name: verify-multiprompt-android
description: Verify multiprompt Android's Jetpack Compose mobile UI on GitHub's emulator when proving SSH host setup, tmux sessions, reader actions, terminal fallback, changed host identities, session settings or sign-in entry.
---

# Verify multiprompt Android

pstack: 0.15.44

Read [the feature index](features/README.md), then the selected recipe. Everything that builds or runs the app happens on GitHub, never on the local server. No local Gradle, Java, emulator or Actions imitation.

## The phone CLI

[scripts/mpa](scripts/mpa) is the one way agents drive the phone. Python 3, stdlib only, one JSON object per command, rich `--help` on every subcommand, and `--dry-run` on anything that changes state.

| Command | What it does |
|---|---|
| `mpa doctor --expected-version "$EXPECTED_VERSION"` | Read-only proof that this device is worth driving. |
| `mpa launch [--force-stop] [--wait-text Inbox]` | Start the app. |
| `mpa seed` | 40 inbox sessions, the long chat, the real-size restarted chat and the three chat shapes on the runner fixture. |
| `mpa tap text verify-session` | Resolve an enabled label through `journey_action`, scroll it into view, tap it. |
| `mpa read` | The UI tree as JSON. `--select` answers one selector; `--wait-text` and `--wait-absent` poll. Modes include `visible` (one row, fully on screen, `--extra enabled` for an actionable one, `--before` to prove a scroll revealed it), `current` (a `Current: ` row that must be disabled), `draft` (the composer text with every menu closed), `desc-absent` and `--inside LABEL` to scope any selector to one row. |
| `mpa fill 'Message verify-session' /cl` | Tap the field, clear it, type the value, read it back, dismiss the keyboard. A literal `%s` becomes a space. |
| `mpa hold text REPLY_BUBBLE_418` | Scroll a reachable label into view, then press and hold its center for 800 ms. |
| `mpa screenshot reader-sync` | Numbered PNG/XML pair in `$EVIDENCE`. |
| `mpa proof --name reader-sync --require-text 'Sign in to sync'` | Assert text and content descriptions, write `reader-sync.json` and a proof file. `--order A --order B` requires each row exactly once, fully on screen, in that top-to-bottom order. `--inline A B` requires both labels exactly once, fully on screen, on one line, with the first ending before the second starts. |
| `mpa key back` | One keyevent. |
| `mpa swipe [--inside LABEL] [--axis vertical] [--direction forward]` | Scroll the one visible container, or the container holding LABEL, by a fifth of its size. `--dry-run` prints the swipe. |
| `mpa terminal --step ready` | Read the fixture pane and assert the terminal fallback's shell: ready prompt, typed echo, real sleep, Ctrl+C, history or tab completion. Runner only. |
| `mpa cleanup --fixture "$FIXTURE"` | Stop the recorded PIDs, copy logs, keep the evidence. |

`--xml PATH` replays a captured dump instead of dumping the phone, which is how the CLI's own tests run without a device. A failure prints `{"ok": false, "error": "...", "message": "...", "next": "..."}` and exits 1; `next` names the command to run instead. `ui.py`, `doctor.sh`, `seed.sh` and `cleanup.sh` are thin wrappers over the same core, so a journey that has not moved yet keeps working.

`tools/mpa-cli.test.py` covers argument parsing, the JSON shape, `--dry-run` side effects, the selector modes and the error messages. It runs in `tools/check.sh source`.

Four journeys have moved end to end: `signed-out-sync.md`, `clear-ranking.md`, `message-reply.md` and `claude-remote-control.md` run only `mpa` commands for the phone. Six more phone checks now call only the CLI: the favorites dot, copy chat ID, inbox draft label, composer Stop/Send slot, native menu rows and the terminal shell steps. Every other journey still uses the `drive.sh` helpers below; migrate one journey per pull request. Host-side fixtures, receipts and tmux captures stay where they are: `mpa` drives the phone, not the host. Remaining loose drivers to fold in: `drive.sh` and its journey body, `fixture.sh`, and the per-journey `*-drive.sh`, `*-console.py`, `*-fixture.py` and `*-proof.py` scripts in [scripts](scripts).

## Choose proof for the change

[features/registry.json](features/registry.json) owns outcome IDs, recipe ownership, path-to-outcome rules, runner kinds and prerequisites. The generated index, picker catalog, driver eligibility and workflow choices all come from it. Run `node .github/scripts/pstack-verifier/coverage.cjs generate` when it changes; CI runs `check` and rejects drift.

The picker runs every registered debug or signed journey for the changed outcome IDs, recipes and owned drive scripts, so nothing for this PR waits for nightly. Registry runner.scripts records ownership of separate drivers. Signed candidates use the existing signing inputs on head.

For every product change, identify the changed outcome IDs. Add a new ID and journey for a new behavior. Code maps known files to outcomes before the picker is asked: a changed registry recipe maps to its journey's outcomes, an outcome's `match` tokens map changed source, and an outcome's `paths` map files, which is how the shared chat reader (`app/src/main/assets/chat_history_reader.py`, `tools/vendor/chat-reader/**`, `tools/sync-chat-reader.sh`) maps to its reader outcomes. Those IDs stand; the picker only adds guards on top. `["unmapped"]` stops the pick only when code mapped no outcome and real product source changed. The independent judge can add required IDs. An unknown product outcome fails closed. Tooling-only work can use the retained hosted Node test proof instead of claiming that an Android smoke test covered it.

The second-AI verdict may include `coverage` entries for every changed ID whose journey is required in this PR. Each entry names its registered journey and quotes a real action from `driver-transcript.txt`, a resulting XML/JSON state, and independent receipt/server evidence where required. The gate checks these references in the downloaded head artifact after cleanup, requires a successful matching journey and exact `app-source-sha.txt`, then retains `verifier-feature-coverage`. Free-text `not_covered`, a green unrelated journey or smoke cannot satisfy this contract, and a coverage gap is a note, never a failed check. The check fails only on a head journey that did not pass and is not already red in the latest nightly run on main. A journey red in the latest nightly run on main never blocks a PR; the verify chat files a ticket for it.
A bug-fix pull request (its title or ticket says fix or bug, or the picker sets change_kind to bug) is judged like any other: every picked journey must pass on head.

Verification checklist item 7 is the break-it pass, which runs nightly in `android-full.yml` and never in a pull request: the same emulator and APK replay each journey after the normal one passes. The shared session-list readiness event now runs the isolated stress chat described in `tools/android-break-it/README.md` before the selected driver, leaving the network throttled throughout its original assertions. This replaces text-entry-triggered stress; the three journeys without chat access declare only their reachable screen checkpoints in `break-it.cjs`. `tools/android-break-it/drive.sh` rotates during an exact non-empty draft, backgrounds and returns, switches to another fixture chat and back, then applies 128000 bit/s upload/download bandwidth and 150–300ms latency for the remaining journey. Its shared streaming-send checkpoint checks independent send-admission and stream-completion timestamps. Each checkpoint retains PNG, XML and text, and the judge reads them in the verdict evidence.

The replay has a 20-minute limit, and a failing replay fails its nightly journey. Tooling-only changes do not claim an Android repair; product source cannot use the tooling classification.

`features/coverage-baseline.json` lists the initial uncovered IDs, including dedicated and manual routes outside the PR picker and historical signed gaps now driven as candidates. The baseline is an inventory, not permission to change those features without proof. New uncovered IDs fail the weekly ratchet even if another gap disappears. Keep dedicated workflows and account prerequisites; never make them debug recipes just to obtain a green verdict.

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

Pull requests run no control-map check, so app screen edits wait only on their touched-feature journeys. `maintain-verification.yml` compares the committed map with the live inventory weekly, against its base, and reports new gaps in its evidence and correction PR; that run also executes these tool tests with `CONTROL_MAP_LIVE=1`, non-blocking. New controls still need journeys before that run reports clean. `tools/check.sh source` runs the tool tests; the two that read the live inventory skip unless `CONTROL_MAP_LIVE=1` is set. The historical test reads the actual parent and merged source of [PR #380](https://github.com/valentinyeo/multiprompt-android/pull/380), commit `50eb80bbcc560ee2813d0eb869ab2364663275a8`, and rejects its previously unmapped chat-search controls.

## Weekly maintenance

`maintain-verification.yml` runs on Mondays at 05:17 UTC and supports `workflow_dispatch`. Its `scripts/maintain.py` planner reads every journey from the registry. Debug and signed journeys reuse `verify.yml`, two at a time, with the existing seeded SSH fixture. Signed routes build branch candidates; dedicated update, working-dots and photo routes retain their own proofs. Manual prerequisites remain explicit limitations, not failures or live coverage. Unsupported future routes block the run.

The source pass checks the registry/index and retains `control_map.py check` before and after `locations`. A following non-blocking step reruns both ratchets against the newest commit older than seven days, never older than the control map's first commit, and keeps `ratchet-registry.txt`, `ratchet-controls.txt` and `ratchet-result.txt`, so new gaps surface as a red step without blocking the run. Only moved locations are repaired automatically; changed controls, choice digests and product failures still need review. The final job reads all paginated job conclusions, including missing or skipped journeys, and opens or updates one `maintain/<UTC-date>` PR with corrections and a failure report. It uses `GITHUB_TOKEN` with contents and pull-request write permissions; repository Actions settings must allow PR creation. It never merges. A clean automated pass exits zero and opens no PR. Source drift, failed automated journeys or unsupported routes leave the run red even after a correction PR opens.

```bash
gh workflow run maintain-verification.yml -R valentinyeo/multiprompt-android --ref <branch>
gh workflow run maintain-verification.yml -R valentinyeo/multiprompt-android --ref <branch> -f dry_run=true
gh workflow run emulator-stability.yml -R valentinyeo/multiprompt-android --ref <branch> -f feature_map=true -f dry_run=true
python3 -B tools/maintain-verification.test.py
```

`dry_run=true` executes planner/report regression tests, the real source checks and the report without driving Android or creating/updating a PR. The artifacts include the complete plan, test output, before/after checks and `publication.json`; planned journeys are explicitly not live coverage. Source drift still exits non-zero. The normal weekly run always drives automated journeys.

The `emulator-stability.yml` dispatch option is an equivalent entry point for testing a new workflow before it exists on the default branch; it does not run the stability series.

`maintenance-map` retains source diagnostics and corrected locations; `maintenance-report` retains the paginated job list and report. Each journey retains its usual action/result artifacts. Review those before accepting map changes. A global concurrency group cancels superseded maintenance runs so two runs cannot write the same dated branch.

## Launch

`pstack-verifier.yml` owns pull-request journeys. It runs debug and signed candidate journeys for changed registry outcomes, recipes and owned drivers on head through `verify.yml`, with no parent matrix. The one `pstack-verifier` check fails only when picking failed or a head journey did not pass; the independent judge posts an advisory comment beside that result.

`verify.yml` supports calls and manual dispatch. The weekly `maintain-verification.yml` pass drives every registered automated feature. `android-full.yml` runs every PR-capable registry journey, plus all six phone regressions, release-profile compilation and historical controls nightly. Dedicated top-fade, account, update and other periodic workflows retain their schedules without PR triggers. `release.yml` still blocks publication on the recorded robot-faces journey against the exact signed APK.

From a pushed branch containing this workflow:

```bash
gh workflow run verify.yml -R valentinyeo/multiprompt-android --ref <branch> -f feature=session-reader.md
gh run list -R valentinyeo/multiprompt-android --workflow verify.yml --branch <branch> --limit 1
gh run watch <id> -R valentinyeo/multiprompt-android --interval 60 --exit-status
```

Poll GitHub at most once a minute. If dispatch says the workflow must exist on the default branch, report the exact error; do not merge it yourself.

The job uses JDK 17, Android SDK, Gradle 9.5.1 and `assembleDebug`. SDK/emulator downloads have three bounded attempts before emulator launch; `sdk-install.txt` retains each attempt, and an exhausted download fails the job rather than skipping the journey. It starts a fresh API 35 (minSdk 26, targetSdk 36) x86_64/google_apis emulator with KVM and animations disabled, installs the debug APK and starts `dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity`. Ready means `DOCTOR READY` in the job log, not just an emulator process. Each run has its own emulator, runner, keypair, SSH daemon and tmux server; never drive another run's device.

The ephemeral SSH server is the runner at `10.0.2.2:2222`, user `verifyssh`, target `verify-session`; normal debug jobs seed 40 additional inbox sessions, `cl-long-chat` with 1,882 transcript rows (>18 MiB), the compacted, fresh-started and resumed chat shapes once, and `live-real-2781` once. Dedicated long-chat and signed pins proofs retain their own fixtures. Both user and host keys are generated in the job. Password authentication is disabled. The console prints `VERIFY_READY_528` and records input, empty Enter and Interrupt in a receipt file. This is a real SSH/tmux boundary, not a mock or an app setter.

`live-real-2781` is the shared real-size chat: `tools/real-chat/fixture.py` writes a synthetic Claude transcript of about 3 MB and roughly 4,000 rows, 70% of them tool and tool_result rows with large detail fields, with assistant replies interleaved. It restarts three times (three `init` events, two handover files, three session ids), keeps accepted and delivered dispatch events, and leaves two stale transcripts and a neighbouring chat in the same project folder. Its text is synthetic only. `mpa seed` creates the tmux session and its `@mp_host_sid`, and `seed-counts.json` records the chat id, the transcript bytes and rows, the markers and the older cached position.

## Doctor

The workflow's driver calls this read-only check after starting the activity, before driving it. On the GitHub runner only:

```bash
EXPECTED_VERSION="0.1.2-verify.$GITHUB_RUN_NUMBER-debug" EVIDENCE="$GITHUB_WORKSPACE/verification-evidence" .claude/skills/verify-multiprompt-android/scripts/mpa doctor
```

It requires boot completion, the debug package's expected build versionName, resumed MainActivity and a non-empty parsed UI XML containing the app package. It writes `package.txt`, `activity.txt` and `doctor.xml`. It never launches, grants permissions, imports keys or changes app state. Rerun it first when the UI appears wrong; a browser on top intentionally fails it.

## Drive

Prove desktop-hosted chat by loading skills and returning to the live view through the desktop host; a retry or a clearer error is a failure.

The workflow calls, on its fresh GitHub emulator only:

```bash
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-routing.md
```

`EVIDENCE`, `FIXTURE` and `EXPECTED_VERSION` come from the workflow. Pass any filename in the feature index. `drive.sh` boots the app, runs doctor, seeds the host and owns the evidence trap; the journey itself drives the phone with `mpa` (`mpa tap text …`, `mpa read --wait-text …`, `mpa screenshot …`, `mpa proof …`). Journeys that have not moved yet still call the `drive.sh` helpers `tap_text`, `tap_desc`, `wait_for_text`, `dump_ui` and `shot`. Never use a raw coordinate tap. Their shared `journey_action.py` planner resolves the enabled label and clickable ancestor in the active window, requires the complete clickable bounds inside the window and ancestor scroll viewports, and rejects tap centers covered by foreground controls (drawing order, or accessibility sibling order when absent). The existing native terminal-focus action checks both toolbar/key-row anchors and the typing point between them. It chooses the clipped target's vertical or horizontal scroll ancestor, redumps after every swipe and taps only after re-resolution. A missing lazy label searches the unique active vertical container forward then backward, bounded to 18 gestures; ambiguous containers fail rather than scrolling the background or key row. Failures name the label with `JOURNEY_ACTION_UNREACHABLE`. Native picker rows without a clickable ancestor use the enabled label bounds. `tools/journey-actions/check.py` blocks new bypasses; its baseline lists old raw taps for cleanup. Observation selectors such as `contains` do not prove reachability. Update the affected recipe alongside changed interactions, then rerun that journey with its existing resulting-state and independent receipt assertions. Editable fields are found by class and their label/placeholder. Missing or ambiguous selectors fail instead of falling back to blind coordinates. While waiting, a system **isn't responding** dialog from the slow emulator is answered with **Wait** and logged in `system-anr.txt`; before launch the driver sets the emulator's `hide_error_dialogs` so other apps' ANR dialogs cannot cover the app (an app crash still fails doctor and shows in `logcat.txt`), and doctor runs at most three times, each failure logged in `doctor-retries.txt`. After doctor it waits up to 60 s for the emulator to reach `10.0.2.2` (logged in `network-wait.txt`) and fails as blocked otherwise. A single **Trust and connect** tap must reach `verify-session`; failure is logged in `trust-retries.txt`, captured as `trust-retry-1`, and fails the journey without retrying. Existing Compose accessibility text suffices; no test-only state setters or UI instrumentation are used.

Every journey requires the exact `EXPECTED_VERSION` text and `Version <version>, open release notes` description immediately after doctor, saving `version-label.txt` (no added `v` prefix). On Sign in this label is at the bottom, not beside a menu.

All SSH journeys first capture Sign in, choose **Use without account (debug)**, open **Session list actions → Hosts → Add host**, fill the real form, open **Import private key**, and select the adb-pushed `verify-key.pem` through Android's real document picker. They compare the displayed `ssh-ed25519 SHA256:…` against the job-generated host fingerprint before choosing **Trust and connect**. An accepted SSH authentication before that action fails the driver. `hosts-retired.md` is the exception: it saves a host at the retired address `45.151.123.11` (blocked on the runner with iptables), force-stops and relaunches, and requires the host card and any "unreachable" banner to be gone; it never trusts or connects.

`host-changed-key.md` closes the recorded fixture sshd and its owned authenticated child connections before rotating the host key. Replacing only the listener leaves pooled SSH connections authenticated against the old key. The journey then checks **Session list actions → Refresh → Not now**, repeat refresh and force-stop/relaunch. It requires the new fingerprint, unchanged original pin and no further authentication. Session list settings ([session-settings.md](features/session-settings.md)) is mapped but not dispatchable: saving a split switch needs account OTP and a pulled layout, which CI cannot reach.

The reaction fixture consumes the prompt and its trailing delivery marker as one transcript turn. A marker is not a second user message or an extra assistant reply. `tools/kit-controls/journeys.test.py` checks this framing and the SSH rotation ownership. Workspace split actions resolve the active horizontal viewport and search both directions; do not wait for an offscreen label before revealing it.

## Reader lifetime

For changes to connection ownership, dispatch `chat-codex.md` and cover `reader.lifecycle`. Its SSH observer counts actual history queries and snapshot captures for Hax and Claude while visible, backgrounded, resumed and switched away. A missing positive control or extra hidden request fails. Retain the raw host log, count files, resumed XML and the receipt after closing the second reader. JVM tests cover multiple observers of the same session and silent reconnects; they do not replace the phone journey.

## Real-size chat proof

Every chat journey listed in `scripts/real-chat-screen.sh` runs the same shared step before its own body: `real-chat-screen.sh` seeds an older phone cache position from the app's own restored cache file, relaunches the app, opens `live-real-2781` from Inbox, and asserts on the rendered UI dump. It requires the newest assistant reply and the newest row after open, forbids the stale pre-restart reply and the neighbouring chat, requires a user row, an assistant reply and the next user row in that top-to-bottom order after scrolling back, and requires the newest reply again after returning to newest. Each check keeps a numbered PNG/XML pair and `real-chat-screen.txt`.

A server frame, a reader reply or a host receipt never counts as chat screen proof: the assertion is the phone's own text. On a signed package the cache seed is skipped and recorded as `REAL_CHAT_CACHE_SKIPPED`, because `run-as` needs a debuggable app; the rendered checks still run. A journey that cannot show the newest assistant reply fails as a product assertion and must not be weakened.

## Evidence
Normal debug journeys check `seed-counts.json` against live tmux names and transcript bytes/rows before any action; missing, undersized or mismatched seeds fail setup. `sessions.md` records a runtime-selected reader and its exact one-result search in `seed-search.xml`; it does not assert that all 45 rows render. Find targets by exact name rather than assuming a single row.

Use the pstack proof standard: real user path; action plus resulting state; independently checked side effects; no test-only setters. A final screenshot alone is not proof. The reader recipe records the draft and Send, menu actions and resulting output, plus runner-side receipts and tmux capture. Host setup records the document picker/import, comparison dialog, authenticated session list and pinned identity. Terminal proof requires both attached status and a real tmux client. Split settings cannot be proven by debug offline entry. Sign-in proves only browser handoff, never OTP completion.

The driver uses `adb exec-out screencap -p`, `adb shell uiautomator dump` and `adb logcat -d`. The always-uploaded artifact is `verify-<feature>-<run-id>`; it contains numbered PNG/XML pairs, `driver-transcript.txt` (commands and driver exit), `doctor.xml`, package/activity metadata, `logcat.txt`, `revision.txt`, `build.txt`, visible version evidence, SSH fingerprint/auth/log evidence and feature-specific receipts/captures. Private keys and APKs are outside the artifact and never uploaded.

```bash
gh run download <id> -R valentinyeo/multiprompt-android
```

LOOK at the downloaded PNGs, read the XML and receipts, and reconcile them with the transcript. A green job without that inspection is not verified. Report entry points or sub-features not exercised, including OTP, instead of treating the map as coverage. After teardown, download again or list the artifact to establish that cleanup preserved proof.

## Cleanup

The driver EXIT trap captures the final UI and logcat on both success and failure. The emulator-runner action ends the emulator it owns, including after driver failure. The workflow always invokes:

```bash
.claude/skills/verify-multiprompt-android/scripts/mpa cleanup --fixture "$FIXTURE"
```

It copies SSH logs and console receipts into evidence, kills only the tmux and sshd PIDs recorded by fixture setup, removes the generated private/public key files (including the rotated host key) and tries to remove the emulator's imported file. It never kills by process name. The fresh hosted runner's disposal removes the user's Keystore, imported app secrets and fixture user; evidence survives in the uploaded artifact. Do not cancel the job as a substitute for normal cleanup.

## Helpers

All helpers are executable. These commands are **GitHub-job-only**, with workflow environment variables set:

```bash
.claude/skills/verify-multiprompt-android/scripts/fixture.sh
.claude/skills/verify-multiprompt-android/scripts/mpa doctor --expected-version "$EXPECTED_VERSION"
.claude/skills/verify-multiprompt-android/scripts/mpa read --select desc --value 'Session actions'
.claude/skills/verify-multiprompt-android/scripts/mpa screenshot reader-menu
.claude/skills/verify-multiprompt-android/scripts/mpa cleanup --fixture "$FIXTURE"
.claude/skills/verify-multiprompt-android/scripts/drive.sh signed-out-sync.md
.claude/skills/verify-multiprompt-android/scripts/drive.sh session-reader.md
sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/real-chat-phone-cache.py prepare --prefs "$EVIDENCE/hosts-prefs.xml" --fixture /home/verifyssh/real-chat-fixture.py --home /home/verifyssh --out-dir /home/verifyssh/real-chat-cache-2781 --session live-real-2781
python3 -u .claude/skills/verify-multiprompt-android/scripts/console.py /home/verifyssh/receipts.txt
```

`drive.sh <feature>.md` still runs the boot, doctor, host seed and evidence trap for the journeys that have not moved to `mpa` yet. Every other invocation above is an `mpa` subcommand; `ui.py`, `doctor.sh`, `seed.sh` and `cleanup.sh` remain only as wrappers for those journeys. `real-chat-screen.sh` is sourced by `drive.sh`, not run directly; it owns the chat journeys' real-size screen step.

Fixture setup starts `console.py` inside tmux as the disposable fixture user; do not run a second copy. Ordinary driver invocations need a fresh emulator. The break-it replay alone reuses its normal journey's boot after replacing the disposable fixture, clearing app data and reinstalling the identical APK with its original permission grants.

Use `/maintain-verification-skill` when UI labels, entry points or behaviors change.
