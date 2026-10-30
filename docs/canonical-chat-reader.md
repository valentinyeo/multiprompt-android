# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop v0.28.1075](https://github.com/valentinyeo/multiprompt/tree/0f1fb9ee3ab9cc427273099c7baf6dffe78d2916).
Commit: `0f1fb9ee3ab9cc427273099c7baf6dffe78d2916`.
The reader SHA-256 is `bbcc265ea2dc816b8d66024b9fc8c67236e53cde27e0712eeae39153153c7f4f`.
The Claude module SHA-256 is `1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 222844 bytes.
Bundle SHA-256: `c93f6148e0437b2c9588ce0dc49277202ab25bf8a201db4f63bfe107a5e1dc06`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader selects generation 17.
A newer compatible host runtime takes precedence.
Android checks runtime identity before sending messages.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
