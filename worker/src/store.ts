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
      // No row exists, but the client expected a nonzero revision: tell it
      // the true current state (revision 0 = does not exist) so it rebases
      // onto a create (expectedRevision 0) instead.
      return { revision: 0, conflict: { entityId, revision: 0, tombstone: false, payload: null } };
    }

    const nextRevision = (current?.revision ?? 0) + 1;
    // Tombstones keep the last known sealed payload when none is supplied
    // (docs/sync-protocol-v1.md: "for tombstones: a sealed empty body or the
    // last sealed body").
    const storedPayload = payload ?? (tombstone ? (current?.payload ?? "") : "");
    const now = new Date().toISOString();

    // The WHERE on DO UPDATE re-checks expectedRevision at write time (not
    // just at our earlier SELECT): if another writer changed the row in
    // between, this condition is false, the UPDATE is skipped, and RETURNING
    // yields no row — the atomic signal that we lost the race, without a
    // second round trip guessing at "did it apply".
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
      // Lost the race: someone else's write landed between our SELECT and
      // this INSERT/UPDATE. Re-read and report their row as the conflict so
      // the caller rebases onto reality.
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
