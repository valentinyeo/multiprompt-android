# Inbox question and step

A chat row shows a waiting question in place of the pane reply. A working live chat with no question shows its current step. Both texts are on the inbox row. Neither is painted on the tmux pane.

Dispatch `verify.yml` with `feature=session-rows.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys.

The fixture starts two sessions before the phone connects:

- `verify-session` is a Claude pane. Its screen is `⏺ Pane reply stays.` The transcript holds an unanswered `AskUserQuestion` whose text is `Which target should ship?` That question is not on the pane.
- `live-rowstep` is a `sleep` pane with host session `a1b2c3d4-2361-4000-8000-000000002362`. Its planted history has an in-progress step `Rewrite the inbox row` and no question. The step is not on the pane. The host state marks the chat in flight, so the row is working without a `Working:` spinner.

## Journey

1. On Inbox, wait until the `verify-session` row shows `Which target should ship?` and no longer shows `Pane reply stays.`
2. Wait until the `live-rowstep` row shows `Rewrite the inbox row`. Capture `inbox-rows`.
3. Save the Claude transcript and the live history. Require `AskUserQuestion` and the question in the transcript, and `TodoWrite` and the step in the history.
4. Capture both panes. The question pane still contains `Pane reply stays.` and not the question. The step pane does not contain the step.

## Gotchas

The app before this change has no stored row parts, so the parent run of this same script stops while it waits for the question. A missing `/home/verifyssh/receipts.txt` means nothing was sent; this journey never reads that file and never sends a prompt. The question and the step are planted files on the fixture user. No external model, account, or API key is used.

A pane that already shows `Working:` keeps that screen line and does not show the stored step. This journey does not paint a spinner. Answering the question inside the open chat, account OTP, a signed release build, and a physical phone are outside this recipe.
