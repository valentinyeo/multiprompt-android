-- multiprompt sync v1 — reference schema from docs/sync-protocol-v1.md
-- ("Storage and sync semantics (Cloudflare D1)" > "D1 row shape").

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

-- Supports `list(recordId, sinceRevision)` scoped to one account.
CREATE INDEX sync_entity_list ON sync_entity (account_id, record_id, revision);
