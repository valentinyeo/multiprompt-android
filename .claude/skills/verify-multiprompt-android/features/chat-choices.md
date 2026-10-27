# Plain reply lists and real questions

Dispatch `verify.yml` with `feature=chat-choices.md` on the pushed branch. No local app or Gradle runs.

The isolated SSH/tmux fixture uses Python, not an AI provider. Normal transcript discovery reads its Claude-format transcript. No preferences, reader state or transport methods are injected.

## Journey

1. Import the generated key and trust the displayed fingerprint through the normal host screens.
2. Open `verify-session`. Capture `plain-numbered-reply`. Require original numbered markers, all three complete list items, no A/B/C badges and no clickable list items. The second item must include “or pause until you top up?”.
3. Send `Continue` through the normal composer. Require exactly `VERIFY_RECEIVED:Continue` at the independent console.
4. Capture `question-before`. Tap the live Claude-style question option **2: Use the current account**.
5. Require raw-key receipt `VERIFY_QUESTION_KEY:2`, resulting transcript **Question answered: Use the current account**, and `question-after`.
6. Require exactly those two receipts. No inferred prose answer, pasted question label, duplicate send or extra Enter may reach the console.

Inspect PNG/XML pairs, receipts, SSH logs, final tmux capture and driver transcript. This journey uses the debug build; it does not prove released phone screens. Check the signed published update separately.

`ChatChoicesTest` covers complete read-only numbered and lettered prose in reader/classic themes, plus structured question retries and raw-key transport. `ClaudeQuestionTest` rejects prose lists, stale prompts, unrelated menus and invalid keys. Structured Codex question events are not currently supported; this change does not guess them from prose.

Desktop follows the same rule in https://app.hypertask.ai/detail/project-2060/1063.
