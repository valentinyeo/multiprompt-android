# Canonical chat reader

Desktop owns the reader and its regression corpus. The bundled reader and files under `tools/vendor/chat-reader/tools` come unmodified from [multiprompt](https://github.com/valentinyeo/multiprompt) commit `af3ea232f557386345bd9628fa28c97e5d038deb`. The reader SHA-256 is `07e8a53f7c241aced88bb115d84d4884da0556651ef81fe63b37050772fca6bb`. The reader harness SHA-256 is `da5f35a7cc02db2a4f8eff7679771bb0dc0946e6b84c68fe5fe2b9b948d79997`. `tools/vendor/chat-reader/SHA256SUMS` records every vendored file hash.

The harness contains synthetic fixtures and uses the vendored fixture files, reader service, red-run checker and its imports. The transcript harness imports the same reader harness. The hosted check copies these unmodified files into their original relative layout and places the bundled reader at `src/chat_history_reader.py`; it does not create separate fixture expectations.

`tools/sync-chat-reader.sh --check` requires `MULTIPROMPT_DESKTOP_REPO` to point at a local desktop Git repository containing the pinned commit. On GitHub Actions, without that variable, it downloads the file from the immutable official commit URL. Both paths verify the pinned SHA-256 before comparing the bundle. Running without `--check` restores only that pinned reader; it never selects a newer branch.

The current Android transport bootstraps this embedded source; it is not a shared installed runtime. Managed Claude delivery status remains engine-owned. Native Codex sends still use the existing direct tmux transport, not a supported managed send queue. Terminal input and other raw controls remain outside that queue. A common runtime, provider capabilities, coexistence with older writers, and real-provider release proof remain future work.
