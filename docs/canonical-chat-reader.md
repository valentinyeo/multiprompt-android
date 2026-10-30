# Canonical chat reader

Desktop owns the reader and test corpus.
These files are unchanged from [multiprompt](https://github.com/valentinyeo/multiprompt) commit `fc76d5714ced43500552fa0398f8b2f56ee41bd5`.
The full reader SHA-256 is `47a2a75d4c670ab21224ea166d512616694881cb484106e17851fbaef71dd508`.
`tools/vendor/chat-reader/SHA256SUMS` lists vendored file hashes.
Hosted checks run all three canonical harnesses.
They cover 208 reader, 7 transcript, and 9 runtime tests.
Vendored fixtures and tests match Desktop without edits.

`tools/sync-chat-reader.sh --check` compares the bundled reader.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the desktop repository.
GitHub Actions can download the pinned file.
Both paths verify its full-file hash.
Without `--check`, the script restores that file.

The loader selects a runtime per Unix user.
It selects generation 4 and stores generations under `~/.multiprompt-runtime`.
Previously published Android 0.1.299-m3 bundles generation 3.
Its loader can select an installed newer generation.
Cooperating older clients cannot downgrade selection.
Conflicting or corrupt selections fail closed.
Imports do not install a runtime.
The selected inner payload SHA-256 is `ca6c0abf9b1af9ede90f3645b6449c6c7b57a02213dd6e3f7957f6885220ba66`.
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
