# Gates: HYCI-88 Multiprompt Android checks, green PR 204 with after numbers

OWNS: tools/pr-checks.test.js, .hyci88/**, GATES.md

Scope: PR 204's red `test` job is fixed without weakening any check, the PR's own green run supplies the after numbers, and HYCI-88 carries before/after numbers with links in its Done column.

- [x] G1: the check-wait test derives its expectation from the shared required list instead of a hardcoded order that rots when a job is added.
  CHECK: node .hyci88/verify.mjs testfix
  EXPECT: TEST_EXPECTATION_DERIVED
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=e4841e51e85b91a8825ea883551ca5af7a4891514fba8e6cb4c5d29fd3780356; exit=0; EXPECT=matched; output-sha256=02c6196346ed3841d2ad3e960938bca9e0485dd69e3ad305b566a2f7aa657dc1; output-bytes=25; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G2: the original secret-only commit is still rejected, and specifically for missing the long check.
  CHECK: node .hyci88/verify.mjs negative
  EXPECT: NEGATIVE_CONTROL_KEPT
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=d795fbbd7a8fc9c7f2574f24cda628f83d31df808549f350fc2d2e42aebe2690; exit=0; EXPECT=matched; output-sha256=bdb67f9eaec7f6c124f1d42df5828501e69c1ab1a7b1c55da1020ace31fc4f43; output-bytes=22; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G3: every command the old `tools/check.sh` ran still runs in some phase.
  CHECK: node .hyci88/verify.mjs checks
  EXPECT: NO_CHECK_DROPPED
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=1f623bede177c933499d2e10c805a5cc6c40dcb3c40b43444102ee9cb666a646; exit=0; EXPECT=matched; output-sha256=df76dbb946dc38501e9d167db43ffcab923dddec536fda05e26d7089b5a61d2c; output-bytes=17; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G4: `test.yml` still builds the app once and the five emulator jobs still consume that build.
  CHECK: node .hyci88/verify.mjs workflow
  EXPECT: SPLIT_OK
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=ffa41c5884883c747f8daf7ab5d42be231c6615be6eadeda7ede3536dd1baa4e; exit=0; EXPECT=matched; output-sha256=eff048e768fd38f21a8223cd59a997f34dba8341c091defbf702306d307f3c1e; output-bytes=9; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G5: the pushed head of PR 204 (open or merged) has all twelve required checks green, including the fixed `test` job.
  CHECK: node .hyci88/verify.mjs green
  EXPECT: /CHECKS_GREEN 12\/12 head=[0-9a-f]{40}/
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=deaca1c98e2c522f236cfe6d47e9e22a57cd53d672b7a1442381bd1548423431; exit=0; EXPECT=matched; output-sha256=53844ce38e7f488201e957c785519c481ef67e57b76c0690c629505b9c5a96f9; output-bytes=65; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G6: the after numbers come from that green run and the longest job is under ten minutes.
  CHECK: node .hyci88/verify.mjs after
  EXPECT: /AFTER_MEASURED run=[0-9]+ slowest=\S+ [0-9]+m[0-9]+s total=[0-9]+m[0-9]+s under_ten=yes/
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=0568733f67885b482b435dfd296bbe2b6986e389289ffca3e6101693a6caaf3c; exit=0; EXPECT=matched; output-sha256=4ab3671bd23e1b9f58d129fde3b1e28089a7f89e12598e7fa7b69f4bd6f50836; output-bytes=82; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G7: a people comment on HYCI-88 carries all four before and after numbers, both run links and the ticket link.
  CHECK: node .hyci88/verify.mjs ticketnumbers
  EXPECT: TICKET_NUMBERS
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=2ad79bfe38aab349251c0f80b984e0fe7b4ac6460af47b5c7ada07a6861305fa; exit=0; EXPECT=matched; output-sha256=f7b04892d40e660c85a27dc854d892dd3c40b3c255b367d263472f8fc218f37a; output-bytes=15; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries

- [x] G8: HYCI-88 sits in its Done column after the merge.
  CHECK: node .hyci88/verify.mjs ticket
  EXPECT: SECTION Done
  CWD: /home/valentin/projects/multiprompt-android-hyci-88
  EVIDENCE: automatic-evidence=v1; definition-sha256=0b6b9fc077bebe7a96024ca3afd0c9499c64fd45d3760109e24e41cea2b55387; exit=0; EXPECT=matched; output-sha256=1bc4b057ea4dfae4b364b2d3b4fe1ee371bb7ac19aaf46c5b24b08be813704fe; output-bytes=13; shell=/bin/sh; cwd=/home/valentin/projects/multiprompt-android-hyci-88; path=eb75ee63cfb0/37 entries
