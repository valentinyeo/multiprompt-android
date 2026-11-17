# Find and send Claude clear

Proves `claude.clear-ranking` for [SOFT-2545](https://app.hypertask.ai/detail/project-2060/2545), closing the phone-journey gap from [PR #381](https://github.com/valentinyeo/multiprompt-android/pull/381).

Dispatch `verify.yml` with `feature=clear-ranking.md`. Changes to Claude command ranking also require this journey on pull requests. The fresh GitHub emulator imports the ephemeral SSH key through Android's document picker, compares the fingerprint, and connects through the normal UI.

PR #381 requires `/clear` first for `/cl`, name matches before description matches, and one built-in row when a session skill has the same name. The disposable Claude-shaped console exposes real skill files for `/client`, `/aaa-paper` with description `Clear the log`, and a duplicate `/clear`. It records stdin independently of Android. It does not call Claude or claim to reset provider context.

## Journey

Each step retains a numbered PNG/XML pair and an asserted JSON state.

1. Open `verify-session`. Require `Native session skills are ready.` and the message box. Capture `clear-ready`.
2. Type `/cl`. Require the exact editor value and visible enabled rows ordered `/clear`, `/client`, `/aaa-paper`. Require one `/clear` and its built-in description. The description-only match must follow both name matches. Capture `clear-query`. Require an empty host receipt.
3. Tap `/clear`. Require the exact editor value `/clear ` and the closed picker. Capture `clear-selected`. Require the host receipt still empty.
4. Tap **Send prompt**. Require the empty message box and the host reply `Received /clear. Claude`. Capture `clear-sent`. Require exactly one command receipt, `VERIFY_RECEIVED:/clear`, with no trailing space, extra draft, description, or bundled skill body. Allow only the optional `[mp:<16 hex>]` delivery trailer defined by the reader transport. Retain it unchanged in the raw host log and record it separately in `clear-host.json`.

## Evidence

Inspect `clear-*.png/xml`, `clear-ready.json`, `clear-query.json`, `clear-selected.json`, `clear-sent.json`, `clear-before.txt`, `clear-selected-host.txt`, `clear-receipts.txt`, `clear-host.json`, and `clear-proof.txt` in the retained `verify-clear-ranking-*` artifact. The query state records the observed row order. The selected state records the exact editor value. Receipts come from the SSH fixture's stdin log, not a phone echo. `driver-transcript.txt` records each action. `app-source-sha.txt` must equal the PR head.

The registry supplies exact action, result, and receipt citation hints to the independent verifier. Missing, fabricated, stale, or unrelated citations fail coverage. Account sync, live Claude reset semantics, and other command queries are outside this recipe.
