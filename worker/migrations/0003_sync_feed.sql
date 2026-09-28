-- Sync 5: account-wide change feed and independently revocable devices.
ALTER TABLE sync_entity ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_entity ADD COLUMN device_id TEXT;
CREATE INDEX sync_entity_seq ON sync_entity (account_id, seq);
CREATE TABLE sync_account (
  account_id TEXT PRIMARY KEY,
  head INTEGER NOT NULL,
  paused INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE sync_device (
  device_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  secret_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT,
  acked_seq INTEGER NOT NULL DEFAULT 0,
  revoked_at TEXT
);
CREATE INDEX sync_device_account ON sync_device (account_id);
