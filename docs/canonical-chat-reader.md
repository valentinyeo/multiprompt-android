# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/ee643caf5b5c32567790124c6209942b2a95d86b).

`tools/sync-chat-reader.sh` holds the one pin: the desktop revision and the SHA-256 of every bundled file.
It copies those files into `app/src/main/assets/`, copies the pinned vendored tests and their fixtures into `tools/vendor/chat-reader/`, and writes two pin files from that one revision: `app/src/main/assets/chat_history_reader.sha` for the app, and `app/src/androidTest/assets/chat_reader_pin.txt` with the revision and the bundle length and SHA-256 for the phone test.
`tools/chat-reader-bundle.py` computes that fingerprint from the bundled bytes.
Never edit the bundled files or the pin files by hand: re-pin by changing the revision in the script and running it.

`tools/sync-chat-reader.sh --check` verifies every asset and the pin against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files and the pin.
`tools/check-canonical-chat-reader.sh` runs it on every pull request.
`ChatReaderBundleTest` compares the reader bundle the app assembles against those pin files on a phone.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.
`tools/sync-chat-reader.sh --check` also proves the vendored `multibot-feedback-test.py`, `chat-history-reader-test.py` and the fixtures that test reads match Desktop byte for byte, so a stale copy cannot outlive a re-pin.
The bundled reader supplies generation 126.
A newer compatible host runtime takes precedence.
Android requires generation 22 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
