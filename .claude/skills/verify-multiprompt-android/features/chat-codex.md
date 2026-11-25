# Codex transcript parity

Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.

The journey reads each desktop-originated post, sends `PHONE_CODEX`, independently checks its tmux receipt, and requires the matching transcript-only reply within five seconds of publication. It then reads and sends in the unchanged Claude fixture. Evidence includes per-session screenshots, XML, receipt files, reply visibility measurements, and a screen recording.

The workflow first runs the unchanged regression against the original bundled reader and requires its assertion failure, then requires the fixed reader to pass. No paid model requests run. Fixture replies prove transport and file-format compatibility, not a live provider response.

## Reader ownership

The same journey proves `reader.lifecycle` for Hax and Claude. Each reader first performs real history work, and its positive control is the history query, because a native chat holds no pane lease and captures no pane. Home hides the app. After three seconds to settle, two host-side request counts four seconds apart must be equal. The fixture publishes a transcript reply while hidden. Returning to the app must show it and resume the history requests. While native Codex is open, the warm Hax reader must also remain idle. Closing Codex and returning to Hax must show another newly published reply.

The SSH fixture observes actual history query lines and each snapshot capture. It does not infer idleness from a status label or a channel-open count. Evidence retains `reader-requests.jsonl`, per-stage counts, result files, resumed XML and screenshots, and the independent receipt after the second reader closes. `tools/reader-ownership/receipts.py` rejects a missing positive control, any extra hidden request of either kind, and a resumed kind that never grew, with `HIDDEN_READER_IO`.

Hosted JVM tests also cover two observers of one owner, closing only the last lease, bounded reconnect after silence, and the older two-reader send-lock failure from #241 (`66e2700e`). `tools/reader-ownership/check.test.py` replays the real ungated implementations before #328 (`39a1b95a`) and #341 (`c3dc2a3b`). These source checks prove the ownership rule rejects those implementations. The SSH journey proves runtime behavior. No old source is copied into the current Android app.

The remote snapshot command must exit on hangup, termination or a broken output pipe. A cleanup-only signal trap does not stop its capture loop. `TmuxCommandsTest.closingTheSnapshotPipeStopsRemoteCapturesWithoutDisconnectingTheHost` runs the generated shell command against an isolated tmux fixture and closes its output pipe. The real SSH journey catches a leaked remote loop even when Android has stopped consuming it.

## Native fixture contracts

The native Codex fixture uses a UUID thread ID and the actual `codex queue --thread … --message …` boundary. Its isolated executable is a copied Python binary named `codex`; the `queue` fixture script validates the requested thread and appends the reply and receipt. This satisfies the existing strict process, open-transcript and owner checks without weakening them. `tools/reader-ownership/codex-fixture.test.py` drives the real connector against this fixture and rejects a different thread. The Claude fixture accepts one bracketed paste, including its correlation tag, as one native transcript occurrence.

`reader-ui.json` retains text and bounds extracted from the numbered XML captures. Missing or duplicate text fails rather than manufacturing a result. The registry prioritizes this witness, lifecycle counts and independent receipts for the verifier. These fixtures prove reader transport and lifetime, not live AI responses.

## Streaming pane budget

`reader.pane-budget` first opens Terminal detail and independently observes full-scrollback captures. Back in native chat, `STREAM_531` publishes one Claude reply in four held stages with a stable message ID. Two native XML captures must show its first text and its later addition, and the reply must arrive through history queries. Actual SSH captures during that interval must be empty: the chat history view holds no pane lease, so no capture runs and no snapshot frame is sent while the reply streams.

The chat view also has to keep its structured controls with no pane text. While the fixture holds the reply, one capture shows the working dots and the structured model choices. A structured question from the transcript then has to reach the chat list as a card whose answer options are enabled, in the capture taken while the reply is still streaming. `pane-stream-chat-awaiting.xml`, `pane-stream-chat-updated.xml` and `pane-stream-question.jsonl` retain those three controls.

The phone then opens the independent terminal fallback while that reply remains unfinished. It must attach to `claude-control` and render `TERMINALALPHA`, then `TERMINALOMEGA`, in its production accessibility TextView, with an XML/screenshot pair for each update. The first terminal state must not contain the second marker. Host stage 3 and the prompt receipt independently confirm the reply is still streaming and was sent once. `pane-stream-result.json`, `pane-stream-proof.txt`, XML, PNGs, extracted terminal text and the raw request log retain the proof. This uses a deterministic provider fixture, not a paid model.

`PaneStreamBudgetTest` runs on GitHub's JVM. It executes the production shell stream over a synthetic 5,000-line scrollback with 60 changing frames modeling one minute at 1 Hz, records every framed wire byte, and runs the actual Android snapshot decoder on every frame. It then wires the production `paneFrames` gate the way the chat history view does, holding only the chat lease, and requires zero captures, zero frames and zero wire bytes for the same minute. After three warmups, five alternating measurements retain the median decode elapsed time in `unit-tests-results/pane-stream-budget.json`. These are JVM decoder timings, not physical-phone wall time or end-to-end reply latency. Framing and metadata remain included in the byte counts, and `PaneFramesTest` pins the gate decision itself.
