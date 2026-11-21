# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/f821d627aed08c62b4d0c47bb76ca1c58896b75b).
Commit: `f821d627aed08c62b4d0c47bb76ca1c58896b75b`.
The reader SHA-256 is `b7aa106a54cee586a64c4e9309fb887679d9275f1fdd7c46544b0f03b59bf1b6`.
The Claude module SHA-256 is `7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 510392 bytes.
Bundle SHA-256: `b99d07bc32278e6a6bc8749bb3f4ab3cd2977b8ecae0e6796179282684ce9921`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
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
