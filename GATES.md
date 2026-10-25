# Gates: IGSH-844

OWNS: GATES.md, app/src/main/java/dev/multiprompt/companion/ui/**, app/src/test/**, tools/root-files.mjs, .github/workflows/test.yml

Scope: Mobile-only Grok-style chat, existing archive/remind/wait behaviour reachable through the existing overflow menu, JVM red/green proof on GitHub Actions, and an unmerged PR on ceo/IGSH-844.

All verification is reviewed from source and GitHub Actions evidence. No local test runner, Android build, emulator, adb, or gate-check scripts may run under the user's local-command restriction. Git and GitHub operations are used only for the requested worktree, commits, CI and PR.

- [x] G1: Fresh worktree starts from origin/main on ceo/IGSH-844; desktop and unrelated files remain unchanged.
  EVIDENCE: Worktree /home/valentin/projects/mpa-IGSH-844 was created with git worktree add -b ceo/IGSH-844 from origin/main be55a0c837d017cf8688348cd5586bd37e66801b. Reviewed diff: Android UI/tests, CI screenshot/JVM-result artifact upload, this ledger and its required root-file allowlist entry only; original worktree's error.log is untouched.

- [x] G2: Chat uses theme-owned near-black colours, rounded cards, system typography, letter choice badges, floating controls, bottom chevron and composer with inside microphone and visual-only waveform.
  EVIDENCE: Manually reviewed both 390x844 CI screenshots (chat-live.png and chat-view.png) from chat-regression-proof in run 40209679429 at app commit 7bf9e77: near-black background, full-width left-aligned 24dp cards, round overlay controls, blue A/B badges, plus/input/mic/decorative waveform and centered bottom chevron. All ReaderStyleTest, ReaderChromeTest and composer geometry assertions passed in that run; its separate reminder idling failure remains tracked by G5. Theme tokens live in ui/Theme.kt:42-70, reader styling is scoped to ReaderScreen, and the waveform has no click action.

- [ ] G3: Archive session, Remind me and Waiting exist only in the existing overflow menu and retain their existing callbacks, toggles and enabled rules without new blocking state.
  EVIDENCE: pending

- [x] G4: New JVM menu regression tests fail on GitHub Actions before implementation for the intended missing menu actions, not a compilation error.
  EVIDENCE: Red commit 925a6f9, https://github.com/valentinyeo/multiprompt-android/actions/runs/40207747870, compiled successfully. testM3UnitTest reported 659 tests, exactly four failures: archiveFromOverflowKeepsTheExistingDirectArchiveBehaviour (callback count), remindFromOverflowOpensTheExistingPickerAndDispatchesItsDueTime (missing menu item), waitingFromOverflowTogglesBackToOpenEvenWhileSending (missing Waiting label), sessionActionsAreNotDuplicatedAboveTheComposer (old buttons remain). All failures are AssertionError, not compiler errors.

- [ ] G5: All new tests and tools/check.sh pass on GitHub Actions after implementation; tests cover actual menu reachability, dispatch and state and reject broken wiring.
  EVIDENCE: pending

- [x] G6: No new code comments, dependencies, timeout, cap, blocking state or telephone mode; all styling tokens live in ui/Theme.kt.
  EVIDENCE: Reviewed complete origin/main diff: no dependency, ViewModel, SSH, reader connection, dictation, worker or desktop changes. Existing send/attach/mic enabled expressions are retained; archive/remind/wait have no new enabled conditions. The waveform is a non-clickable Box. New colours, radii, spacing, control dimensions and typography are Theme.kt tokens. GitHub Actions run 40209679429 passed the no-comments and root-file checks. No local Android build, test, emulator, adb or gate scripts were run; the reminder fixture uses the repository's native-renderer/test-dispatcher pattern and adds no timeout.

- [ ] G7: Changes committed and pushed; PR has the required title, change description, red/green evidence and CI tools/check.sh note, and remains unmerged.
  EVIDENCE: pending
