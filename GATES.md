# Gates: IGSH-1223

Deeper: field: cross-device delivery | raw source: desktop reader and Android bundled reader | method: preserve receipt identity and replay prior pending records | outside proof: GitHub regression failures before fix and signed release | outside services: none | proof gate: IGSH-1223.deeper-queue

OWNS: app/src/main/assets/chat_history_reader.*, app/src/test/**, tools/**, .github/workflows/test.yml, GATES.md

Scope: Android uses desktop queue receipt fixes, without resending accepted messages or breaking old protocol requests.

- [ ] IGSH-1223.deeper-queue: GitHub regression proves large receipts and existing requests complete exactly once.
  CHECK: gh run list -R valentinyeo/multiprompt-android --branch main --workflow test.yml --limit 1 --json conclusion --jq '.[0].conclusion'
  EXPECT: success
  EVIDENCE: pending

- [ ] G2: PR merged after all required GitHub checks pass.
  EVIDENCE: pending

- [ ] G3: Signed Android update serves merged commit and release proof.
  EVIDENCE: pending
