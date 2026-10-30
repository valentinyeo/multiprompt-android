# Canonical chat reader

Desktop owns the reader and test corpus.
These files are unchanged from [multiprompt](https://github.com/valentinyeo/multiprompt) commit `920dffe80a424393d74e5bd0b8514b6bddb2df12`.
The full reader SHA-256 is `27e681fab0bbebf4620f0ff47c66d1fcc4442152fb1c7046eac376726a2672d0`.
`tools/vendor/chat-reader/SHA256SUMS` lists vendored file hashes.
Hosted checks run all three canonical harnesses.
They cover 196 reader, 7 transcript, and 9 runtime tests.
Fixtures and test expectations remain unchanged.

`tools/sync-chat-reader.sh --check` compares the bundled reader.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the desktop repository.
GitHub Actions can download the pinned file.
Both paths verify its full-file hash.
Without `--check`, the script restores that file.

The loader selects a runtime per Unix user.
It selects generation 3 and stores generations under `~/.multiprompt-runtime`.
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
A busy Claude can accept handoff with empty native input.
Draft, editor and selection checks still come first.
Native Codex and Hax lack a supported managed queue.
Raw terminal controls bypass that queue.
Real-provider release proof remains outstanding.
