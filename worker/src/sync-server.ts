/**
 * multiprompt sync — production sync server (IGSH-249, milestone M3).
 * Endpoints per docs/sync-protocol-v1.md "Storage and sync semantics", shaped
 * to match SyncTransport.kt's list/push contract 1:1. The server never
 * decrypts: `payload` is always the sealed entity-record JSON text, opaque
 * bytes as far as this worker is concerned.
 *
 * Routes:
 *   GET /records/:recordId?since=<revision>   list entities at/above a revision
 *   PUT /records/:recordId/:entityId          push one entity (optimistic concurrency)
 *
 * Auth: `Authorization: Bearer <token>` verified against the Access-for-SaaS
 * app's JWKS (see auth.ts). Everything else is rejected.
 */

import { verifyRequest, AuthError, type AuthEnv } from "./auth";
import { SyncStore, type RemoteEntity, type VaultRow } from "./store";

export interface Env extends AuthEnv {
  DB: D1Database;
}

// Same shape as the local envelope's record ids (docs/sync-protocol-v1.md,
// "Canonical envelope"): lowercase-led camelCase identifiers. Deliberately
// not an allowlist of the three known record ids — "adding a record id is
// not a breaking change" (docs/sync-protocol-v1.md, "Record ids (v1)").
const RECORD_ID_RE = /^[a-z][a-zA-Z0-9]*$/;
// docs/sync-protocol-v1.md, "Entity records".
const ENTITY_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._%*-]*$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    let identity;
    try {
      identity = await verifyRequest(request, env);
    } catch (error) {
      const status = error instanceof AuthError ? error.status : 401;
      const message = error instanceof Error ? error.message : "auth failed";
      return json({ error: message }, status);
    }

    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const store = new SyncStore(env.DB);

    if (parts.length === 2 && parts[0] === "records" && request.method === "GET") {
      const recordId = parts[1];
      if (!RECORD_ID_RE.test(recordId)) return json({ error: "invalid recordId" }, 400);

      const sinceParam = url.searchParams.get("since");
      const since = sinceParam === null ? 0 : Number(sinceParam);
      if (!Number.isInteger(since) || since < 0) return json({ error: "invalid since" }, 400);

      const rows = await store.list(identity.accountId, recordId, since);
      return json(rows.map(serializeEntity), 200);
    }

    if (parts.length === 3 && parts[0] === "records" && request.method === "PUT") {
      const [, recordId, entityId] = parts;
      if (!RECORD_ID_RE.test(recordId)) return json({ error: "invalid recordId" }, 400);
      if (!ENTITY_ID_RE.test(entityId)) return json({ error: "invalid entityId" }, 400);

      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return json({ error: "invalid JSON body" }, 400);
      }
      const parsed = parsePushBody(body);
      if (!parsed) {
        return json(
          { error: "expected {expectedRevision: int>=0, tombstone: boolean, payload: string|null}" },
          400,
        );
      }

      const outcome = await store.push(
        identity.accountId,
        recordId,
        entityId,
        parsed.expectedRevision,
        parsed.tombstone,
        parsed.payload,
      );
      if (outcome.conflict) {
        return json({ revision: outcome.revision, conflict: serializeEntity(outcome.conflict) }, 409);
      }
      return json({ revision: outcome.revision }, 200);
    }

    // Sync 4 (IGSH-251): the small single-row vault key envelope a device
    // fetches to bootstrap before it can unwrap and sync entity records.
    // Never the entity records themselves (see the module doc above).
    if (parts.length === 1 && parts[0] === "vault" && request.method === "GET") {
      const vault = await store.getVault(identity.accountId);
      if (!vault) return json({ error: "not found" }, 404);
      return json(serializeVault(vault), 200);
    }

    if (parts.length === 1 && parts[0] === "vault" && request.method === "PUT") {
      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return json({ error: "invalid JSON body" }, 400);
      }
      const parsed = parseVaultBody(body);
      if (!parsed) {
        return json({ error: "expected {expectedRevision: int>=0, envelope: string}" }, 400);
      }
      const outcome = await store.putVault(identity.accountId, parsed.expectedRevision, parsed.envelope);
      if (outcome.conflict) {
        return json({ revision: outcome.revision, conflict: serializeVault(outcome.conflict) }, 409);
      }
      return json({ revision: outcome.revision }, 200);
    }

    return json({ error: "not found" }, 404);
  },
};

function serializeVault(vault: VaultRow) {
  return { revision: vault.revision, envelope: vault.envelope };
}

function parseVaultBody(body: unknown): { expectedRevision: number; envelope: string } | null {
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  if (typeof b.expectedRevision !== "number" || !Number.isInteger(b.expectedRevision) || b.expectedRevision < 0) {
    return null;
  }
  if (typeof b.envelope !== "string" || b.envelope.length === 0) return null;
  return { expectedRevision: b.expectedRevision, envelope: b.envelope };
}

function serializeEntity(entity: RemoteEntity) {
  return {
    entityId: entity.entityId,
    revision: entity.revision,
    tombstone: entity.tombstone,
    payload: entity.payload,
  };
}

function parsePushBody(
  body: unknown,
): { expectedRevision: number; tombstone: boolean; payload: string | null } | null {
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  if (typeof b.expectedRevision !== "number" || !Number.isInteger(b.expectedRevision) || b.expectedRevision < 0) {
    return null;
  }
  if (typeof b.tombstone !== "boolean") return null;
  if (b.payload !== null && typeof b.payload !== "string") return null;
  const payload = b.payload as string | null;
  return { expectedRevision: b.expectedRevision, tombstone: b.tombstone, payload };
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
