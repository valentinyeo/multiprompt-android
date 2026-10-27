# Structured question choices

Dispatch `verify.yml` with `feature=chat-choices.md`. Builds and emulator runs happen only on GitHub.

The real SSH/tmux fixture keeps a provider-format transcript open. It contains ordinary reply lists, pending `AskUserQuestion` calls and their matching tool results. No application state or transport is injected.

## Journey

1. Import the generated key and trust its displayed fingerprint.
2. Open `verify-session`. Inspect the exact question, header, labels and descriptions.
3. Select **Use the current account**, then **Submit answer**. Require the independent receipt `VERIFY_QUESTION_KEY:2`.
4. Select both **History** and **Settings**. Enter **Custom** under **Other**, then submit. Require the receipt `VERIFY_MULTI:{"other": "Custom", "selected": [0, 1]}`.
5. Require the completed reply and disappearance of the settled question cards.
6. Inspect the older ordinary list. Its original numbers remain. Its items and bullet have no clickable ancestor.
7. Require exactly those two receipts, without duplicate sends.

Inspect every PNG/XML pair, receipts, SSH log, final tmux capture and driver transcript. The fixture verifies provider terminal keys, not a live provider subscription.

`PlainListsTest` fails against original source. `ChatChoicesTest` covers exact payload display, multiple selection, Other and transport retry. `PendingQuestionTest` covers pending ownership and settlement. Python fixtures cover Claude and Codex structured events, malformed inputs and ordinary tools.
