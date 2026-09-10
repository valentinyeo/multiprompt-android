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

- Changing the account passphrase re-wraps the vault key only: records do not need to be
  re-encrypted.
- Argon2id parameters are stored inside the envelope and are bound by AAD, so they cannot
  be swapped without failing the wrap tag.

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

## Kotlin reference implementation

`app/src/main/java/dev/multiprompt/companion/sync/SyncProtocol.kt` — `seal()`/`open()` for
the vault/bootstrap envelope and `sealEntity()`/`openEntity()` for per-entity transport
records, with an injectable-free deterministic core so tests can reproduce every byte.
Verified against every vector in

```text
app/src/test/resources/sync/sync-protocol-v1-fixtures.json
```

Each vector fixes the passphrase, salt, vault key, and all IVs, and states the expected
KEK, wrap ciphertext, per-record ciphertexts, the exact canonical envelope JSON, and its
SHA-256. A language implements protocol v1 correctly if and only if it reproduces the
envelope bytes and SHA-256 for every vector, and opens them back to the plaintexts.

## Milestone M0 — Cloudflare Access on native Android (separate from this protocol)

M0 proves the auth path before any storage ships: a custom-tab/browser Access login on the
device yields a short-lived Access token the Worker accepts. **No long-lived or service
token may ever be embedded in the APK**; the APK carries no Cloudflare secret at all.

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
  + "/" + entityId)`), tag included. The entity id is inside the AAD, so a row must never
  be re-keyed under another entity id.
- Field order, compact separators, and base64 rules are the same as for the envelope.
- `entityId` must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no `/`, no `:`). Mappers that have
  composite keys (e.g. host + tmux session) must encode them into this alphabet; the
  protocol does not prescribe the encoding.
- Canonical field order: `v`, `recordId`, `entityId`, `iv`, `ct`.

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

`v` is 1. Any breaking change (KDF defaults, canonical form, record layout) must bump `v`
and ship new fixtures; parsers must reject unknown versions with `UNSUPPORTED_VERSION`
rather than guessing. Adding a record id is not a breaking change.
