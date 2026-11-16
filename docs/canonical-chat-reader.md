# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/605597a163923914989a0cae06f727a83afbcd5a).
Commit: `605597a163923914989a0cae06f727a83afbcd5a`.
The reader SHA-256 is `13ff821c39e8e81b700016b054c92f03a474c6b7ca9e52e03979e17bb2b7012c`.
The Claude module SHA-256 is `7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 501543 bytes.
Bundle SHA-256: `1331a99fec06fd45db0cfcebd8bf803c81e8dc129512613a4f46c5bac139d19d`.

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
