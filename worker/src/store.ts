
export interface RemoteEntity {
  entityId: string;
  revision: number;
  tombstone: boolean;
  payload: string | null;
}

export interface PushOutcome {
  revision: number;
  conflict: RemoteEntity | null;
}

export interface VaultRow {
  revision: number;
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

  async push(
    accountId: string,
    recordId: string,
    entityId: string,
    expectedRevision: number,
    tombstone: boolean,
    payload: string | null,
  ): Promise<PushOutcome> {
    const current = await this.db
      .prepare(
        "SELECT entity_id, revision, tombstone, payload FROM sync_entity " +
          "WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3",
      )
      .bind(accountId, recordId, entityId)
      .first<Row>();

    if (current !== null && current.revision !== expectedRevision) {
      return { revision: current.revision, conflict: rowToEntity(current) };
    }
    if (current === null && expectedRevision !== 0) {
      return { revision: 0, conflict: { entityId, revision: 0, tombstone: false, payload: null } };
    }

    const nextRevision = (current?.revision ?? 0) + 1;
    const storedPayload = payload ?? (tombstone ? (current?.payload ?? "") : "");
    const now = new Date().toISOString();

    const result = await this.db
      .prepare(
        "INSERT INTO sync_entity (account_id, record_id, entity_id, revision, tombstone, payload, updated_at) " +
          "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
          "ON CONFLICT (account_id, record_id, entity_id) DO UPDATE SET " +
          "revision = excluded.revision, tombstone = excluded.tombstone, " +
          "payload = excluded.payload, updated_at = excluded.updated_at " +
          "WHERE sync_entity.revision = ?8 " +
          "RETURNING entity_id, revision, tombstone, payload",
      )
      .bind(accountId, recordId, entityId, nextRevision, tombstone ? 1 : 0, storedPayload, now, expectedRevision)
      .all<Row>();

    const written = result.results?.[0];
    if (!written) {
      const after = await this.db
        .prepare(
          "SELECT entity_id, revision, tombstone, payload FROM sync_entity " +
            "WHERE account_id = ?1 AND record_id = ?2 AND entity_id = ?3",
        )
        .bind(accountId, recordId, entityId)
        .first<Row>();
      return { revision: after?.revision ?? 0, conflict: after ? rowToEntity(after) : null };
    }
    return { revision: written.revision, conflict: null };
  }

  async getVault(accountId: string): Promise<VaultRow | null> {
    const row = await this.db
      .prepare("SELECT revision, envelope FROM sync_vault WHERE account_id = ?1")
      .bind(accountId)
      .first<{ revision: number; envelope: string }>();
    return row ? { revision: row.revision, envelope: row.envelope } : null;
  }

  async putVault(accountId: string, expectedRevision: number, envelope: string): Promise<VaultPushOutcome> {
    const current = await this.db
      .prepare("SELECT revision, envelope FROM sync_vault WHERE account_id = ?1")
      .bind(accountId)
      .first<{ revision: number; envelope: string }>();

    if (current !== null && current.revision !== expectedRevision) {
      return { revision: current.revision, conflict: { revision: current.revision, envelope: current.envelope } };
    }
    if (current === null && expectedRevision !== 0) {
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
