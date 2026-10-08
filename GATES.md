# Gates: Android transcript chat

OWNS: app/src/main/java/dev/multiprompt/companion/MainViewModel.kt, app/src/main/java/dev/multiprompt/companion/reader/**, app/src/main/java/dev/multiprompt/companion/ssh/SshRepository.kt, app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt, app/src/test/java/dev/multiprompt/companion/reader/**, GATES.md

Scope: Read bounded Claude/Codex transcripts over persistent SSH exec, render ordered role bubbles with notification filtering, preserve tmux fallback and actions, test and commit locally on transcript-chat without pushing.

- [x] G1: Wire parsing validates correlation, identity, framing, roles, UTF-8/base64, same replies and the message/byte bounds
  CHECK: ANDROID_HOME=/home/valentin/tools/android-sdk ./gradlew testDebugUnitTest
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=0465f3973add411924b468cb49b379e84da20769255bf45aa8c66bfa0ca67d2b; exit=0; EXPECT=matched; output-sha256=9bb04541747e08e92a6d0739d3a2d2b161aa6532488d70ec55ffc5e2705dd8bd; output-bytes=1853; shell=/bin/sh; cwd=/home/valentin/projects/mpa-transcript; path=695691a31144/31 entries

- [x] G2: Android lint accepts the integrated reader and Compose changes
  CHECK: ANDROID_HOME=/home/valentin/tools/android-sdk ./gradlew lintDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=f1f8c37ad39e55e434d223bda0bd6d5a8ce0c18c2fb4d02384aba2e702ba0702; exit=0; EXPECT=matched; output-sha256=4f6bf341b2f2fd20b25948c4f22bf1f5d24f57f22a344fe35965cf69456a9208; output-bytes=1730; shell=/bin/sh; cwd=/home/valentin/projects/mpa-transcript; path=695691a31144/31 entries

- [x] G3: Persistent transcript channel uses six fields, retains SID/token/cwd, reconnects safely, and falls back for missing helper or unsupported agents
  EVIDENCE: Reviewed SshRepository.kt:225-277: one non-PTY exec session, sequential request IDs, strict framing/identity parser, 20s response deadline, SID/token retained with original cwd, periodic tmux re-resolution, channel cleanup. SessionReaderConnection.kt:283-318 restricts Claude/Codex, maps missing/failure to null fallback, retries at 3-30s, and distinguishes timeout from parent cancellation. MainViewModel.kt:596 supplies known cwd. Compiled production parser also accepted four real mp_complete.py --serve responses in one local process: Claude ok (Unicode user + assistant), token-based same, Codex ok, missing; REAL_HELPER_COMPATIBILITY_PASSED. This proves stdio compatibility and source integration, not live SSH/device QA.

- [x] G4: Chat bubbles retain provider order and roles, hide task-notification user rows, and leave send/Enter/interrupt on tmux
  EVIDENCE: MultipromptApp.kt:3398-3438 iterates the authoritative list without sorting or tmux inference; user cards are right-aligned and labelled You, assistant cards left-aligned and labelled with the agent, both retain text/links and recorded timestamps. TranscriptProtocol.kt:60-79 validates role and appends in wire order, filtering only user task-notifications; unit tests include a visible assistant notification and a user mention as negative controls, an all-hidden valid conversation, and the 96/97 boundary. Technical mode still shows raw tmux; structured mode disables misleading TUI Load older. SessionReaderConnection.kt:92-115 and :389-410 retain the original action queue/fresh-client tmux send and key operations; no prompt uses the helper. Final Gradle run: 200 tests, zero failures/errors/skips; 16 transcript tests. Lint: zero errors. Source/compile review, not on-device visual QA.

- [x] G5: Only task changes are committed on transcript-chat and no push is performed
  EVIDENCE: git commit produced 3140a13 on transcript-chat containing exactly the six task implementation/test paths reviewed with git diff --cached --check/stat. git status afterward had no tracked changes, only this new ledger and the user's pre-existing untracked docs/parity-audit-2026-10-08.md; the audit is untouched and excluded. No push, amend, branch mutation, or destructive git command was run. This ledger is committed separately to preserve the measured commit proof.
