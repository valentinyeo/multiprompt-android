-- multiprompt sync — vault key envelope bootstrap row (IGSH-251, Sync 4).
-- One row per account: the small wrapped-vault-key envelope a second device
-- fetches to unwrap the same vault key. Never entity records (see
-- docs/sync-protocol-v1.md, "Storage and sync semantics").

CREATE TABLE sync_vault (
  account_id TEXT PRIMARY KEY,
  revision   INTEGER NOT NULL,
  envelope   TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
