# MUBO-554 typing measurements

Make typing and middle edits smooth again without losing drafts, mentions or skill pills.

## Before changing the typing path

[Successful baseline run](https://github.com/valentinyeo/multiprompt-android/actions/runs/45593682043)
at `af89f77d`, with only diagnostic hooks added to the original app. The `timing-scale-head`
artifact retains every key in `typing/keys.json`, a separate sampled Main trace and its
`typing-profile.txt` report, the recording, exact reopened draft and validated seed metadata.

The PR #605 seed has 40 chats. This run opens `live-scale-01`, with 54,532,789 transcript bytes
and 30,004 rows, and requires `SCALE_CHAT_LATEST_4704_01` on screen. Five trials each type
60 characters and insert ten characters at offset 30. All 350 keys have a post-input frame;
each key's complete expected text is checked outside its timing window. Leaving and reopening
retains the exact edited draft.

| Before | Keys | Frame p50 / p95 ms | Main CPU p50 / p95 ms | Reader compositions/key | List compositions/key | ART allocated p50 bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Type | 300 | 234.79 / 369.83 | 12.48 / 19.96 | 1.123 | 0.137 | 204,832 |
| Middle edit | 50 | 209.66 / 339.84 | 12.14 / 21.72 | 1.460 | 0.040 | 237,600 |

Typed-key trial p50 Main CPU ranges from 11.74 to 13.78 ms, frame p50 from 209.02 to
303.04 ms. Middle-edit trial p50 Main CPU ranges from 9.88 to 18.14 ms. Report changes
inside those spreads as inconclusive, not a phone speedup. The five trials run consecutively,
not alternating between builds, so host drift remains a limitation.

## After the change

[Head run](https://github.com/valentinyeo/multiprompt-android/actions/runs/45596646030) at
`dba212eb`, same dispatch, same 40-chat seed, same `live-scale-01` chat, same five trials,
same key events and the same exact-text checks. The draft that survives leaving and reopening
the chat is byte-identical to the last typed text (`typing/last-draft.txt` against
`typing/draft-reopened.txt`). All 350 keys still have a post-input frame.

| After | Keys | Frame p50 / p95 ms | Main CPU p50 / p95 ms | Reader compositions/key | List compositions/key | ART allocated p50 bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Type | 300 | 194.82 / 312.21 | 13.15 / 19.77 | 0.060 | 0.093 | 188,456 |
| Middle edit | 50 | 169.39 / 300.25 | 11.85 / 23.94 | 0.120 | 0.120 | 204,848 |

Typed-key trial p50 Main CPU ranges from 12.21 to 13.93 ms, frame p50 from 181.44 to
238.76 ms. Middle-edit trial p50 Main CPU ranges from 9.65 to 12.59 ms.

### What the numbers say

- The reader no longer rebuilds on a key. `ReaderScreen` compositions per key fall from
  1.123 to 0.060 when typing and from 1.460 to 0.120 on a middle edit. `MultipromptApp`
  falls from 0.103 to 0.060. The composer keeps its own work: 2.16 compositions per key,
  against 2.14 before.
- The residue is live reader traffic, not typing. A 60-key trial runs about 12 s, so the
  reader's two-second poll lands a few times inside it: 0.060 compositions/key is 3.6 reader
  compositions per trial. The JVM guard proves the key press itself recomposes neither the
  reader nor the list (both 0).
- List compositions per key are 0.093 typing against 0.137 before, and 0.120 editing
  against 0.040. That is 5.6 list compositions per 60-key trial and 1.2 per 10-key trial,
  matching those live updates. Key presses add none.
- Per-key Main CPU does not move: 13.15 ms p50 against 12.48, both inside the before trial
  spread of 11.74 to 13.78. The removed recomposition was the cheap part of the frame. In
  the before trace, the reader's outer content is 10.49 ms of 85.45 ms of recomposition work
  across ten keys, about 1 ms per key. The saving scales with what the reader draws, not
  with this screen's fixed cost.
- Frame p50 falls 234.79 to 194.82 ms and p95 369.83 to 312.21. Main CPU is flat, so I read
  most of that as emulator rendering variance: the trial p50 spread is 209.02 to 303.04
  before and 181.44 to 238.76 after. Not a claimed phone speedup.
- Allocation p50 per typed key falls from 204,832 to 188,456 bytes; middle edits fall from
  237,600 to 204,848.

### What limits frame time now

Drawing, not app work. In the head run's 239.65 ms sampled Main trace,
`HardwareRenderer.nSyncAndDrawFrame` costs 36.09 ms exclusive and `performDraw` 77.86 ms
inclusive, while the `Recomposer` costs 68.55 ms inclusive. A ~195 ms frame is about 5 fps,
which is what this software-rendered emulator gives every build. With ~13 ms of Main CPU per
key, frames here are not gated by the app's composition; the app-side cost that remains per
key is composer text layout plus the skill-span lookup (0.293 ms/key) and the draft save
(0.079 ms/key), neither of which the ticket targets.

## What the baseline rules out

- The draft is already local Compose state, not `AppUiState`. `MultipromptApp` composes
  0.094 times/key amid normal reader updates, not once for every key.
- `ReaderScreen` still reads the draft in its outer composition. It composes 1.171 times/key;
  the composer composes 2.231 times/key. List compositions are already mostly skipped,
  with live reader updates and composer-height changes still counted.
- Draft persistence costs 0.090 ms/key. Skill-span lookup costs 0.339 ms/key. Neither
  explains most of the measured CPU, so neither receives a separate optimization.
- The separate ten-key trace attributes 85.45 ms to recomposition/apply callbacks, including
  63.18 ms in `performRecompose`; the reader's outer content accounts for 10.49 ms,
  and composer-row work for 34.63 ms (nested times, not additive). Drawing/traversal
  accounts for 83.72 ms. There is no Compose test clock or Espresso synchronization.

The chosen shape retains the existing `MutableState<TextFieldValue>` owner and reads its
value only in the bottom-bar composition, event handlers and a `snapshotFlow` persistence
observer. Extracting the entire composer would require a large parameter interface for the
same isolation. Persistence still observes each changed text, and disposal flushes the latest
value so leaving immediately cannot lose a pending observation.

## Limits and discarded measurements

Both runs are the debug APK on GitHub's software-rendered emulator, not release 0.1.748 on a
physical phone, and they are two separate emulator runs rather than one alternating series.
FrameMetrics duration is a rendering measure, not key-to-screen latency.
Main CPU ends at the first post-input frame callback after the edited text composes.
Allocation deltas are process-wide ART bytes, including the input driver and background work.
Idle windows measure 0.12–1.60 ms Main CPU without an input. The sampled trace runs before,
and outside, the timed keys.

Early harness attempts are not comparison evidence: an invalid test import; debug account
entry after instrumentation restart; late frame callbacks; Compose test-clock CPU; stale
accessibility text; and an unconfirmed middle-selection update. The build pipeline also
masked its compiler failure behind `tee`; `set -euo pipefail` now makes it fail at the cause.
That pre-existing harness fault belongs on the Android repo's board/view, via the owner chat.

The accepted baseline checks exact text, waits for selection readback, and uses Android key
events with the activity's normal frame clock. All builds and execution happen on GitHub.

The measured head later gained one change in the diagnostic hook only: the composition probe
follows a runtime `TypingProbe.enabled` flag initialised from `BuildConfig.DEBUG`, so the
Material 3 release-flavour unit test can switch it on. In the debug APK the flag is true
either way, so the key path measured here is the key path that ships.

## Frozen benchmark against the merged baseline

The frozen harness from [PR #617](https://github.com/valentinyeo/multiprompt-android/pull/617)
(`scale-timing.yml`, mode `frozen`, schema `mubo-555-v1`) times the same work on both sides and
records `App CPU ms`, `Main ms`, `frames over 16 ms` and the raw `dumpsys gfxinfo` histogram.
Seed `scale`, five repeats, scenario `typing_long_chat`: open the largest chat, then type the
fixed 45-character draft `Frozen benchmark types this everyday message.` through `mpa fill` and
check the exact readback. Nothing is sent.

Two pairs ran in the same window, each with a concurrent base control:

| Pair | Head | Base | Head run | Base run |
| --- | --- | --- | --- | --- |
| 1 | `4c9f2c36` | `5ed79144` | [45602881727](https://github.com/valentinyeo/multiprompt-android/actions/runs/45602881727) | [45602885651](https://github.com/valentinyeo/multiprompt-android/actions/runs/45602885651) |
| 2 | `4c9f2c36` | `5ed79144` | [45604592131](https://github.com/valentinyeo/multiprompt-android/actions/runs/45604592131) | [45604596950](https://github.com/valentinyeo/multiprompt-android/actions/runs/45604596950) |
| 3 | `9f8ad7c8` | `5ed79144` | [45616481136](https://github.com/valentinyeo/multiprompt-android/actions/runs/45616481136) | [45616485948](https://github.com/valentinyeo/multiprompt-android/actions/runs/45616485948) |

Medians of five trials, with each trial range under them:

| Pair | Side | App CPU ms | Main ms | Frames over 16 ms | Wall ms |
| --- | --- | --- | --- | --- | --- |
| frozen baseline (`5ed79144`, #617) | base | 8720 (7770-9910) | 1000 (1000-1130) | 129 (124-146) | - |
| 1 | head | 6170 (5560-6590) | 620 (520-710) | 121 (115-125) | 19.7 (19.4-20.3 s) |
| 1 | base | 8160 (6750-8530) | 820 (740-930) | 131 (121-136) | 23.1 (21.9-23.9 s) |
| 2 | head | 6970 (6270-8440) | 730 (660-940) | 126 (117-137) | 20.9 (20.1-22.6 s) |
| 2 | base | 7750 (7110-8260) | 830 (750-950) | 127 (121-135) | 22.9 (22.2-23.8 s) |
| 3 | head | 9640 (9140-9970) | 680 (660-710) | 126 (121-132) | 21.5 (20.9-22.4 s) |
| 3 | base | 10530 (9850-11040) | 970 (880-1040) | 129 (122-137) | 23.9 (22.7-24.6 s) |

Pair 3 runs after the merge from `main` and after PR #619, so it is the harness with zero
errors. Main CPU separates again (head max 710 below base min 880, minus 30%), and the direction
never reverses in any pair. App CPU is lower at the median (9640 against 10530, minus 8%) but
overlaps by one trial, so read pair 3's App CPU as within noise and pair 1's as the separated
result. Every run in the session reported one error per trial before #619 and zero after it.

App CPU and Main CPU drop in both first pairs and the direction never reverses. Pair 1 separates on
the harness's own range rule (head max 6590 below base min 6750 for App CPU, 710 below 740 for
Main); pair 2 overlaps by one trial (head max 8440 above base max 8260), so read pair 2's CPU
as within noise and pair 1 as the separated result. Errors are one per trial on both sides in
every run, the same unrelated `CellSignalStrengthLte` line, so no work was dropped to buy the
saving.

### Frame time, the metric Valentin feels

`frames over 16 ms` is a count of frames in the gfxinfo histogram above 16 ms, and on this
software-rendered emulator every frame is above it. In pair 1, head trial 1, no frame landed in
any histogram bin at or below 31 ms, the GPU histogram puts 120 of 121 frames at its 4950 ms
clamp, `Number Slow issue draw commands` is 120 of 121, and `Number Frame deadline missed` is
120. So that count is the number of frames the app managed to draw in the window, and it cannot
fall while the emulator draws 4 to 6 frames a second. It even rises when a build draws more
frames in the same wall time.

The per-frame number inside the same gfxinfo dump is the histogram percentile. Medians of five
trials, ranges under them:

| Pair | Side | Frame p50 ms | p90 ms | p95 ms | p99 ms | Slow UI frames |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | head | 150 (150-150) | 250 (200-250) | 250 (250-250) | 300 (250-300) | 83 (82-90) |
| 1 | base | 200 (200-200) | 300 (300-300) | 300 (300-350) | 350 (350-400) | 104 (94-106) |
| 2 | head | 150 (150-200) | 250 (200-250) | 250 (250-300) | 300 (300-350) | 96 (86-115) |
| 2 | base | 200 (200-200) | 300 (300-300) | 300 (300-350) | 400 (350-450) | 104 (98-107) |
| 3 | head | 250 (250-250) | 350 (350-400) | 400 (400-400) | 450 (450-500) | 96 (85-103) |
| 3 | base | 250 (250-300) | 400 (400-400) | 400 (400-450) | 450 (450-500) | 107 (101-115) |

Head p50 is 150 ms in nine of ten trials and 200 ms in one in pairs 1 and 2; base is 200 ms in
all ten. The whole p50, p90 and p99 ranges separate there. p95 touches at 300 in pair 2, so treat
p95 there as within noise. Pair 3 ran on a slower pair of runners: both sides move up about
50 ms, p50 ties at 250, and the separation survives at p90 (350 against 400) and in slow UI
frames (96 against 107). Frames over 16 ms in pair 3 are 126 against 129, within noise. Every key press in this build costs a frame a quarter shorter than
the base's, and the share of frames that miss the UI-thread deadline falls with it.

### What still costs a frame per key

Recomposition is not it any more, and this is measured with the branch's own counter
(`TypingProbe`), which the debug APK reports per key in `typing/keys.json`:

| Per typed key | Before `af89f77d` | After `dba212eb` | After `9f8ad7c8` |
| --- | ---: | ---: | ---: |
| `ReaderScreen` (whole chat screen) compositions | 1.123 | 0.060 | 0.087 |
| `MultipromptApp` compositions | 0.103 | 0.060 | 0.087 |
| Composer `BasicTextField` compositions | 2.14 | 2.16 | 1.14 |
| `ChatHistoryList` (message list) compositions | 0.137 | 0.093 | 0.120 |
| Composer text layouts | not measured | not measured | 1.073 |
| Message list viewport layouts | not measured | not measured | 0.000 |
| IME inset at the key | not measured | not measured | 901 px, all 350 keys |

`4c9f2c36` on top of `dba212eb` removes the composer's per-key layout-state loop, the skill-span
scan for drafts without a slash, and the per-key draft write, so its counts are at or below the
right-hand column. The reader's 0.060 is its two-second repoll landing inside the window; the
list's 0.093 is the same live traffic. The JVM guard
`ComposerLongDraftTest.typingAndMiddleEditsDoNotRecomposeTheReaderOrMessageList` puts key-driven reader and list
compositions at zero.

[Head run 45616332136](https://github.com/valentinyeo/multiprompt-android/actions/runs/45616332136)
at `9f8ad7c8` adds the three probes the last round asked for, on the same seed, chat, five
trials and key events: every composition by composable, every text-field layout through
`onTextLayout`, every layout of the message list's viewport through `onSizeChanged`, and the IME
inset read from the decor view on each key. Composer compositions per key fall to 1.14, one text
layout per key remains because the text changed, and the message list's viewport does not
relayout at all. The byte-identical reopened draft and all 350 post-input frames still hold.

The remaining per-key cost is the composer's own text layout, about 2 ms of the ~13.8 ms Main
CPU per key, and then drawing. The sampled Main trace in this build spends 36.09 ms exclusive in
`HardwareRenderer.nSyncAndDrawFrame` and 77.86 ms inclusive in `performDraw`; the GPU histogram
above shows the emulator failing to finish 120 of 121 frames. No app-side change moves this
harness's frame time further, which is why the benchmark README calls the frame count secondary.

### Keyboard insets

The reader's message list takes its bottom padding from the Scaffold's bottom-bar padding
(`MultipromptApp.kt`, `bottomPadding = padding.calculateBottomPadding()` into
`ChatHistoryList`), and the bar carries `Modifier.imePadding()`. Every frame of the keyboard
open animation therefore changes the list's content padding and remeasures the visible rows.

That animation costs one keyboard-open event, and it is not a per-key cost. The probe run above
measures the IME inset on every key: 901 px on all 350 keys, unchanged, and the message list's
viewport size changes 0 times in the same 350 keys. The keyboard is fully up before the first
timed key, so no insets frame lands inside the typing window. In the frozen benchmark the app
logs exactly one `FrameTracker ... IME_INSETS_SHOW_ANIMATION` timeout per keyboard open, 5 in 5
trials, identical at base and head in every pair. There is no per-key insets relayout to remove,
so the composer keeps its current padding and the list keeps one padding per animation. Moving
`imePadding()` onto the composer alone would change where the keyboard pushes the composer and
needs its own visible-difference measurement before any edit.

## Skill pills under the same head

`journey skill-pills.md / verify` failed on this head at run
[45602903068](https://github.com/valentinyeo/multiprompt-android/actions/runs/45602903068) with
`Pill picker missing after typing "/ "`, then `JOURNEY_ACTION_UNREACHABLE` on its own retry.
A fresh dispatch of the same journey on `main` ([45608796642](https://github.com/valentinyeo/multiprompt-android/actions/runs/45608796642))
fails at the same two lines, so the picker flake and the dead retry are the base's, not this
branch's. The retry calls `fill 'Message verify-session' ...`, which resolves the field by its
placeholder label, and the placeholder is gone once the attempt has typed anything, so a retry
could never succeed. It now clears the focused field by class and retypes the draft before the
second attempt, and each attempt waits for the picker row instead of reading one dump. The
assertions are unchanged: `Please / this` in the composer and `/pill-guide-01` in the picker.
On the branch head the journey passes
([45616338846](https://github.com/valentinyeo/multiprompt-android/actions/runs/45616338846)):
the composer reads `Please / this`, the picker opens, the pill inserts, the sent message carries
one pill and the host receipt matches. Cursor placement and composing text are unchanged by this
change; the caret sat where the driver left it because the keyboard was composing the word it had
just typed, which happens on `main` too.

### Round 5: live picker state and IME composition

The picker on both revisions calls `NativeSkills.query` with the current `TextFieldValue.text`
and collapsed `selection.start`. Head reads that value inside the bottom-bar composition;
main reads it in the reader composition. Neither reads the persisted draft. The 600 ms save
observer does not supply picker state. The span fast path only returns early when the draft
has no slash; it does not participate in query detection.

Fresh controls on main `81db9f32` pass in
[45649263400](https://github.com/valentinyeo/multiprompt-android/actions/runs/45649263400),
but fail the same initial picker step in
[45651351366](https://github.com/valentinyeo/multiprompt-android/actions/runs/45651351366) and
[45652601987](https://github.com/valentinyeo/multiprompt-android/actions/runs/45652601987).
Both failures retain the exact `Please / this` text but no picker, followed by the old
placeholder-based retry failure. The latest nightly
[45540371074](https://github.com/valentinyeo/multiprompt-android/actions/runs/45540371074)
also fails this step. Head `eac2c517` passes
[45649467911](https://github.com/valentinyeo/multiprompt-android/actions/runs/45649467911)
after one retry. These observations do not establish a head-only regression.

The failed head recording in
[45642363255](https://github.com/valentinyeo/multiprompt-android/actions/runs/45642363255)
shows `Please / |this`: Gboard is composing the following word and its first Left event
leaves the caret after the space, not after the slash. A slash query is correctly absent
there. The driver now dismisses the IME to finish that composition before sending Left.
The exact draft, picker row, required scroll, inserted pill, removal, file-path exclusion,
built-in command and independent send-receipt assertions are unchanged.

`ComposerLongDraftTest.movingBackToAMidMessageSlashOpensSkillsBeforeTheDraftIsSaved`
drives the actual composer: type `Please this`, insert `/ ` at offset 7, press Left,
require the visible skill, select it and require exact `Please /review-native this`.
The persisted draft must still be empty. Both debug and M3 unit jobs pass at `b3fda4d0`
([45649849078](https://github.com/valentinyeo/multiprompt-android/actions/runs/45649849078));
the debug artifact's `ComposerLongDraftTest` XML has five tests, zero failures or errors.
This guards immediate live-text/caret lookup independently of Gboard and of the save timer.
No additional product change is justified by these controls.

The final controlled series uses the same repaired driver at `3777d558` for both APKs,
with four fresh emulators per side on the same day. Each dispatch pins `app_ref` to either
head `3777d5586ad16cd65036017550088d32928b1ab4` or main
`81db9f326af3e0ff4ab20eb68d60792180552a11`. Independent `trial` IDs separate only the
manual dispatch concurrency groups; the normal PR groups, cancellation policy and all
journey/verdict assertions remain in place. No cached parent journey is used.

| Trial | Head run | Main control run |
| --- | --- | --- |
| 1 | [45655160973](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655160973) | [45655183498](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655183498) |
| 2 | [45655166614](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655166614) | [45655188572](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655188572) |
| 3 | [45655171326](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655171326) | [45655192857](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655192857) |
| 4 | [45655176807](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655176807) | [45655196519](https://github.com/valentinyeo/multiprompt-android/actions/runs/45655196519) |

The PR Verification records the observed pass counts and artifact inspection. Four trials
measure this sample's failure rate, not a guarantee that the journey can never flake.
The earlier cancelled runs and a short-SHA checkout failure are excluded. App product source
is unchanged from the accepted performance head; emulator frame numbers remain
**emulator, not representative**. No further performance runs were made in this round.
