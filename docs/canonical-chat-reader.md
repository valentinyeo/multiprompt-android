# Canonical chat reader

Desktop owns the reader and test corpus.
These files are unchanged from [multiprompt](https://github.com/valentinyeo/multiprompt) release `v0.28.1048`, commit `e62769a0862c8518a68a74ea6bfc6030b03dad3b`.
The full reader SHA-256 is `3c5c2f1923f2a5346e65ed7fc235aab998b9e67054535541f3ea18b51466cdfd`.
`tools/vendor/chat-reader/SHA256SUMS` lists vendored file hashes.
Hosted checks run all three canonical harnesses.
They cover 211 reader, 7 transcript, and 9 runtime tests.
Vendored fixtures and tests match Desktop without edits.

`tools/sync-chat-reader.sh --check` compares the bundled reader.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the desktop repository.
GitHub Actions can download the pinned file.
Both paths verify its full-file hash.
Without `--check`, the script restores that file.

The loader selects a runtime per Unix user.
It selects generation 5 and stores generations under `~/.multiprompt-runtime`.
Previously published Android 0.1.299-m3 bundles generation 3.
Its loader can select an installed newer generation.
Cooperating older clients cannot downgrade selection.
Conflicting or corrupt selections fail closed.
Imports do not install a runtime.
The selected inner payload SHA-256 is `e0830af90a1673a45ab1d70340b4dd5acfd915e4449c7a8d4579b91c7a1a9035`.
`engine_info` is optional diagnostic JSON.
Protocol version remains 1.
Existing history and serve transports remain unchanged.
The submitted state is durable before terminal handoff.
A SQLite writer lock spans the entire handoff.
Competing writers cannot adopt delivery while keys remain.

Preloader clients still run their embedded reader.
Selection cannot fence those older clients.
Running processes also keep their existing reader.
Immediate universal cutover is not promised.
Managed Claude delivery remains engine-owned.
A busy Claude can accept handoff with empty native input.
Draft, editor and selection checks still come first.
Native Codex and Hax lack a supported managed queue.
Raw terminal controls bypass that queue.
Real-provider release proof remains outstanding.
