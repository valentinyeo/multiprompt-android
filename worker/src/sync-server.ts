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
 *   GET /changes, POST /batch, GET/POST/DELETE /devices
 *
 * Auth: Access-for-SaaS Bearer tokens (see auth.ts), or revocable Device
 * credentials on the sync and device-list routes.
 */

import { verifyRequest, AuthError, type AuthEnv } from "./auth";
import { SyncStore, type RemoteEntity, type VaultRow, type WriteOp } from "./store";
import { authenticateDevice, registerDevice, listDevices, revokeDevice, acknowledge } from "./device";

export interface Env extends AuthEnv {
  DB: D1Database;
  SYNC_PAUSED?: string;
  MIN_DESKTOP_VERSION?: string;
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
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const store = new SyncStore(env.DB);
    const header = request.headers.get("Authorization") ?? "";
    let identity;
    try {
      if (request.headers.has("CF-Access-Client-Id") || request.headers.has("CF-Access-Client-Secret")) {
        throw new AuthError(403, "service tokens are not accepted on the user path");
      }
      identity = header.startsWith("Device ") && !(parts[0] === "devices" && request.method === "POST")
        ? await authenticateDevice(env.DB, header) : await verifyRequest(request, env);
    } catch (error) {
      const status = error instanceof AuthError ? error.status : 401;
      const message = error instanceof Error ? error.message : "auth failed";
      return json({ error: message }, status);
    }
    const deviceId = "deviceId" in identity ? identity.deviceId : null;

    if (deviceId && parts[0] === "vault" && request.method !== "GET") {
      return json({ error: "bearer required" }, 401);
    }
    if (["changes", "batch", "records", "vault", "devices"].includes(parts[0])) {
      const gate = await syncGate(request, env, store, identity.accountId, parts.length === 1 &&
        (parts[0] === "changes" || parts[0] === "batch"));
      if (gate) return gate;
    }
    if (parts[0] === "devices") {
      if (parts.length === 1 && request.method === "POST") {
        return json(await registerDevice(env.DB, identity.accountId), 201);
      }
      if (parts.length === 1 && request.method === "GET") {
        return json(await listDevices(env.DB, identity.accountId), 200);
      }
      if (parts.length === 2 && request.method === "DELETE") {
        const revoked = await revokeDevice(env.DB, identity.accountId, parts[1]);
        return revoked ? json({ revoked: true }, 200) : json({ error: "not found" }, 404);
      }
    }

    if (parts.length === 1 && parts[0] === "changes" && request.method === "GET") {
      const after = integerParam(url.searchParams.get("after"), 0, Number.MAX_SAFE_INTEGER);
      const wait = integerParam(url.searchParams.get("wait") ?? "0", 0, 55);
      const limit = integerParam(url.searchParams.get("limit") ?? "500", 1, 500);
      if (after === null || wait === null || limit === null) return json({ error: "invalid after, wait or limit" }, 400);
      if (deviceId && !(await acknowledge(env.DB, deviceId, after))) return json({ error: "device revoked" }, 401);
      const deadline = Date.now() + wait * 1000;
      while (true) {
        const state = await store.accountState(identity.accountId);
        if (env.SYNC_PAUSED === "true" || env.SYNC_PAUSED === "1" || state.paused) return json({ paused: true }, 503);
        // Recheck the device on each wake so a revoked long poll cannot return data.
        if (deviceId && !(await acknowledge(env.DB, deviceId, after))) return json({ error: "device revoked" }, 401);
        const expired = Date.now() >= deadline;
        if (state.head > after || expired || wait === 0) {
          const { changes, more } = await store.changes(identity.accountId, after, limit);
          if (changes.length || expired || wait === 0) {
            return json({ head: state.head, accountId: identity.accountId, more, changes }, 200);
          }
        }
        await new Promise(resolve => setTimeout(resolve, Math.min(wait === 55 ? 3000 : 1500, deadline - Date.now())));
      }
    }

    if (parts.length === 1 && parts[0] === "batch" && request.method === "POST") {
      let body: unknown;
      try { body = await request.json(); } catch { return json({ error: "invalid JSON body" }, 400); }
      if (!body || typeof body !== "object" || !Array.isArray((body as { ops?: unknown }).ops) ||
          (body as { ops: unknown[] }).ops.length > 50 ||
          !(body as { ops: unknown[] }).ops.every(validOp)) {
        return json({ error: "expected {ops:[{recordId,entityId,expectedRevision,tombstone,payload}]} (max 50)" }, 400);
      }
      const { head, results } = await store.writeMany(identity.accountId, (body as { ops: WriteOp[] }).ops, deviceId, true);
      if (deviceId && !(await acknowledge(env.DB, deviceId, 0))) return json({ error: "device revoked" }, 401);
      const mapped = results.map(outcome => outcome.conflict
        ? { conflict: serializeEntity(outcome.conflict) } : { revision: outcome.revision });
      return json({ head, results: mapped }, results.some(outcome => outcome.conflict) ? 409 : 200);
    }

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
        deviceId,
      );
      if (deviceId && !(await acknowledge(env.DB, deviceId, 0))) return json({ error: "device revoked" }, 401);
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

function integerParam(value: string | null, min: number, max: number): number | null {
  if (value === null || !/^(0|[1-9][0-9]*)$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= min && n <= max ? n : null;
}

function validOp(value: unknown): value is WriteOp {
  if (!value || typeof value !== "object") return false;
  const op = value as Record<string, unknown>;
  return typeof op.recordId === "string" && RECORD_ID_RE.test(op.recordId) &&
    typeof op.entityId === "string" && ENTITY_ID_RE.test(op.entityId) && parsePushBody(op) !== null;
}

async function syncGate(request: Request, env: Env, store: SyncStore, accountId: string, pausable: boolean): Promise<Response | null> {
  if (pausable && (env.SYNC_PAUSED === "true" || env.SYNC_PAUSED === "1" || (await store.accountState(accountId)).paused)) {
    return json({ paused: true }, 503);
  }
  if (env.MIN_DESKTOP_VERSION) {
    const client = /^desktop\/(\d+(?:\.\d+)*)\/[^/]+$/.exec(request.headers.get("X-MP-Client") ?? "");
    if (!client && (request.headers.get("X-MP-Client") ?? "").startsWith("desktop/")) {
      return json({ error: "invalid desktop client version" }, 400);
    }
    if (client) {
      const minimum = env.MIN_DESKTOP_VERSION.split(".").map(Number);
      if (minimum.length < 1 || minimum.some(n => !Number.isSafeInteger(n) || n < 0)) return json({ error: "invalid minimum version" }, 503);
      const actual = client[1].split(".").map(Number);
      for (let i = 0; i < Math.max(actual.length, minimum.length); i++) {
        if ((actual[i] ?? 0) < (minimum[i] ?? 0)) return json({ error: "minimum desktop version", minDesktopVersion: env.MIN_DESKTOP_VERSION }, 426);
        if ((actual[i] ?? 0) > (minimum[i] ?? 0)) break;
      }
    }
  }
  return null;
}
