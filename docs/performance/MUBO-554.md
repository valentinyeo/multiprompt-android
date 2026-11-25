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
