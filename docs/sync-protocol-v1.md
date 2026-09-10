# multiprompt sync protocol v1

Status: **stable** — fixture vectors are the cross-language contract. The desktop side
(zigshell, Zig/Win32) implements this same envelope; its verifier lands in a later
zigshell change and must reproduce every fixture byte-for-byte.

Account: IGSH-223. Auth milestone M0 (Cloudflare Access from native Android) is tracked
there and is a prerequisite for shipping any transport; this document covers only the
envelope/record crypto, which is independent of transport.

## Threat model and invariants

- The server (Cloudflare Worker + D1) is **zero-knowledge**: it stores opaque ciphertext
  only. A full database compromise must not reveal host profiles, SSH key material, or
  session state.
- The account passphrase never leaves the device. It derives a KEK only.
- The **vault key** (random 256 bits) is the content key. It is never derived from the
  passphrase and never travels unsealed.
- Local protection of the unwrapped vault key while the app runs: Android Keystore on
  Android, **DPAPI** on Windows/zigshell. No platform ever persists the vault key in
  plaintext.
- **Never ship a service token in the APK.** The app authenticates to Cloudflare through
  the Access login flow (milestone M0) and holds only short-lived user tokens.
- No PBKDF2-with-passphrase-as-content-key variant is acceptable: the passphrase must only
  ever produce the KEK.

## Key hierarchy

```text
account passphrase --Argon2id(salt, m, t, p)--> KEK (32 bytes)
vault key: 32 random bytes, generated once per vault
KEK + AES-256-GCM --wrap--> sealed vault key (48 bytes = 32 key + 16 tag)
vault key + AES-256-GCM --per record--> record ciphertext (plaintext + 16-byte tag)
```

vault key: 32 random bytes, generated once per vault account
KEK + AES-256-GCM --wrap--> sealed vault key (48 bytes = 32 key + 16 tag)
vault key + AES-256-GCM --per record--> record ciphertext (plaintext + 16-byte tag)
```

- Changing the account passphrase re-wraps the vault key only: **records never need
  re-encryption on a passphrase change.**
- Argon2id parameters are stored inside the envelope and are bound by AAD, so they cannot
  be swapped without failing the wrap tag. KDF parameters are **versioned**: the envelope
  pins `argon2id v19, m=64 MiB, t=3, p=1` today; any future change ships as a new version
  in the `kdf` block, never a silent edit of the defaults.
- Random 12-byte nonces per encryption. GCM AAD always binds the authenticated metadata
  (schema version, account, record id, entity id, revision — see the entity sections).
- **Local vault-key storage:** after a successful unwrap the vault key lives in
  **Android Keystore** on Android (a Keystore-wrapped AES key protects the stored vault
  key, mirroring `SecretStore`; strongbox-backed when available). The later Windows
  adapter (zigshell) stores it under **DPAPI**. The unwrapped vault key never persists in
  plaintext and never leaves the device unsealed.

## Canonical envelope

The envelope is a single UTF-8 JSON object, **canonical form**: no whitespace, compact
separators (`,` and `:`), object keys in exactly the orders below, standard RFC 4648
base64 **with padding**, integers without decimal point or exponent.

```json
{
  "v": 1,
  "kdf": {
    "alg": "argon2id",
    "version": 19,
    "memoryKiB": 65536,
    "iterations": 3,
    "parallelism": 1,
    "salt": "<b64, 16 bytes>"
  },
  "wrap": {
    "alg": "AES-256-GCM",
    "iv": "<b64, 12 bytes>",
    "ct": "<b64, 48 bytes>"
  },
  "records": {
    "<recordId>": { "iv": "<b64, 12 bytes>", "ct": "<b64>" }
  }
}
```

`records` keys are sorted by Unicode code point (`hosts` < `sessionState` < `workspaces`).
Record ids match `[a-z][a-zA-Z0-9]*`. Parsers must accept any key order on input (compare
semantically), but writers must emit exactly this order.

## Algorithms and encodings

| Step | Definition |
|---|---|
| KEK | Argon2id v19, `memoryKiB`=65536, `iterations`(t)=3, `parallelism`(p)=1, salt 16 random bytes, output 32 bytes. The password bytes are the UTF-8 encoding of the passphrase (Argon2 is applied to raw bytes, matching `hash_secret_raw`). |
| Vault key | 32 bytes from a cryptographically secure RNG. |
| Wrap | AES-256-GCM, key = KEK, 12-byte random IV, AAD = `UTF8("mp-sync-v1/wrap/" + canonicalKdfJson)`, plaintext = vault key. Ciphertext = 48 bytes (key + 16-byte GCM tag). |
| Record | AES-256-GCM, key = vault key, 12-byte random IV per record, AAD = `UTF8("mp-sync-v1/record/" + recordId)`, plaintext = record body bytes (UTF-8 JSON for state records). |
| `canonicalKdfJson` | The `kdf` object rendered with the canonical field order above — `{"alg":"argon2id","version":19,"memoryKiB":65536,"iterations":3,"parallelism":1,"salt":"..."}`. AAD binds the parsed-then-canonicalised parameters, so a re-serialised but semantically identical envelope still opens. |

Vault-key layout: `ct_wrap = AES256GCM(KEK, wrapIv, vaultKey, aadWrap)`.

A wrong passphrase and a tampered wrap block are indistinguishable by design; both fail
the wrap tag as `WRONG_PASSPHRASE`. A record whose wrap opened but whose tag fails is
`TAMPERED` — the vault key was correct, so the record body was modified or its id/IV
substituted (the record id is inside the AAD, so renaming a record also fails).

## Golden fixtures — the cross-language contract

```text
app/src/test/resources/sync/sync-protocol-v1-fixtures.json
```

One committed file is the single source of truth for both implementations. It contains

- **crypto golden vectors** (`vectors`): full vault envelopes — passphrase, salt, vault
  key and every IV pinned; expected KEK, wrap ciphertext, per-record ciphertexts, the
  exact canonical envelope JSON, and its SHA-256;
- **entity golden vectors** (`entityVectors`): per-entity D1 payloads with account and
  revision pinned in the AAD;
- **schema golden vectors** (`schemaVectors`): plaintext record bodies for `hosts`,
  `workspaces`, and `sessionState` — the exact canonical JSON a mapper must emit for a
  fixed logical input, so the two languages agree on field names, order, types, and
  omitted-field handling *before* encryption. A schema mismatch is as fatal as a crypto
  mismatch.

Determinism rules: the generator (`scripts/gen-sync-fixtures.py`) is committed; every
random input is pinned; re-running it must reproduce the committed file bit-for-bit — if
it does not, the generator drifted and the diff is a bug, not new data.

### The gate before production

**Neither the Android app nor zigshell may talk to the production Worker until its own
suite passes every vector in the committed fixture file byte-for-byte.** For Kotlin this
is `SyncProtocolFixtureTest` (envelope + entity + schema vectors) in CI. For Zig it is the
zigshell verifier against the same committed file — a later zigshell change; until that
suite exists and passes, zigshell has no protocol implementation. Fixture updates are a
reviewed, deliberate act: regenerate, inspect the diff, land both sides' verification in
the same change.

### Schema vectors

Plaintext record bodies are canonical JSON with fixed field order (below). Types: host
UUIDs and workspace ids as strings, ports as integers, timestamps as epoch-seconds
integers, booleans as JSON booleans; optional fields are **omitted**, never null or
empty-string placeholders (except `passphraseSecretId`, which serializes as `null`).

- `hosts` record body: `{"hosts":[{"id","label","hostname","port","username",
  "keySecretId","passphraseSecretId","hostKeyType","hostKeyFingerprint"}]}` sorted by
  `label` (case-insensitive), then `id` — the same order `HostStore` persists.
- `workspaces` record body: `{"workspaces":[{"id","name","hostId","remotePath"}]}`
  sorted by `name` (case-insensitive).
- `sessionState` record body: one entity per session, entity id `<hostUuid>--<tmuxSessionName>`;
  body `{"lastReadAt":<int>,"archivedAt":<int|null>,"resumeAt":<int|null>,
  "fontScaleScope":"universal"?}` — per the sessionState semantics section; keys present
  only when meaningful, `archivedAt` present iff archived, `resumeAt` only with
  `archivedAt`.

## Milestone M0 — auth proof (gated, not an assumption)

Auth is milestone 0 and must be **proven on a device** before any storage ships. M0's
question: can a native Android app get **reusable, revocable app API authorization**
through Cloudflare Access, cleanly?

### The problem with the cookie

Access normally authenticates **HTTP requests** with the `CF_Authorization` cookie (a
JWT). On the web this is invisible; for a native app it is awkward: cookies live in the
browser, are refreshed by Cloudflare's JS on page loads, and have no official Android
SDK or cookie-handoff bridge. A custom tab could technically complete the login and the
cookie would sit in the browser, not the app — the app would then have no token for its
own OkHttp calls.

### Candidate A (prove-or-drop): Access for SaaS (OIDC) via Custom Tabs

Access for SaaS exposes standard OIDC endpoints (authorization, token, JWKS) and allows
OIDC client applications. The native flow:

1. Custom Tabs opens the Access authorization endpoint; the user completes Access login
   (email OTP / IdP) in the browser.
2. Redirect to the app via AppLinks/deep link (`dev.multiprompt.companion://auth/callback`),
   with authorization code.

A **supported OIDC library** (AppAuth-Android) exchanges the code at the token endpoint
for ID/access tokens. Proof requirements for M0:

- Short-lived **access token** the Worker can verify (Access for SaaS certs or the IdP's
  JWKS); refresh handled by the library (refresh token rotation) → revocable: revoking a
  user's session in Access/IdP stops refresh, and access tokens die within minutes.
- The app requests an **offline-access refresh token**, stored in Android Keystore, never
  exported. Service tokens: still never in the APK — M0 stands with the invariant.
- Revocation check: can the account owner kill a device's refresh token from the Access
  dashboard/IdP? If not cleanly, Access for SaaS fails the "revocable" bar → Candidate B.

### Candidate B (fallback, recommended if A is not clean): direct OIDC / Better Auth for users, Access stays in front of admin/ops

If the Access-for-SaaS token handoff is not clean (cookie-only, no token endpoint,
non-revocable), the recommendation flips: user login goes through a first-class OIDC
provider (or Better Auth) that natively supports native apps (PKCE, system browser, refresh
rotation, revocation endpoint); Cloudflare Access stays in front of admin/ops surfaces
(dashboard, D1 console, deploy pipeline) and is **removed from the app's API path**. The
Worker validates the OIDC access/ID token (JWKS) instead of Access JWTs. This is the
**default recommendation** unless Candidate A proves clean and revocable in M0.\n
### M0 acceptance (either candidate)

- On-device demo: login → token → Worker `/sync/whoami` echoes the identity and expiry.
- Revocation: killing the session/refresh token makes the next API call fail within the
  token TTL.
- No Cloudflare secret in the APK (code review + string scan for `v1.1-` style service
  token prefixes, `CF_Authorization` cookie handling, or any embedded credential).
- Flow is a supported pattern: system browser / Custom Tabs + PKCE, no webview-in-app
  auth, no cookie scraping.

## Desktop mapping (zigshell) — no wholesale file sync

The desktop client is zigshell (Zig/Win32). Its durable state lives under
`%APPDATA%\ZigShell` — chiefly `session.json` and `windows.json`, plus `commands.json`
and the OS SSH configuration. **None of these files are synced wholesale.** Sync always
happens at the level of this protocol's records: the desktop maps its own state into the
record ids below and applies incoming records back explicitly. Raw config files, OS SSH
config (`~/.ssh`, registry-stored agent config), and anything not modelled as a record
never leave the device.

## Record ids (v1)

- `hosts` — host profiles. **Never contains private key or passphrase material** — only the profile fields (label, hostname, port, username, host key type/fingerprint) and a local-only `keySecretId` reference that stays meaningless outside the originating device.
- `workspaces` — workspace definitions.
- `sessionState` — per-session read/unread, archive, and display state.

**v1 ships without SSH private-key/passphrase sync.** Phase 1 syncs non-secret logical state only. SSH key material sync is a separate **Phase 2** feature that requires its own threat model before implementation; when it arrives it gets a dedicated record id (e.g. `keys`) sealed with the same vault key and the envelope-wrapping scheme from the key hierarchy. Until then the server never sees, in any form, the contents of `SecretStore`.

Adding a record id is not a breaking change; renaming or removing one is.

## sessionState semantics (normative for both apps)

Sync semantics must hold identically on Android and zigshell. The session-state record for
`<hostUuid>--<tmuxSessionName>` carries only the logical, device-independent state:

### lastRead — a monotonic remote activity watermark

`lastReadAt` is the highest remote activity timestamp the account has consumed, and it may
only move forward: writers store `max(existing, lastActivityEpochSeconds)` and sync the
watermark, never an absolute "read" moment. A read on an old snapshot can never mark newer
output as read, and two devices cannot regress each other's watermark. `isUnread` is
always **derived**: `lastActivity > lastReadAt` — it is never synced as a boolean, and
never stored per device.

### archive — tombstoned intent with archivedAt + optional resumeAt

An archive is `{ archivedAt, resumeAt? }`: `archivedAt` = the activity watermark at
archive time; `resumeAt`, when present, is an epoch-seconds instant after which the
session returns ("resume at"). Restore clears the record. Archives sync as their own
sessionState fields; absence = not archived.

### needsAttention — device-derived, never a synced bool

`needsAttention` is **derived on each device** from its own view of the session (prompt
state, activity watermark, focused-ness). It is not a synced boolean. What syncs, if
anything, is the *underlying remote signal* (e.g. the tmux prompt/waiting state) as
remote-sourced data; each device keeps its own attention UI. Two devices may legitimately
disagree at an instant; that is correct behaviour, not a conflict.

### fontScale — device/form-factor scoped unless explicitly universal

Font scale is a device preference, not account state. It syncs **only** under an explicit
user request for a universal value; otherwise the `sessionState` record omits it and every
device keeps its own. When the user does opt in, the record gains an explicit
`fontScaleScope: "universal"` marker plus the value — never an implicit copy.

### Rebase rules per state key

`sessionState` merges per state key (`union`/`max` per the optimistic-concurrency rules):
`lastReadAt` = max; `archivedAt`/`resumeAt` = latest archive intent wins by revision (and
restore is a tombstone of the archive state); font scale follows the scoping rule above.

## Storage and sync semantics (Cloudflare D1)

Storage is **D1, not KV**: the sync model needs per-row revisions, uniqueness, and
conditional writes. The unit of sync is the **per-entity encrypted record** — never one
giant account blob, and never the multi-record envelope above. The envelope is the local
vault/bootstrap format (passphrase → vault key); what travels to and from the server is
one sealed row per entity.

### Entity records

An entity record is a canonical JSON object sealing one entity with the vault key:

```json
{
  "v": 1,
  "recordId": "hosts",
  "entityId": "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31",
  "iv": "<b64, 12 bytes>",
  "ct": "<b64>"
}
```

- `ct` = AES-256-GCM(vaultKey, iv, plaintext, AAD = `UTF8("mp-sync-v1/entity/" + recordId
  + "/" + entityId + "/" + accountId + "/" + revision + "/" + schemaVersion)`), tag
  included. The AAD is the **authenticated metadata**: schema version, record id, entity
  id, account, and the row revision this payload seals. A payload therefore cannot be
  replayed onto a different account, a different record/entity, or an older revision —
  each attempt fails the GCM tag. The AAD components mirror the D1 row's indexed columns,
  so the database cannot shuffle rows without detection.
- Field order, compact separators, and base64 rules are the same as for the envelope.
- `entityId` must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no `/`, no `:`). Mappers that have
  composite keys (e.g. host + tmux session) must encode them into this alphabet; the
  protocol does not prescribe the encoding.
- Canonical field order: `v`, `recordId`, `entityId`, `iv`, `ct`. The AAD (not the JSON
  body) carries `accountId` and `revision` — the server indexes those columns and clients
  must pass the row's revision when sealing so replay across revisions is detected.
- `accountId` is the Access identity's stable identifier (e.g. the Access JWT `sub`),
  bound as-is into the AAD.

## Stable logical IDs and the exclusion list

Entity ids are **stable logical identities**, not machine state:

- **Host**: the account-scoped host UUID (`HostProfile.id`, a UUIDv4). The same physical
  VPS must keep one host UUID across every device; host profiles are created once and
  synced, not re-invented per device.
- **Workspace**: the workspace UUID (`Workspace.id`).
- **Session**: the session identity is the pair **host UUID + tmux session name**, encoded
  for the entity id as `<hostUuid>--<tmuxSessionName>` (`::` is reserved by legacy local
  stores and `/` and `:` are outside the entity-id alphabet; `--` is safe because tmux
  session names cannot contain `--`... when a name could, mappers must percent-encode the
  tmux name portion). Session-derived fields like `windowName`, `title`, `paneCommand`,
  `preview`, `columns`, `rows`, `attachedClients`, and `windows` are **live telemetry**:
  they may appear in session records only as display hints, never as part of the identity,
  and every consumer must tolerate them being absent or stale.
- **Excluded from sync entirely** — machine-only, per-process, or transient fields:
  local PIDs and window handles (PID/HWND), process executable paths, transient browser
  URLs, tunnel/keep-alive state (e.g. zigshell's `127.0.0.1:19547` notification tunnel),
  SSH agent socket paths, and any other value that exists only inside one machine or one
  app run. Two devices with different PIDs/HWNDs/tunnels for the same logical session
  produce identical sync records.

### D1 row shape (reference schema)

```sql
CREATE TABLE sync_entity (
  account_id TEXT NOT NULL,
  record_id  TEXT NOT NULL,
  entity_id  TEXT NOT NULL,
  revision   INTEGER NOT NULL,
  tombstone  INTEGER NOT NULL DEFAULT 0,
  payload    TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (account_id, record_id, entity_id)
);
```

- `payload` is the sealed entity-record JSON above (for tombstones: a sealed empty body or
  the last sealed body; clients must treat a tombstone as authoritative deletion).
- `revision` is a **server-assigned, per-key monotonic counter**, starting at 1. It is the
  only ordering authority. `updated_at` is metadata (debugging, retention) and is **never**
  consulted for conflict resolution.
- Deletions write a tombstone row at a new revision rather than deleting the row, so a
  delayed write from another device cannot resurrect a deleted entity. Tombstones may be
  compacted only after every device has acknowledged a revision past them.

### Optimistic concurrency

Every write names the revision it believes current (`expectedRevision`; `0` = create).
The server accepts the write only when the stored revision equals it, bumping to a fresh
revision on success; otherwise it responds `409 CONFLICT` carrying the current revision
and payload. On conflict the client **re-reads the winning entity, re-applies its logical
change on top, and retries** — rejection-and-rebase, not timestamped overwrite. Mappers
own the merge semantics per record id: `hosts` and `workspaces` merge at the field level,
`sessionState` resolves as union/max per state key. Wall-clock-only last-write-wins is
explicitly forbidden: timestamps never decide a conflict.

## Versioning

The protocol document is versioned in its filename: `sync-protocol-v1.md` is the normative
spec for envelope `v: 1`. Any breaking change (KDF defaults, canonical form, record
layout, AAD composition) must bump the version — a new doc file, a new fixture file, a new
envelope version — never a silent edit of v1 material. Adding a record id or an optional
field is not a breaking change. Parsers must reject unknown versions with
`UNSUPPORTED_VERSION` rather than guessing.
