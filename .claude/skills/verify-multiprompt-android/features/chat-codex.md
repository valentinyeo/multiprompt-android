# Codex transcript parity

Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.

The journey reads each desktop-originated post, sends `PHONE_CODEX`, independently checks its tmux receipt, and requires the matching transcript-only reply within five seconds of publication. It then reads and sends in the unchanged Claude fixture. Evidence includes per-session screenshots, XML, receipt files, reply visibility measurements, and a screen recording.

The workflow first runs the unchanged regression against the original bundled reader and requires its assertion failure, then requires the fixed reader to pass. No paid model requests run. Fixture replies prove transport and file-format compatibility, not a live provider response.

## Reader ownership

The same journey proves `reader.lifecycle` for Hax and Claude. Each reader first performs real history and snapshot work. Home hides the app. After three seconds to settle, two host-side request counts four seconds apart must be equal. The fixture publishes a transcript reply while hidden. Returning to the app must show it and resume both request counts. While native Codex is open, the warm Hax reader must also remain idle. Closing Codex and returning to Hax must show another newly published reply.

The SSH fixture observes actual history query lines and each snapshot capture. It does not infer idleness from a status label or a channel-open count. Evidence retains `reader-requests.jsonl`, per-stage counts, result files, resumed XML and screenshots, and the independent receipt after the second reader closes. `tools/reader-ownership/receipts.py` rejects a missing positive control or any extra hidden request with `HIDDEN_READER_IO`.

Hosted JVM tests also cover two observers of one owner, closing only the last lease, bounded reconnect after silence, and the older two-reader send-lock failure from #241 (`66e2700e`). `tools/reader-ownership/check.test.py` replays the real ungated implementations before #328 (`39a1b95a`) and #341 (`c3dc2a3b`). These source checks prove the ownership rule rejects those implementations. The SSH journey proves runtime behavior. No old source is copied into the current Android app.

The remote snapshot command must exit on hangup, termination or a broken output pipe. A cleanup-only signal trap does not stop its capture loop. `TmuxCommandsTest.closingTheSnapshotPipeStopsRemoteCapturesWithoutDisconnectingTheHost` runs the generated shell command against an isolated tmux fixture and closes its output pipe. The real SSH journey catches a leaked remote loop even when Android has stopped consuming it.
