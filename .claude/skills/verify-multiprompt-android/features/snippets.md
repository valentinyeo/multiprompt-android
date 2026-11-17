# Saved snippets

Proves `snippets.save-reuse-delete` for [SOFT-2537](https://app.hypertask.ai/detail/project-2060/2537), closing the missing phone journey from [PR #376](https://github.com/valentinyeo/multiprompt-android/pull/376).

Dispatch `verify.yml` with `feature=snippets.md`. The driver imports the debug SSH host through the real form and document picker, compares its fingerprint, and chooses **Trust and connect**. Each capture includes a PNG and UI XML. Text assertions must pass before the next action.

## Journey

1. Open `verify-session`. Type `VERIFY_SNIPPET_2537` in the composer. Capture `snippets-draft`.
2. Open **Attach**, then **Snippets**. Require **No snippets yet**. Capture `snippets-empty`.
3. Tap **Save current text**. Require the snippet row and **Delete**, with no **No snippets yet**. Capture `snippets-saved`. Require no host receipt for the unsent draft.
4. Force-stop and reopen the app. Open the same chat and replace its draft with `replacement draft`. Open **Attach**, then **Snippets**. Require the saved snippet and **Delete**. Capture `snippets-retained`.
5. Tap `VERIFY_SNIPPET_2537`. Require that exact composer value, replacing `replacement draft`, with the menu closed. Capture `snippets-reused`.
6. Tap **Send prompt**. Require `VERIFY_RECEIVED:VERIFY_SNIPPET_2537` on screen and in the independent host receipt. Capture `snippets-sent`.
7. Open **Attach**, then **Snippets**. Tap **Delete**. Require **No snippets yet** and no **Delete**. Capture `snippets-deleted`.
8. Force-stop and reopen the app again. Open the same chat and **Snippets**. Require **No snippets yet** and no **Delete**. Capture `snippets-deleted-restarted`.

## Evidence

`snippets-state.json` extracts the observed menu text and composer values from the captured XML, asserts each state, and links its screenshot. `snippets-receipts.txt` must contain exactly one receipt for the reused text. The registry prioritizes both files for the independent verifier and supplies exact action, UI-state, and receipt citation hints. Hints do not count as proof: the gate still requires those quotes in the successful head journey's retained artifact at the exact APK source SHA. Product changes matching the snippet outcome schedule this journey before optional AI-picked journeys.

Account sync, signed releases, multiline snippets, duplicate saves, and the snippet count limit are outside this recipe.
