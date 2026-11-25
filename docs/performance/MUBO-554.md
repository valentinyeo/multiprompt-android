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

This is the debug APK on GitHub's software-rendered emulator, not release 0.1.748 on a
physical phone. FrameMetrics duration is a rendering measure, not key-to-screen latency.
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
