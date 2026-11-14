# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/81b8d2226455d4d76c0c2ec596c3a0479011bd09).
Commit: `81b8d2226455d4d76c0c2ec596c3a0479011bd09`.
The reader SHA-256 is `01b5c32842908663eeab3b84294bdbde628f48f51b4fef82db40940bf5f549eb`.
The Claude module SHA-256 is `7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 456784 bytes.
Bundle SHA-256: `53ab6329f27e6a255b04c052c575e1b9c8474af6570a11641b7b31975ec2d5d9`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader supplies generation 115.
A newer compatible host runtime takes precedence.
Android requires generation 22 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
