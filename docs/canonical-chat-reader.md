# Canonical chat reader

Desktop owns the reader and regression corpus. The bundled reader and `tools/vendor/chat-reader/tools` are unmodified. Both come from [multiprompt](https://github.com/valentinyeo/multiprompt) commit `af3ea232f557386345bd9628fa28c97e5d038deb`. Reader SHA-256: `07e8a53f7c241aced88bb115d84d4884da0556651ef81fe63b37050772fca6bb`. Reader harness SHA-256: `da5f35a7cc02db2a4f8eff7679771bb0dc0946e6b84c68fe5fe2b9b948d79997`. `tools/vendor/chat-reader/SHA256SUMS` records every vendored file hash.

The harness contains synthetic fixtures. It uses vendored fixtures, reader service and imports. It also uses the red-run checker. The transcript harness imports that reader harness. Hosted checks copy files unmodified, preserving relative paths. The bundled reader goes at `src/chat_history_reader.py`. No separate fixture expectations are created.

`tools/sync-chat-reader.sh --check` requires `MULTIPROMPT_DESKTOP_REPO` locally. It must contain the pinned desktop Git commit. GitHub Actions can download its immutable official URL. Both paths verify the pinned SHA-256 before comparison. Without `--check`, the script restores only that reader. It never selects a newer branch.

Android transport bootstraps this embedded source. It is not a shared installed runtime. Managed Claude delivery status remains engine-owned. Native Codex sends use direct tmux transport. They lack a supported managed send queue. Terminal input and other raw controls bypass it. A common runtime remains future work. Provider capabilities also remain future work. So does older-writer coexistence. Real-provider release proof remains future work.
