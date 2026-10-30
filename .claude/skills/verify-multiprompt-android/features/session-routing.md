# Session switching and send destinations

Run `verify.yml` with `feature=session-routing.md` on GitHub Actions. The SSH server, emulator, user, home, tmux server and sessions are disposable fixtures. The fixture records SSH commands, terminal receipts, UI captures and a full screen recording. Cleanup stops only recorded fixture processes. No production account traffic or paid provider is used.

## Debug routing and simulated voice

The debug journey opens `verify-session` (A) and `verify-other` (B) through the real UI. It checks the exact heading and composer together, then sends `UI_ONLY_A` and `UI_ONLY_B`. The existing instrumentation switches readers, sends typed and simulated final dictation to both SSH connections, and disposes a reader while dictation finishes. It checks that late dictation does not reach B.

Before the stale-session probe, require `OK (1 test)`, `EMULATOR_ROUTING_OK`, and these exact receipt sequences:

- A: `UI_ONLY_A`, `TYPED_A`, `VOICE_A`.
- B: `UI_ONLY_B`, `TYPED_B`, `VOICE_B`, `AFTER_SWITCH_B`.

Voice is simulated with the existing instrumentation. This does not prove microphone capture, speech recognition, or native provider delivery. Inspect `routing-b.png`, the UI captures and both receipt files.

## Signed M3 UI-only routing

Set `released_chat=true` and `channel=android-m3-latest`. Set `candidate=true` for a signed branch candidate. For the published APK, set `candidate=false` and supply its exact manifest commit as `expected_commit`. Do not set `app_ref`, `update_baseline_run`, or another release mode. `candidate=true` alone is not a signed routing run.

Signed runs use the existing same-signer account seed and real host-key and private-key import UI. The isolated proxy blocks production account traffic. Signed positives require exactly `UI_ONLY_A` and `UI_ONLY_B`; they do not run debug instrumentation. Their result explicitly says `UI_ONLY`: it does not cover voice, instrumentation, or a native provider.

## Vanished selected session

After the mode's positive gate, the driver reopens A while it exists and enters `IGSH1230_STALE_A_FORBIDDEN_7291`. It records the exact A title and draft. It starts independent C, `verify-session-neighbor`, and confirms its distinct screen marker. C records raw terminal bytes and parsed lines separately. An allowed `CANARY_CONTROL_7291` input proves both logs work before their baselines are saved.

The driver verifies the recorded fixture tmux server and A's session ID, ends only A, and proves exact A absent while recorded B and C remain alive. With A still mounted, it taps the real Send button. The normal oracle requires the A heading, send error, and restored draft; no new C bytes or receipts; no C screen on A; and unchanged B screen and receipts. The classifier is `EXACT_TARGET_STALE_SEND_REJECTED` or `EXACT_TARGET_STALE_SEND_REJECTED_UI_ONLY`.

The old app must fail normally. The intended failure is `SELECTED_SESSION_FELL_BACK_TO_NEIGHBOR`, emitted only when the app's recorded SSH paste command follows the tap and C independently records the forbidden marker in both raw bytes and a parsed line. Any other failure, including timeout, wrong APK, setup, selector, or missing canary readiness, is not the intended red. Inspect `routing-preconditions.txt`, `commands-probe.txt`, `routing-classifier.txt`, C's before/after raw and receipt files, B's before/after evidence, and the screenshots and recording. `UNCLASSIFIED` means no causal result was proven.

For the current old M3 control, require manifest commit `056ee39de07940f27c16123b3652ae4f3017bf5a` and APK SHA-256 `d70eb97be6c633198058db524e4b2d336d7aa818a5cddf1b46ea1fa6c2e57a42`. A candidate is not a published APK. The existing published manifest, HTTPS URL, size, hash, package, version, and same-signer checks remain required.

Hosted JVM tests use a private tmux socket and execute production-generated commands. They exercise missing prefix and glob targets, real terminal input and captures, and literal special-name creation. The pre-fix implementation is expected to fail those assertions. They do not fence replacement of a session with the same exact name or prove provider identity.
