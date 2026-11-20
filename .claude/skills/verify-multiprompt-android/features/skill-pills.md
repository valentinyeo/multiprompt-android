# Skills within messages

Proves `skills.pills` for [SOFT-2543](https://app.hypertask.ai/detail/project-2060/2543), closing the missing phone journey from [PR #378](https://github.com/valentinyeo/multiprompt-android/pull/378).

Dispatch `verify.yml` with `feature=skill-pills.md`. The fresh GitHub emulator imports the disposable SSH host through the real form and document picker, compares its fingerprint, and chooses **Trust and connect**. The existing native-skills console supplies real project, user and plugin skill files and records incoming messages independently. Six extra project skills make scrolling necessary to reach `/review-native`.

## Journey

1. Open `verify-session`. Type `Please this`. Require that exact composer text and no pill outline. Capture `pills-draft`.
2. Move the cursor after `Please ` and type `/ `, leaving `this` intact. Move back one character to open the picker. Require `Please / this` and `/pill-guide-01`. Capture `pills-picker`. Require that reaching `/review-native` needs a scroll.
3. Scroll Composer suggestions using the visible skill row's scroll-container bounds. Require `/review-native` and unchanged `Please / this`. Capture `pills-scrolled`, then tap `/review-native`. Require exact `Please /review-native this` and a painted pill outline inside the composer. Capture `pills-inserted`.
4. Tap **Send prompt**. Require the exact sent message with one inline `/review-native` pill, its painted outline, an empty composer and the host reply. Capture `pills-sent`. Require the independent receipt `VERIFY_RECEIVED:Please /review-native this`.
5. Type `Keep /rev this`, move the cursor after `/rev`, and tap `/review-native`. Require exact `Keep /review-native this` and its painted composer pill. Capture `pills-removable`.
6. Backspace over the skill token and its trailing space. Require exact `Keep this` and no composer pill outline. Capture `pills-removed`.
7. Tap **Send prompt**. Require the exact `Keep this` sent message, cleared composer and host reply. Capture `pills-sent-removed`. Require the independent receipt `VERIFY_RECEIVED:Keep this`.
8. Type a six-line draft ending with `/review-native`, then tap the field so the keyboard is open. Require the exact draft in the composer. From the same UI dump require the `+` button and the text field inside one composer row, the `+`, **Start dictation** and **Send prompt** bottoms aligned with the field bottom, the row at the bottom of the window above the keyboard, and the placeholder absent. Capture `composer-long-draft`. The app before IGSH-2957 centers dictation and send in a grown field, so the parent run stops on that alignment. Nothing is sent.

Every step retains a PNG and UI XML. `skill-pills-state.json` records exact observed text, screenshots and visual assertions. The screenshot check looks for paired horizontal pill-outline strokes inside the composer, excluding the field border. The sent label's accessibility bounds exclude its padding, so the screenshot crop includes padding scaled to the text height and stays inside the owning message. The same paired outline strokes remain required. Its tests reject plain slash text without a pill, a surviving outline after removal, and a pill outside the sent message. The retained PNG/XML from [the failed a4470175 run](https://github.com/valentinyeo/multiprompt-android/actions/runs/44110224686) reproduces the too-tight crop; removing the real border pixels must still fail.

`skill-pills-receipts.txt` must contain exactly the two messages above and two distinct phone correlation markers. The console retains those transport lines raw without turning metadata into extra chat replies. `skill-pills-transcript.jsonl` independently confirms the native user turns, with no injected skill body. The registry supplies action, UI-state and receipt citation hints. The picker must schedule this journey for matching skill-pill changes and changes to this recipe, even when the model omits it.

Live provider execution, account sync, signed releases and multiple simultaneous pills are outside this recipe. Step 8 types a long single-line draft that wraps over six lines; real newline characters in a draft stay outside this recipe, because the keyboard's send key is the only Enter a phone gives the composer.

The composer row's height is asserted from the UI dump, not from pixels: `COMPOSER_LONG_DRAFT_BOUNDS_OK` in the driver transcript, with the measured rectangles printed above it. The retained `composer-long-draft` PNG is the visual evidence that no chat text shows beside the box and no pill crosses its bottom edge.
