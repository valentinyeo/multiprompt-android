# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/4e43135910fd38cb8939506a29a07c2ea62c1066).

`tools/sync-chat-reader.sh` holds the one pin: the desktop revision and the SHA-256 of every bundled file.
It copies those files into `app/src/main/assets/` and writes `app/src/main/assets/chat_history_reader.sha`, which holds the revision on its first line and the bundle length and SHA-256 on its second.
`tools/chat-reader-bundle.py` computes that fingerprint from the bundled bytes.
Never edit the bundled files or the pin by hand: re-pin by changing the revision in the script and running it.

`tools/sync-chat-reader.sh --check` verifies every asset and the pin against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files and the pin.
`tools/check-canonical-chat-reader.sh` runs it on every pull request.
`ChatReaderBundleTest` compares the reader bundle the app assembles against the pin on a phone.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

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
