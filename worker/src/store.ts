/**
 * D1-backed sync_entity store — the server side of SyncTransport.kt
 * (app/src/main/java/dev/multiprompt/companion/sync/SyncTransport.kt).
 * Payloads are opaque sealed bytes; this module never inspects or decrypts
 * them (docs/sync-protocol-v1.md, "Threat model and invariants").
 */

export interface RemoteEntity {
  entityId: string;
  revision: number;
  tombstone: boolean;
  /** Sealed entity-record JSON (docs/sync-protocol-v1.md, "Entity records"), or null. */
  payload: string | null;
}

export interface PushOutcome {
  revision: number;
  conflict: RemoteEntity | null;
}

export interface Change extends RemoteEntity {
  seq: number;
  recordId: string;
}

export interface WriteOp {
  recordId: string;
  entityId: string;
  expectedRevision: number;
  tombstone: boolean;
  payload: string | null;
}

export interface VaultRow {
  revision: number;
  /** Sealed envelope text, or null when no vault row exists yet (revision 0). */
  envelope: string | null;
}

export interface VaultPushOutcome {
  revision: number;
  conflict: VaultRow | null;
}

interface Row {
  entity_id: string;
  revision: number;
  tombstone: number;
  payload: string;
}

export class SyncStore {
  constructor(private readonly db: D1Database) {}

  /** Lists every entity for `recordId` at or above `sinceRevision` (0 = all), for one account. */
  async list(accountId: string, recordId: string, sinceRevision: number): Promise<RemoteEntity[]> {
    const result = await this.db
      .prepare(
        "SELECT entity_id, revision, tombstone, payload FROM sync_entity " +
          "WHERE account_id = ?1 AND record_id = ?2 AND revision >= ?3 ORDER BY revision ASC",
      )
      .bind(accountId, recordId, sinceRevision)
      .all<Row>();
    return (result.results ?? []).map(rowToEntity);
  }

  /**
   * Pushes one entity under optimistic concurrency: the write is accepted
   * only when the stored revision equals expectedRevision (0 = must not
   * exist yet), bumping to a fresh per-key revision on success. On mismatch
   * returns the current row as a conflict for the client to rebase onto
   * (docs/sync-protocol-v1.md, "Optimistic concurrency") — never a
   * timestamp-based overwrite.
   */
  async push(
    accountId: string,
    recordId: string,
    entityId: string,
    expectedRevision: number,
    tombstone: boolean,
    payload: string | null,
    deviceId: string | null = null,
  ): Promise<PushOutcome> {
    return (await this.writeMany(accountId, [
      { recordId, entityId, expectedRevision, tombstone, payload },
    ], deviceId)).results[0];
  }

  async accountState(accountId: string): Promise<{ head: number; paused: number }> {
    return (await this.db.prepare(
      "SELECT head, paused FROM sync_account WHERE account_id = ?1",
    ).bind(accountId).first<{ head: number; paused: number }>()) ?? { head: 0, paused: 0 };
  }

  async changes(accountId: string, after: number, limit: number): Promise<{ changes: Change[]; more: boolean }> {
    const rows = await this.db.prepare(
      "SELECT seq, record_id, entity_id, revision, tombstone, payload FROM sync_entity " +
      "WHERE account_id = ?1 AND seq > ?2 ORDER BY seq ASC LIMIT ?3",
    ).bind(accountId, after, limit + 1).all<Row & { seq: number; record_id: string }>();
    const results = rows.results ?? [];
    return {
      changes: results.slice(0, limit).map(row => ({ seq: row.seq, recordId: row.record_id, ...rowToEntity(row) })),
      more: results.length > limit,
    };
  }

  /** One D1 batch is one transaction. Failed revisions do not consume sequence numbers. */
  async writeMany(accountId: string, ops: WriteOp[], deviceId: string | null = null, respectPause = false): Promise<{
    head: number; results: PushOutcome[];
  }> {
    if (ops.length === 0) return { head: (await this.accountState(accountId)).head, results: [] };
    const statements: D1PreparedStatement[] = [
      this.db.prepare("INSERT OR IGNORE INTO sync_account (account_id, head) VALUES (?1, 0)").bind(accountId),
    ];
    const pausedClause = respectPause ? " AND paused = 0" : "";
    for (const op of ops) {
      const args = [accountId, op.recordId, op.entityId, op.expectedRevision];
      const matches = "((?4 = 0 AND NOT EXISTS (SELECT 1 FROM sync_entity WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3)) " +
        "OR EXISTS (SELECT 1 FROM sync_entity WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3 AND revision = ?4))";
      statements.push(this.db.prepare(
        "UPDATE sync_account SET head = head + 1 WHERE account_id = ?1" + pausedClause + " AND " + matches +
        " AND (?5 IS NULL OR EXISTS (SELECT 1 FROM sync_device WHERE device_id = ?5 AND account_id = ?1 AND revoked_at IS NULL))",
      ).bind(...args, deviceId));
      statements.push(this.db.prepare(
        "INSERT INTO sync_entity (account_id, record_id, entity_id, revision, tombstone, payload, updated_at, seq, device_id) " +
        "SELECT ?1, ?2, ?3, ?4 + 1, ?5, COALESCE(?6, (SELECT payload FROM sync_entity " +
        "WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3), ''), ?7, head, ?8 " +
        "FROM sync_account WHERE account_id = ?1" + pausedClause + " AND " + matches +
        " AND (?8 IS NULL OR EXISTS (SELECT 1 FROM sync_device WHERE device_id = ?8 AND account_id = ?1 AND revoked_at IS NULL)) " +
        "ON CONFLICT (account_id, record_id, entity_id) DO UPDATE SET " +
        "revision = excluded.revision, tombstone = excluded.tombstone, payload = excluded.payload, " +
        "updated_at = excluded.updated_at, seq = excluded.seq, device_id = excluded.device_id " +
        "WHERE sync_entity.revision = ?4 RETURNING entity_id, revision, tombstone, payload",
      ).bind(...args, op.tombstone ? 1 : 0, op.payload, new Date().toISOString(), deviceId));
    }
    const written = await this.db.batch<Row>(statements);
    const results: PushOutcome[] = [];
    for (let i = 0; i < ops.length; i++) {
      const row = written[2 + 2 * i].results?.[0];
      if (row) {
        results.push({ revision: row.revision, conflict: null });
      } else {
        const op = ops[i];
        const current = await this.db.prepare(
          "SELECT entity_id, revision, tombstone, payload FROM sync_entity " +
          "WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3",
        ).bind(accountId, op.recordId, op.entityId).first<Row>();
        results.push({ revision: current?.revision ?? 0, conflict: current ? rowToEntity(current) : {
          entityId: op.entityId, revision: 0, tombstone: false, payload: null,
        } });
      }
    }
    return { head: (await this.accountState(accountId)).head, results };
  }

  /**
   * Fetches the one small "vault key envelope" row for an account (IGSH-251,
   * Sync 4): the wrapped-vault-key bootstrap object a second device needs
   * before it can unwrap and sync the per-entity records above. Unlike
   * entity records this is a single opaque row per account, not a
   * collection — there is only ever one current envelope.
   */
  async getVault(accountId: string): Promise<VaultRow | null> {
    const row = await this.db
      .prepare("SELECT revision, envelope FROM sync_vault WHERE account_id = ?1")
      .bind(accountId)
      .first<{ revision: number; envelope: string }>();
    return row ? { revision: row.revision, envelope: row.envelope } : null;
  }

  /**
   * Writes the vault envelope under the same optimistic-concurrency rule as
   * push(): the write is accepted only when the stored revision equals
   * expectedRevision (0 = must not exist yet). Two devices racing to create
   * or rewrap the vault get a conflict to resolve, never a silent overwrite.
   */
  async putVault(accountId: string, expectedRevision: number, envelope: string): Promise<VaultPushOutcome> {
    const current = await this.db
      .prepare("SELECT revision, envelope FROM sync_vault WHERE account_id = ?1")
      .bind(accountId)
      .first<{ revision: number; envelope: string }>();

    if (current !== null && current.revision !== expectedRevision) {
      return { revision: current.revision, conflict: { revision: current.revision, envelope: current.envelope } };
    }
    if (current === null && expectedRevision !== 0) {
      // No row exists, but the client expected a nonzero revision: tell it
      // the true current state (revision 0 = does not exist) so it rebases
      // onto a create (expectedRevision 0) instead — same rule as push().
      return { revision: 0, conflict: { revision: 0, envelope: null } };
    }

    const nextRevision = (current?.revision ?? 0) + 1;
    const now = new Date().toISOString();
    const result = await this.db
      .prepare(
        "INSERT INTO sync_vault (account_id, revision, envelope, updated_at) VALUES (?1, ?2, ?3, ?4) " +
          "ON CONFLICT (account_id) DO UPDATE SET " +
          "revision = excluded.revision, envelope = excluded.envelope, updated_at = excluded.updated_at " +
          "WHERE sync_vault.revision = ?5 " +
          "RETURNING revision, envelope",
      )
      .bind(accountId, nextRevision, envelope, now, expectedRevision)
      .all<{ revision: number; envelope: string }>();

    const written = result.results?.[0];
    if (!written) {
      const after = await this.db
        .prepare("SELECT revision, envelope FROM sync_vault WHERE account_id = ?1")
        .bind(accountId)
        .first<{ revision: number; envelope: string }>();
      return { revision: after?.revision ?? 0, conflict: after ? { revision: after.revision, envelope: after.envelope } : null };
    }
    return { revision: written.revision, conflict: null };
  }
}

function rowToEntity(row: Row): RemoteEntity {
  return {
    entityId: row.entity_id,
    revision: row.revision,
    tombstone: row.tombstone !== 0,
    payload: row.payload,
  };
}
