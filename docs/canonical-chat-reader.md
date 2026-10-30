# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop v0.28.1079](https://github.com/valentinyeo/multiprompt/tree/d09a90f3f64e63543bffaec5a3b93050f81166a0).
Commit: `d09a90f3f64e63543bffaec5a3b93050f81166a0`.
The reader SHA-256 is `00cf3dbbd01e3fdc872dbd059009c513966374386895b008706f92f6cd62841e`.
The Claude module SHA-256 is `1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 224140 bytes.
Bundle SHA-256: `43d0205c4f673178f116e53bc702db0080be4d613ee66a8e31427e531ce7a44f`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader supplies generation 21.
A newer compatible host runtime takes precedence.
Android requires generation 21 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
