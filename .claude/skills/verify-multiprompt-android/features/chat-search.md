# Find words inside a chat

Prove `chat-search.open` for [SOFT-2540](https://app.hypertask.ai/detail/project-2060/2540), closing the missing phone journey from [PR #380](https://github.com/valentinyeo/multiprompt-android/pull/380).

Dispatch `verify.yml` with `feature=chat-search.md`. Use a fresh GitHub-hosted emulator. The driver imports the disposable SSH key through the document picker, compares the host fingerprint, and taps **Trust and connect** before opening the chat.

The independent SSH fixture writes a Claude JSONL transcript. Two replies contain `orchard north` and `orchard south`, separated by twelve long exchanges. The terminal shows only a ready marker, so terminal scraping cannot supply the matches. No account, provider key, app setter, or production data is involved.

## Journey

Every step retains a numbered PNG/XML pair and checks the resulting text. Search states also retain `search-<state>.json` from the actual XML and screenshot.

1. Open `verify-session`. Require `VERIFY_SEARCH_READY_2540`, the message box, and `orchard south`. Require `orchard north` to be off screen. Capture `search-chat`.
2. Open **Session actions**. Require **Search in chat**. Capture `search-menu`.
3. Tap **Search in chat**. Require **Search this chat**, **Close search**, both match buttons, and `0 of 0`. Capture `search-open`.
4. Type `orchard`. Require that query and `2 of 2`. Require `orchard south` fully on screen with the active word highlighted. Capture `search-newest`.
5. Tap **Previous match**. Require `1 of 2` and the highlighted `orchard north` reply, previously off screen. Capture `search-previous`.
6. Tap **Previous match** again. Require `2 of 2` and highlighted `orchard south`. Capture `search-wrap-previous`.
7. Tap **Next match**. Require `1 of 2` and highlighted `orchard north`. Capture `search-wrap-next`.
8. Tap **Next match** again. Require `2 of 2` and highlighted `orchard south`. Capture `search-next`.
9. Replace the query with `NO_MATCH_2540`. Require `0 of 0`. Capture `search-no-match`.
10. Restore `orchard`. Require `2 of 2` and highlighted `orchard south`. Capture `search-restored`.
11. Tap **Close search**. Require the normal chat menu and message box, with no search controls. Capture `search-closed`. Require no input sent to the host.

## Evidence

`tools/chat-search/ui.py` checks match counts, query text, search controls, destination bounds, and active highlight pixels inside the destination's text bounds. It rejects ordinary unhighlighted text. `search-proof.txt` is written only after all steps pass. Inspect the PNGs alongside the XML, state JSON, and `driver-transcript.txt` in `verify-chat-search.md-<run-id>` after cleanup.

The registry maps chat search source changes to this journey. The picker must schedule it even when the model omits it or fills all three optional slots. Hosted Node tests replay the real PR #380 diff and reject its unrelated successful reader proof.

## Not covered

This journey covers native Claude history. Terminal fallback search, expanded tables, skill pills, and account-only chats remain outside this recipe.
