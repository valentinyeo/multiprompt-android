# Skills within messages

Proves `skills.pills` for [SOFT-2543](https://app.hypertask.ai/detail/project-2060/2543), closing the missing phone journey from [PR #378](https://github.com/valentinyeo/multiprompt-android/pull/378).

Dispatch `verify.yml` with `feature=skill-pills.md`. The fresh GitHub emulator imports the disposable SSH host through the real form and document picker, compares its fingerprint, and chooses **Trust and connect**. The existing native-skills console supplies real project, user and plugin skill files and records incoming messages independently. Six extra project skills make scrolling necessary to reach `/review-native`.

## Journey

1. Open `verify-session`. Type `Please this`. Require that exact composer text and no pill outline. Capture `pills-draft`.
2. Move the cursor after `Please ` and type `/ `, leaving `this` intact. Move back one character to open the picker. Require `Please / this` and `/pill-guide-01`. Capture `pills-picker`. Require that reaching `/review-native` needs a scroll.
3. Scroll Composer suggestions and tap `/review-native`. Require exact `Please /review-native this` and a painted pill outline inside the composer. Capture `pills-inserted`.
4. Tap **Send prompt**. Require the exact sent message with one inline `/review-native` pill, its painted outline, an empty composer and the host reply. Capture `pills-sent`. Require the independent receipt `VERIFY_RECEIVED:Please /review-native this`.
5. Type `Keep /rev this`, move the cursor after `/rev`, and tap `/review-native`. Require exact `Keep /review-native this` and its painted composer pill. Capture `pills-removable`.
6. Backspace over the skill token and its trailing space. Require exact `Keep this` and no composer pill outline. Capture `pills-removed`.
7. Tap **Send prompt**. Require the exact `Keep this` sent message, cleared composer and host reply. Capture `pills-sent-removed`. Require the independent receipt `VERIFY_RECEIVED:Keep this`.

Every step retains a PNG and UI XML. `skill-pills-state.json` records exact observed text, screenshots and visual assertions. The screenshot check looks for paired horizontal pill-outline strokes inside the composer, excluding the field border. It also checks the sent pill's own bounds. Its tests reject plain slash text without a pill, a surviving outline after removal, and a pill outside the sent message.

`skill-pills-receipts.txt` must contain exactly the two messages above. `skill-pills-transcript.jsonl` independently confirms the native user turns, with no injected skill body. The registry supplies action, UI-state and receipt citation hints. The picker must schedule this journey for matching skill-pill changes and changes to this recipe, even when the model omits it.

Live provider execution, account sync, signed releases, multiline pills and multiple simultaneous pills are outside this recipe.
