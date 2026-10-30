# Structured questions and ordinary lists

Dispatch `verify.yml` with `feature=chat-choices.md` on the pushed branch. No local app or Gradle runs.

The isolated SSH/tmux Python fixture writes real structured AskUserQuestion events. Transcript discovery reads the events through SSH; prose never creates cards.

## Journey

1. Import the generated key and trust the displayed fingerprint through normal host screens.
2. Open `verify-session`. Check exact question, header, labels and descriptions. Select **Use the current account**, then **Submit answer**. Require raw-key receipt `VERIFY_QUESTION_KEY:2`.
3. For **What should we keep?**, select **History** and **Settings**, type **Custom** into **Other**, then submit. Require `VERIFY_MULTI:{"other": "Custom", "selected": [0, 1]}`.
4. Require **Structured questions answered.** and no remaining answer card.
5. Capture ordinary numbered and bulleted lists. Require original markers and complete text without clickable items.
6. Require exactly those two receipts. No label guessing, duplicate sends or extra Enter.

Inspect PNG/XML pairs, receipts, SSH logs, final tmux capture and driver transcript.

## Published APK

Dispatch `verify.yml` with `feature=chat-choices.md`, `release_choices=true`, and `expected_commit` matching the public manifest. The workflow verifies exact APK hash, size, package, version and certificate. Same-signer instrumentation seeds only an isolated offline session. An emulator proxy blocks account traffic. Production app bytes and sign-in behavior stay unchanged.

Before merge use `candidate=true`; after release use `candidate=false`.

JVM tests cover plain lists, exact labels and descriptions, single/multiple selection, Other, retries, stale questions and provider-specific key transport. Python tests cover structured Claude/Codex event parsing. Desktop counterpart: https://app.hypertask.ai/detail/project-2060/1063.
