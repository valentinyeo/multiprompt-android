# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop v0.28.1080](https://github.com/valentinyeo/multiprompt/tree/1bbbb9958be4bbcc3451698a4a5450d1773047a5).
Commit: `1bbbb9958be4bbcc3451698a4a5450d1773047a5`.
The reader SHA-256 is `b4acba203981d203daf0d283b3abf20c873ba54f1a6dd104ab47184a341faea0`.
The Claude module SHA-256 is `1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 224961 bytes.
Bundle SHA-256: `999ef191b364af22994311f4d8b168e22c67a7103585a0c22e0e9c1cc7a9c316`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader supplies generation 22.
A newer compatible host runtime takes precedence.
Android requires generation 22 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
