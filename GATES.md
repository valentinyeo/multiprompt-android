# Gates: Android account workspaces and published release

Scope: ship desktop-compatible account auth, workspace synchronization and All Sessions on Android; preserve legacy folders and hosts; deploy the app redirect on the Yeo UX worker without changing desktop auth; publish and verify main CI release.

Solo work. Ubuntu checks use the CI versions: Temurin JDK 17, Android SDK 36, Gradle 9.5.1. GATES.md and /tmp/mpa-account-proof.py are local evidence, never committed. The proof script was authored and inspected in this session; it contains only source/manifest/test-result checks, Git/GitHub read-only queries, release downloads and credential-safe Cloudflare source readback. Production verification temporarily creates/removes test-only device/code rows and never changes layout or real devices. G4 checks implementation wiring, not physical-phone UI behavior; owner OTP and actual phone SSH attachment are explicitly outside the runtime evidence.

- [x] G0: ledger has valid outcome checks
  CHECK: node /home/valentin/.agents/skills/unlazy/scripts/gate-lint.mjs GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=69f81179f3934636347ff01de4824d2a1f500f2d29238b9fd688f82f77d1c57b; exit=0; EXPECT=matched; output-sha256=497e1b1fd83aa8c97730b46891c668bfc53799ce4cdaa54b7d44728bf3aca6e2; output-bytes=150; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G1: worker tests prove Android redirect allowlisting, state and PKCE, and unchanged desktop callback flow
  CHECK: npm test
  CWD: /home/valentin/projects/zigshell/service
  EXPECT: # fail 0
  EVIDENCE: automatic-evidence=v1; definition-sha256=6ce9e1903f6e9f9be9509003d6aae3a09138a9f981c5f129db5ff9178c4b7446; exit=0; EXPECT=matched; output-sha256=75427a58f6931f1777b488ccaa8ed8d9ea4eecb239f9f80f2f5b2ad2cef488fc; output-bytes=2970; shell=/bin/sh; cwd=/home/valentin/projects/zigshell/service; path=695691a31144/31 entries

- [x] G2: Android unit tests prove desktop layout parsing and ordering, edits, conflict replay, empty-account preservation, host mapping, and auth expiry/error/state handling
  CHECK: JAVA_HOME=/tmp/mpa-jdk17 ./gradlew test
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=fb6e6ae51ac8de89490c3be0ccd241d670a99371e59fea99a90640a0c05f5154; exit=0; EXPECT=matched; output-sha256=24234434a9246796944ebd2a070469fc69860c231d8c1fc3162a2849c99f04b2; output-bytes=1881; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G3: Android debug APK builds and lint passes with the CI toolchain
  CHECK: JAVA_HOME=/tmp/mpa-jdk17 ./gradlew assembleDebug lintDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=202ec9dedbf00cc6ad32a3c3f8fb99cb0ed66f68a85378a9005486b09312aabf; exit=0; EXPECT=matched; output-sha256=02b979856053adad0e5a6e2990777fd4c1aa40f3b2317f3b5d126696423e10c2; output-bytes=2726; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G4: compiled callback registration and account implementation wiring include Custom Tab/Keystore auth, workspace order/opening/editing, exhaustive inventory with membership/add, foreground polling/resume, and preserved hidden folder/host storage
  CHECK: python3 /tmp/mpa-account-proof.py wiring
  EXPECT: Account implementation wiring verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=a891f6c297634e2f840498fa8fa093067fda03cde50f049840fc4d10dd6472b4; exit=0; EXPECT=matched; output-sha256=d1d6c06b78b22931bee8afce72a39cfb61aef7c1a8d19c3c537bc5ab9fd5fce8; output-bytes=150; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G5: live Yeo UX deployment matches the tested worker bundle; Android/desktop code redirect binding, PKCE/replay, Access policy, D1 layout readback, conflicts, refresh and revoke are verified without exposing credentials or changing layout
  CHECK: python3 verify-deployment.py && python3 /tmp/mpa-account-proof.py worker-bundle
  CWD: /home/valentin/projects/zigshell/service
  EXPECT: Deployed tested worker bundle verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=2ec5977036bcd6ae3f15368b8d14c77da488175c2237fe1293ec4c3dc4c569f0; exit=0; EXPECT=matched; output-sha256=06f458774f6665f8cba36092f3b6a2fb84bbff4a125d11c1043d2d433c853d7b; output-bytes=304; shell=/bin/sh; cwd=/home/valentin/projects/zigshell/service; path=695691a31144/31 entries

- [x] G6: worker change is committed and pushed on zigshell master with requested commit trailers and only authorized service files
  CHECK: python3 /tmp/mpa-account-proof.py worker-git
  EXPECT: Worker master push and exact trailers verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=16821c3eda9d2d2f1f93a8ffc4c030524b2b7548e41024de5f280731cab46c2f; exit=0; EXPECT=matched; output-sha256=b4612ca922815dd3fbf100863db0ec458e8727aaeb8ce9aea9b67e927cb6a134; output-bytes=142; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G7: Android changes are rebased on origin/main, merged and pushed to main with requested commit trailers; GATES.md is not tracked
  CHECK: python3 /tmp/mpa-account-proof.py android-git
  EXPECT: Android rebased main push, trailers and untracked ledger verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=f2fe1514dbdada63ab4937d231a3b46d807d9f9ad3acbb140c833a020cef3f03; exit=0; EXPECT=matched; output-sha256=6a7467272f8f9fa31841bd3d6e75a4a947c0235925f3aa74e3b3ec14224dcc7e; output-bytes=107; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G8: CI android-latest release succeeds for pushed main, with measured version and a downloaded APK matching its manifest hash/size
  CHECK: gh run watch 37991845264 --exit-status && python3 /tmp/mpa-account-proof.py release
  EXPECT: Published CI release verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=ccd473ab83e95caec93ac8d9a63302300fe4f6cd621998560f04577abcec1b3d; exit=0; EXPECT=matched; output-sha256=f21bf00fff9831f448961b7f8be7841d0db5689e8c1094be3e102947536e05a9; output-bytes=221; shell=/bin/sh; cwd=/home/valentin/projects/mpa-account; path=695691a31144/31 entries

- [x] G9: final review reconciles every requested feature and constraint against fresh evidence, and accurately distinguishes verified behavior from untested physical-device behavior
  EVIDENCE: 2026-10-09 final expert review of Android 6e4b2e1 and worker 225feb6: auth endpoint/PKCE/state/expiry and outage retention in account/AccountAuth.kt:26-101 and auth/SyncAuthManager.kt:22-77; cold/warm callback and resume handling in MainActivity.kt:49-76; ordered account workspace/tab UI and All Sessions membership/add in ui/AccountWorkspacesScreen.kt:43-171; host slots and valentin outer tmux identity in account/AccountHostMap.kt:9-33; durable edits, 409 re-pull and in-flight rebasing in MainViewModel.kt:1319-1391 and account/AccountLayout.kt:90-125; foreground poll in MainViewModel.kt:197-212. Legacy WorkspaceStore and HostStore are byte-identical to 90773a4; no automatic folder import is claimed. Measured 222 passing JVM tests (22 account), SDK debug build/lint and signed CI run 37991845264 (0.1.176/100176) pass; downloaded APK SHA-256 0ca8421c8f8dc1e5060e48a672ceb502f416ecabdd2b620c0e05dab975271c62, size 18538728, release package/version/callback/signature checked. Yeo UX live D1/API has 5 workspaces and 37 tabs; bundle hash a9c08dbe2854b2ab9c42ec61301c50a7302b7a01680d3a1669510c8c71eae5e8; test devices/codes cleaned, no shared layout changes or credentials printed. All three own commits have requested trailers; main and master include them, later shared-master extension changes are untouched, GATES.md remains untracked. Runtime limit: adb has no attached device, so owner phone OTP/browser dispatch and real phone SSH attachment are not claimed as tested. Existing direct inne/agent profiles still open locally; only desktop-schema outer valentin tmux can be added as host 0. SSH keys/settings are local, custom scheme is not a verified app link, schema-1 metadata is not E2EE; these limits are documented in docs/account-workspaces.md:14-24,42-48,67-80 and will be stated without claiming physical QA.
