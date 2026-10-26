# Tap chat choices

Dispatch `verify.yml` with `feature=chat-choices.md` on the pushed branch. No local app or Gradle runs.

The existing real SSH/tmux fixture uses a Python console, not an AI provider. It keeps a Claude-format transcript open and uses an agent-shaped process name for normal transcript discovery. No app preferences, reader state or transport methods are injected.

## Journey

1. Import the generated key and trust the displayed fingerprint through the normal host screens.
2. Open `verify-session`. The latest assistant reply contains lettered options, including **Use your own account. Keep the current setup.** Capture `choices-before`.
3. Tap that option. Require the independent console receipt `VERIFY_RECEIVED:B: Use your own account. Keep the current setup.` and the resulting user message. Capture `choices-after`.
4. The console now presents a single-select Claude-style question in the live tmux pane. Capture `question-before`; tap **2: Use the current account**.
5. Require the raw-key receipt `VERIFY_QUESTION_KEY:2` and transcript reply **Question answered: Use the current account**. Capture `question-after`.
6. Require exactly those two receipts. No pasted answer, duplicate send or extra Enter may reach the console.

Inspect all four PNG/XML pairs, the receipts, SSH log, final tmux capture and driver transcript. The normal in-app update is proved separately with `node tools/release-status.mjs` against the merged commit.

`ChatChoicesTest` also covers numbered options in classic and reader themes, lettered options, selected/disabled choices, older replies, queued/user answers, and a tap delivering its generated command into a real isolated tmux session. `ClaudeQuestionTest` rejects stale prompts, mismatched questions, free-text and multi-select prompts, invalid keys and unrelated menus. Multi-select and free-text entries remain terminal interactions; this journey makes no claim about those or a live provider.

Red control: https://github.com/valentinyeo/multiprompt-android/actions/runs/40330821523/job/121599465299 — the unchanged app fails `latestReplyOptionsAreTappable`; the option has no click action.
