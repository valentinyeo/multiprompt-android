# Plain reply lists and real questions

Dispatch `verify.yml` with `feature=chat-choices.md` on the pushed branch. No local app or Gradle runs.

The isolated SSH/tmux fixture uses Python, not an AI provider. Normal transcript discovery reads its Claude-format transcript. The debug journey does not inject preferences, reader state or transport methods.

## Journey

1. Import the generated key and trust the displayed fingerprint through the normal host screens.
2. Open `verify-session`. Capture `plain-numbered-reply`. Require original numbered markers, all three complete list items, no A/B/C badges and no clickable list items. The second item must include “or pause until you top up?”.
3. Send `Continue` through the normal composer. Require exactly `VERIFY_RECEIVED:Continue` at the independent console.
4. Capture `question-before`. Tap the live Claude-style question option **2: Use the current account**.
5. Require raw-key receipt `VERIFY_QUESTION_KEY:2`, resulting transcript **Question answered: Use the current account**, and `question-after`.
6. Require exactly those two receipts. No inferred prose answer, pasted question label, duplicate send or extra Enter may reach the console.

Inspect PNG/XML pairs, receipts, SSH logs, final tmux capture and driver transcript.

## Verify the published APK

Dispatch `verify.yml` with `feature=chat-choices.md`, `release_choices=true`, and `expected_commit` set to the published manifest commit. The workflow downloads the exact signed APK and verifies its hash, size, package, version and certificate. Same-signer instrumentation seeds an isolated offline account session. An emulator proxy blocks account traffic. This proves the released reader and real SSH transport, not production sign-in. No production app code or APK bytes change. The journey retains a recording and the same PNG/XML pairs and independent answer receipts.

Before merge, use `candidate=true` to build and test the signed branch APK. Leave `candidate=false` for released-build acceptance.

`ChatChoicesTest` covers complete read-only numbered and lettered prose in reader/classic themes, plus structured question retries and raw-key transport. `ClaudeQuestionTest` rejects prose lists, stale prompts, unrelated menus and invalid keys. Structured Codex question events are not currently supported; this change does not guess them from prose.

Desktop follows the same rule in https://app.hypertask.ai/detail/project-2060/1063.
