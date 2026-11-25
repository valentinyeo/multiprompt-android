# Codex transcript parity

Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.

The journey reads each desktop-originated post, sends `PHONE_CODEX`, independently checks its tmux receipt, and requires the matching transcript-only reply within five seconds of publication. It then reads and sends in the unchanged Claude fixture. Evidence includes per-session screenshots, XML, receipt files, reply visibility measurements, and a screen recording.

The workflow first runs the unchanged regression against the original bundled reader and requires its assertion failure, then requires the fixed reader to pass. No paid model requests run. Fixture replies prove transport and file-format compatibility, not a live provider response.

## Reader ownership

The same journey proves `reader.lifecycle` for Hax and Claude. Each reader first performs real history and snapshot work. Home hides the app. After three seconds to settle, two host-side request counts four seconds apart must be equal. The fixture publishes a transcript reply while hidden. Returning to the app must show it and resume both request counts. While native Codex is open, the warm Hax reader must also remain idle. Closing Codex and returning to Hax must show another newly published reply.

The SSH fixture observes actual history query lines and each snapshot capture. It does not infer idleness from a status label or a channel-open count. Evidence retains `reader-requests.jsonl`, per-stage counts, result files, resumed XML and screenshots, and the independent receipt after the second reader closes. `tools/reader-ownership/receipts.py` rejects a missing positive control or any extra hidden request with `HIDDEN_READER_IO`.

Hosted JVM tests also cover two observers of one owner, closing only the last lease, bounded reconnect after silence, and the older two-reader send-lock failure from #241 (`66e2700e`). `tools/reader-ownership/check.test.py` replays the real ungated implementations before #328 (`39a1b95a`) and #341 (`c3dc2a3b`). These source checks prove the ownership rule rejects those implementations. The SSH journey proves runtime behavior. No old source is copied into the current Android app.

The remote snapshot command must exit on hangup, termination or a broken output pipe. A cleanup-only signal trap does not stop its capture loop. `TmuxCommandsTest.closingTheSnapshotPipeStopsRemoteCapturesWithoutDisconnectingTheHost` runs the generated shell command against an isolated tmux fixture and closes its output pipe. The real SSH journey catches a leaked remote loop even when Android has stopped consuming it.

## Native fixture contracts

The native Codex fixture uses a UUID thread ID and the actual `codex queue --thread … --message …` boundary. Its isolated executable is a copied Python binary named `codex`; the `queue` fixture script validates the requested thread and appends the reply and receipt. This satisfies the existing strict process, open-transcript and owner checks without weakening them. `tools/reader-ownership/codex-fixture.test.py` drives the real connector against this fixture and rejects a different thread. The Claude fixture accepts one bracketed paste, including its correlation tag, as one native transcript occurrence.

`reader-ui.json` retains text and bounds extracted from the numbered XML captures. Missing or duplicate text fails rather than manufacturing a result. The registry prioritizes this witness, lifecycle counts and independent receipts for the verifier. These fixtures prove reader transport and lifetime, not live AI responses.

## Streaming pane budget

`reader.pane-budget` first opens Terminal detail and independently observes full-scrollback captures. Back in native chat, `STREAM_531` publishes one Claude reply in four held stages with a stable message ID. Two native XML captures must show its first text and its later addition. Actual SSH captures during that interval must all request only the visible pane.

The phone then opens the independent terminal fallback while that reply remains unfinished. It must attach to `claude-control` and render `TERMINALALPHA`, then `TERMINALOMEGA`, in its production accessibility TextView, with an XML/screenshot pair for each update. The first terminal state must not contain the second marker. Host stage 3 and the prompt receipt independently confirm the reply is still streaming and was sent once. `pane-stream-result.json`, `pane-stream-proof.txt`, XML, PNGs, extracted terminal text and the raw capture-scope log retain the proof. This uses a deterministic provider fixture, not a paid model.

## Working flag

`reader.working-flag` rides the same run. `STREAM_531`'s two held stages prove the reader still adds a new host row while every unchanged pane frame is skipped, so the flag's input path cannot freeze a streaming chat. The flag itself is `ReaderState.working`, computed on IO from the last 200 pane lines; `PaneFrameStateTest` pins it to the previous whole-pane Main-thread read on the working, quiet, spinner-only and Pi panes. The dots those files paint are driven by `chat-working.md` on the signed release.

`PaneStreamCostTest` runs on GitHub's JVM. It executes both production shell stream variants over the same synthetic 5,000-line scrollback and 32-line visible capture sequence, with 60 changing frames modeling one minute at 1 Hz. It records every framed wire byte, then runs the actual Android snapshot decoder on every frame. After three warmups, five alternating measurements retain median and per-run decode elapsed time in `unit-tests-results/pane-stream-cost.json`. These are JVM decoder timings, not physical-phone wall time or end-to-end reply latency. Framing and metadata remain included in the byte counts.
