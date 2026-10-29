# Codex transcript parity

Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.

The journey reads each desktop-originated post, sends `PHONE_CODEX`, independently checks its tmux receipt, and requires the matching transcript-only reply within five seconds of publication. It then reads and sends in the unchanged Claude fixture. Evidence includes per-session screenshots, XML, receipt files, reply visibility measurements, and a screen recording.

The workflow first runs the unchanged regression against the original bundled reader and requires its assertion failure, then requires the fixed reader to pass. No paid model requests run. Fixture replies prove transport and file-format compatibility, not a live provider response.
