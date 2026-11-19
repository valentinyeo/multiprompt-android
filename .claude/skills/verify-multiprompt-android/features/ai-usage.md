# AI usage

Proves `ai.usage` for [SOFT-2542](https://app.hypertask.ai/detail/project-2060/2542), closing the missing phone journey from [PR #377](https://github.com/valentinyeo/multiprompt-android/pull/377).

Dispatch `verify.yml` with `feature=ai-usage.md`. Use a fresh GitHub-hosted emulator. The shared driver seeds the disposable SSH host and key through its existing instrumentation fixture. It compares the host fingerprint and taps **Trust and connect**. Host-form entry and document-picker import are not covered.

The independent SSH host writes its real `~/.local/state/token-ladder/status.json` file with fresh disposable Claude and Codex usage. The app reads that file through its normal SSH command. A host-side observer records the command, exit status, and exact returned bytes in `ai-usage-receipts.txt`. No usage is injected into the app. This tests supplied host numbers, not live provider billing or credentials.

## Journey

Every step saves a numbered PNG/XML pair and checks the captured text before continuing.

1. From the inbox, open **Session list actions**. Require **AI usage** and **Refresh**. Capture `ai-usage-menu`. Require no usage read before opening the view.
2. Tap **AI usage**. Require **Claude** with `37.5%` weekly and `12.5%` five-hour usage, and **Codex** with `62.5%` weekly and `25%` five-hour usage. Capture `ai-usage-open`. Match each provider's headline, weekly detail, five-hour detail, pace, and forecast to the independent host receipt. Require both complete blocks on screen.
3. Press Android **Back**. Require **Inbox** and `verify-session`, with no **AI usage** or provider blocks. Capture `ai-usage-closed`.

## Evidence

`tools/ai-usage/ui.py` reads the captured XML and screenshot dimensions. It rejects swapped provider values, incorrect numbers, off-screen blocks, missing host receipts, and an unclosed sheet. `ai-usage-menu.json`, `ai-usage-open.json`, and `ai-usage-closed.json` link the screenshot and XML for each step. `ai-usage-proof.txt` appears only after the complete journey passes.

The registry removes `ai.usage` from the missing-coverage baseline. It schedules this journey for AI usage product changes and edits to this recipe, even when the model omits it. Inspect the PNGs, JSON, raw host receipts, and `driver-transcript.txt` in the retained head artifact at the exact APK source SHA after cleanup.

Stale or missing usage, host fallback ordering, long-list scrolling, account sync, and signed releases are outside this recipe.
