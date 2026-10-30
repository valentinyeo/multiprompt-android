# Canonical chat reader

Desktop owns the reader and test corpus.
These files are unchanged from [multiprompt](https://github.com/valentinyeo/multiprompt) commit `c490189d5d85ebf4c1c65ad801feac634994d5b3`.
The full reader SHA-256 is `cd4fbb1bacc93ff9bcb47fcceab5a5f2feae5de2ae992eccc70c17651bd6e296`.
`tools/vendor/chat-reader/SHA256SUMS` lists vendored file hashes.
Hosted checks run all three canonical harnesses.
They cover 191 reader, 7 transcript, and 8 runtime tests.
Fixtures and test expectations remain unchanged.

`tools/sync-chat-reader.sh --check` compares the bundled reader.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the desktop repository.
GitHub Actions can download the pinned file.
Both paths verify its full-file hash.
Without `--check`, the script restores that file.

The loader selects a runtime per Unix user.
It stores generations under `~/.multiprompt-runtime`.
Cooperating older clients cannot downgrade selection.
Conflicting or corrupt selections fail closed.
Imports do not install a runtime.
The inner payload has a separate hash.
`engine_info` is optional diagnostic JSON.
Existing history and serve transports remain unchanged.

Preloader clients still run their embedded reader.
Selection cannot fence those older clients.
Running processes also keep their existing reader.
Immediate universal cutover is not promised.
Managed Claude delivery remains engine-owned.
Native Codex lacks a supported managed queue.
Raw terminal controls bypass that queue.
Real-provider release proof remains outstanding.
