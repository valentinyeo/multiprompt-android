import { AuthError, type Identity } from "./auth";

export interface DeviceIdentity extends Identity {
  deviceId: string;
}

interface DeviceRow {
  device_id: string;
  account_id: string;
  secret_hash: string;
  created_at: string;
  last_seen_at: string | null;
  acked_seq: number;
  revoked_at: string | null;
}

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, "0")).join("");
}

async function hash(secret: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret)));
}

export async function registerDevice(db: D1Database, accountId: string) {
  const deviceId = crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const secret = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  await db.prepare(
    "INSERT INTO sync_device (device_id, account_id, secret_hash, created_at) VALUES (?1, ?2, ?3, ?4)",
  ).bind(deviceId, accountId, await hash(secret), new Date().toISOString()).run();
  return { deviceId, secret, accountId };
}

export async function authenticateDevice(db: D1Database, header: string): Promise<DeviceIdentity> {
  const match = /^Device ([0-9a-f-]{36})\.([A-Za-z0-9_-]{43})$/.exec(header);
  if (!match) throw new AuthError(401, "invalid device credential");
  const row = await db.prepare("SELECT * FROM sync_device WHERE device_id = ?1").bind(match[1]).first<DeviceRow>();
  const digest = await hash(match[2]);
  // Compare every byte of the digest, even when the supplied credential is wrong.
  let difference = 0;
  const stored = row?.secret_hash ?? "0".repeat(64);
  for (let i = 0; i < 64; i++) difference |= digest.charCodeAt(i) ^ stored.charCodeAt(i);
  if (!row || row.revoked_at || difference !== 0) throw new AuthError(401, "invalid device credential");
  const now = new Date().toISOString();
  if (!row.last_seen_at || Date.now() - Date.parse(row.last_seen_at) >= 600_000) {
    const updated = await db.prepare(
      "UPDATE sync_device SET last_seen_at = ?1 WHERE device_id = ?2 AND revoked_at IS NULL",
    ).bind(now, row.device_id).run();
    if (!updated.meta.changes) throw new AuthError(401, "invalid device credential");
  }
  return { accountId: row.account_id, email: null, deviceId: row.device_id };
}

export async function listDevices(db: D1Database, accountId: string) {
  const rows = await db.prepare(
    "SELECT device_id, created_at, last_seen_at, acked_seq, revoked_at FROM sync_device WHERE account_id = ?1 ORDER BY created_at, device_id",
  ).bind(accountId).all<Omit<DeviceRow, "account_id" | "secret_hash">>();
  return (rows.results ?? []).map(row => ({
    deviceId: row.device_id, createdAt: row.created_at, lastSeenAt: row.last_seen_at,
    ackedSeq: row.acked_seq, revokedAt: row.revoked_at,
  }));
}

export async function revokeDevice(db: D1Database, accountId: string, deviceId: string): Promise<boolean> {
  const result = await db.prepare(
    "UPDATE sync_device SET revoked_at = ?1 WHERE account_id = ?2 AND device_id = ?3 AND revoked_at IS NULL",
  ).bind(new Date().toISOString(), accountId, deviceId).run();
  return result.meta.changes > 0;
}

export async function acknowledge(db: D1Database, deviceId: string, after: number): Promise<boolean> {
  const result = await db.prepare(
    "UPDATE sync_device SET acked_seq = MAX(acked_seq, ?1) WHERE device_id = ?2 AND revoked_at IS NULL",
  ).bind(after, deviceId).run();
  return result.meta.changes > 0;
}
