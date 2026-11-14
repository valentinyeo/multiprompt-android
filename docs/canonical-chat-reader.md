# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop master](https://github.com/valentinyeo/multiprompt/tree/58ab73a2315f0c15bec1fb8e2a0abc2ad388578a).
Commit: `58ab73a2315f0c15bec1fb8e2a0abc2ad388578a`.
The reader SHA-256 is `0f063d430a61e8e990df59909c6ba1bf9698f5e3a83f2e236308711fde78df40`.
The Claude module SHA-256 is `7b181305096a39c3ac4276607a3177177f23ca0a88535c96604c2b3f393d1729`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 489368 bytes.
Bundle SHA-256: `6a71927b076b2852694fab9472c33ebc4de297f5688c535f4cbbecc30bb1ce37`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader supplies generation 120.
A newer compatible host runtime takes precedence.
Android requires generation 22 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
