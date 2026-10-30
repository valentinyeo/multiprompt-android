# Canonical chat reader

Desktop owns the reader and connectors.
Desktop also owns the test files.
Source: [Desktop v0.28.1078](https://github.com/valentinyeo/multiprompt/tree/0260632661bcb41d9fe1c14bfb5379f9eb7534a5).
Commit: `0260632661bcb41d9fe1c14bfb5379f9eb7534a5`.
The reader SHA-256 is `9ebc1e7d38fe6ee461a1f6fe09c9985a1cc93678300da0abeba31de95bc92826`.
The Claude module SHA-256 is `1c42945eec4dc66b5edb65f720dfc32a878609253ef3220d4b367eda56fec0ed`.
The Codex module SHA-256 is `07e448dc7d5a849cbbf4b914ce375e147f9343906f213bcda00a7d7663757a31`.
Android copies Desktop's ordered base64 prefix.
Bundle length: 223175 bytes.
Bundle SHA-256: `47567abeb0277d32e12648267bdd73acc9c902aaefd3d68f7a6f9028ab79af4a`.

`tools/sync-chat-reader.sh --check` verifies all three assets against Desktop.
Locally, set `MULTIPROMPT_DESKTOP_REPO` to the Desktop repository.
GitHub Actions can download the pinned files.
Without `--check`, the script restores those files.
`tools/vendor/chat-reader/SHA256SUMS` lists the vendored test corpus hashes.

The bundled reader supplies generation 20.
A newer compatible host runtime takes precedence.
Android requires generation 20 or later.
Message protocol 1 is required.
Claude and Codex use host message rules.
HAX keeps its previous message path.
The host owns message delivery, attempts and deadlines.
Both apps need matching message and repair checks.
Real agent sessions still need hosted checks.
Native provider pickup needs separate proof.
