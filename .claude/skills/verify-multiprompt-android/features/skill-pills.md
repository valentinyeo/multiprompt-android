# Skills within messages

Proves `skills.pills` for [SOFT-2543](https://app.hypertask.ai/detail/project-2060/2543), closing the missing phone journey from [PR #378](https://github.com/valentinyeo/multiprompt-android/pull/378).

Dispatch `verify.yml` with `feature=skill-pills.md`. The fresh GitHub emulator imports the disposable SSH host through the real form and document picker, compares its fingerprint, and chooses **Trust and connect**. The existing native-skills console supplies real project, user and plugin skill files and records incoming messages independently. Six extra project skills make scrolling necessary to reach `/review-native`.

## Journey

1. Open `verify-session`. Type `Please this`. Require that exact composer text and no pill outline. Capture `pills-draft`.
2. Move the cursor after `Please ` and type `/ `, leaving `this` intact. Move back one character to open the picker. Require `Please / this` and `/pill-guide-01`. The driver retypes the slash up to three times from the retained draft until the composer reads exactly `Please / this`, so a dropped keystroke fails on the composer text instead of waiting for a picker that never had its slash. Capture `pills-picker`. Require that reaching `/review-native` needs a scroll.
3. Scroll Composer suggestions using the visible skill row's scroll-container bounds. Require `/review-native` and unchanged `Please / this`. Capture `pills-scrolled`, then tap `/review-native`. Require exact `Please /review-native this` and a painted pill outline inside the composer. Capture `pills-inserted`.
4. Tap **Send prompt**. Require the exact sent message with one inline `/review-native` pill, its fill distinct from the surrounding bubble, an empty composer and the host reply. Capture `pills-sent`. Require the independent receipt `VERIFY_RECEIVED:Please /review-native this`.
5. Type `Keep /rev this`, move the cursor after `/rev`, and tap `/review-native`. Require exact `Keep /review-native this` and its painted composer pill. Capture `pills-removable`.
6. Backspace over the skill token and its trailing space. Require exact `Keep this` and no composer pill outline. Capture `pills-removed`.
7. Tap **Send prompt**. Require the exact `Keep this` sent message, cleared composer and host reply. Capture `pills-sent-removed`. Require the independent receipt `VERIFY_RECEIVED:Keep this`.

Every step retains a PNG and UI XML. `skill-pills-state.json` records exact observed text, screenshots and visual assertions. The screenshot check looks for paired horizontal pill-outline strokes inside the composer, excluding the field border. The sent label's accessibility bounds exclude its padding, so the screenshot crop includes padding scaled to the text height and stays inside the owning message. The sent check compares the dominant pill fill against the surrounding message fill rather than requiring an exact stroke color. `sent_pill_bounds`, `sent_pill_fill` and `sent_bubble_fill` retain that measurement. Both retained sent screenshots must pass; repainting each pill as plain slash text must fail. Its tests reject plain slash text without a pill, a surviving outline after removal, and a pill outside the sent message. The retained PNG/XML from [the failed a4470175 run](https://github.com/valentinyeo/multiprompt-android/actions/runs/44110224686) reproduces the too-tight crop. The [4600e0e1 nightly](https://github.com/valentinyeo/multiprompt-android/actions/runs/45106300380) supplies the second fixture with a different outline color.

`skill-pills-receipts.txt` must contain exactly the two messages above and two distinct phone correlation markers. The console retains those transport lines raw without turning metadata into extra chat replies. `skill-pills-transcript.jsonl` independently confirms the native user turns, with no injected skill body. The registry supplies action, UI-state and receipt citation hints. The picker must schedule this journey for matching skill-pill changes and changes to this recipe, even when the model omits it.

Live provider execution, account sync, signed releases, multiline pills and multiple simultaneous pills are outside this recipe.
