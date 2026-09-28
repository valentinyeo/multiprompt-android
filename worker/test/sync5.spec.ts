import { SELF, env, fetchMock } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import server from "../src/sync-server";
import { TEST_ISSUER, mintAccessToken, testJwks } from "./jwt-fixture";

beforeAll(async () => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
  fetchMock.get(TEST_ISSUER).intercept({ path: "/jwks", method: "GET" })
    .reply(200, await testJwks(), { headers: { "content-type": "application/json" } }).persist();
});

function request(path: string, credential: string, method = "GET", body?: unknown, headers: Record<string, string> = {}) {
  return new Request(`https://sync.multiprompt.dev${path}`, {
    method, headers: { Authorization: credential, ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
const bearer = (token: string) => `Bearer ${token}`;
const op = (recordId: string, entityId: string, expectedRevision = 0, payload: string | null = "sealed") =>
  ({ recordId, entityId, expectedRevision, tombstone: payload === null, payload });

async function device(accountId: string) {
  const token = await mintAccessToken({ sub: accountId });
  const response = await SELF.fetch(request("/devices", bearer(token), "POST"));
  expect(response.status).toBe(201);
  const registered = await response.json() as { deviceId: string; secret: string; accountId: string };
  return { token, ...registered, credential: `Device ${registered.deviceId}.${registered.secret}` };
}

describe("Sync 5 devices", () => {
  it("requires bearer to register, stores only the hash and revokes across all sync endpoints", async () => {
    const a = await device("sync5-register");
    expect(a.accountId).toBe("sync5-register");
    const row = await env.DB.prepare("SELECT secret_hash FROM sync_device WHERE device_id = ?1")
      .bind(a.deviceId).first<{ secret_hash: string }>();
    expect(row?.secret_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row?.secret_hash).not.toContain(a.secret);
    expect((await SELF.fetch(request("/devices", a.credential, "POST"))).status).toBe(401);
    expect((await SELF.fetch(request("/devices", a.credential))).status).toBe(200);
    expect((await SELF.fetch(request("/records/tab", a.credential))).status).toBe(200);
    expect((await SELF.fetch(request("/vault", a.credential))).status).toBe(404);
    expect((await SELF.fetch(request("/vault", a.credential, "PUT", { expectedRevision: 0, envelope: "x" }))).status).toBe(401);
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("tab", "t1")] }))).status).toBe(200);
    const devices = await (await SELF.fetch(request("/devices", a.credential))).json() as { deviceId: string; ackedSeq: number }[];
    expect(devices[0].deviceId).toBe(a.deviceId);
    expect((await SELF.fetch(request("/devices/another-account-device", a.credential, "DELETE"))).status).toBe(404);
    expect((await SELF.fetch(request(`/devices/${a.deviceId}`, bearer(a.token), "DELETE"))).status).toBe(200);
    for (const path of ["/records/tab", "/vault", "/changes?after=0", "/devices"]) {
      expect((await SELF.fetch(request(path, a.credential))).status).toBe(401);
    }
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: [] }))).status).toBe(401);
    expect((await SELF.fetch(request("/records/tab/t2", a.credential, "PUT", op("tab", "t2")))).status).toBe(401);
    expect((await SELF.fetch(request("/changes?after=0", `Device ${a.deviceId}.${"a".repeat(43)}`))).status).toBe(401);
  });

  it("stops a pending long poll when a device is revoked but keeps a second device signed in", async () => {
    const first = await device("sync5-revoke-poll");
    const second = await device("sync5-revoke-poll");
    const waiting = SELF.fetch(request("/changes?after=0&wait=25", first.credential));
    await new Promise(resolve => setTimeout(resolve, 100));
    expect((await SELF.fetch(request(`/devices/${first.deviceId}`, second.credential, "DELETE"))).status).toBe(200);
    expect((await waiting).status).toBe(401);
    expect((await SELF.fetch(request("/changes?after=0", second.credential))).status).toBe(200);
  });

  it("isolates device lists and device authentication per account", async () => {
    const a = await device("sync5-dev-a");
    const b = await device("sync5-dev-b");
    expect(await (await SELF.fetch(request("/devices", b.credential))).json()).toMatchObject([{ deviceId: b.deviceId }]);
    expect((await SELF.fetch(request(`/devices/${a.deviceId}`, b.credential, "DELETE"))).status).toBe(404);
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("tab", "shared")] }))).status).toBe(200);
    expect(await (await SELF.fetch(request("/changes?after=0", b.credential))).json()).toMatchObject({ head: 0, changes: [] });
    expect((await SELF.fetch(request("/records/tab/shared", b.credential, "PUT", op("tab", "shared")))).status).toBe(200);
    expect(await (await SELF.fetch(request("/changes?after=0", a.credential))).json()).toMatchObject({ head: 1, accountId: "sync5-dev-a" });
  });
});

describe("Sync 5 feed and batch", () => {
  it("assigns account-wide ordered seq on PUT and batch, paginates, and acknowledges cursors", async () => {
    const a = await device("sync5-order");
    const put = request("/records/tab/t1", a.credential, "PUT", op("tab", "t1"));
    expect((await SELF.fetch(put)).status).toBe(200);
    const batch = await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("pref", "p1"), op("tab", "t1", 1, null)] }));
    expect(batch.status).toBe(200);
    expect(await batch.json()).toEqual({ head: 3, results: [{ revision: 1 }, { revision: 2 }] });
    const first = await (await SELF.fetch(request("/changes?after=0&limit=1", a.credential))).json();
    // Feed holds the latest state of each entity; superseded seq values are not retained.
    expect(first).toEqual({ head: 3, accountId: "sync5-order", more: true, changes: [
      { seq: 2, recordId: "pref", entityId: "p1", revision: 1, tombstone: false, payload: "sealed" },
    ] });
    const second = await (await SELF.fetch(request("/changes?after=2&limit=1", a.credential))).json();
    expect(second).toEqual({ head: 3, accountId: "sync5-order", more: false, changes: [
      { seq: 3, recordId: "tab", entityId: "t1", revision: 2, tombstone: true, payload: "sealed" },
    ] });
    await SELF.fetch(request("/changes?after=3", a.credential));
    const row = await env.DB.prepare("SELECT acked_seq FROM sync_device WHERE device_id = ?1")
      .bind(a.deviceId).first<{ acked_seq: number }>();
    expect(row?.acked_seq).toBe(3);
  });

  it("returns 409 with per-op conflicts while applying valid ops without sequence gaps", async () => {
    const a = await device("sync5-conflict");
    await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("tab", "x")] }));
    const res = await SELF.fetch(request("/batch", a.credential, "POST", { ops: [
      op("tab", "x", 0, "stale"), op("tab", "y"), op("pref", "missing", 7), op("tab", "x", 1, "new"),
    ] }));
    expect(res.status).toBe(409);
    // Conflicts reflect the latest state after the full transaction.
    expect(await res.json()).toEqual({ head: 3, results: [
      { conflict: { entityId: "x", revision: 2, tombstone: false, payload: "new" } },
      { revision: 1 },
      { conflict: { entityId: "missing", revision: 0, tombstone: false, payload: null } },
      { revision: 2 },
    ] });
    expect(await (await SELF.fetch(request("/changes?after=1", a.credential))).json()).toMatchObject({
      head: 3, changes: [{ seq: 2, entityId: "y" }, { seq: 3, entityId: "x" }],
    });
  });

  it("accepts the maximum 50 ops in one transaction", async () => {
    const a = await device("sync5-fifty");
    const res = await SELF.fetch(request("/batch", a.credential, "POST", {
      ops: Array.from({ length: 50 }, (_, i) => op("tab", `t${i}`)),
    }));
    expect(res.status).toBe(200);
    const body = await res.json() as { head: number; results: unknown[] };
    expect(body.head).toBe(50);
    expect(body.results).toHaveLength(50);
    expect(await (await SELF.fetch(request("/changes?after=0&limit=1", a.credential))).json()).toMatchObject({
      head: 50, changes: [{ seq: 1, entityId: "t0" }],
    });
  });

  it("wakes a pending long poll after a write", async () => {
    const a = await device("sync5-wake");
    const started = Date.now();
    const waiting = SELF.fetch(request("/changes?after=0&wait=25", a.credential));
    await new Promise(resolve => setTimeout(resolve, 100));
    await SELF.fetch(request("/records/tab/wake", a.credential, "PUT", op("tab", "wake")));
    const result = await waiting;
    expect(result.status).toBe(200);
    expect(await result.json()).toMatchObject({ head: 1, changes: [{ seq: 1, entityId: "wake" }] });
    expect(Date.now() - started).toBeLessThan(5000);
  });

  it("validates ranges, IDs, batch size and credentials", async () => {
    const a = await device("sync5-bad");
    for (const path of ["/changes", "/changes?after=-1", "/changes?after=0&wait=56", "/changes?after=0&limit=0"]) {
      expect((await SELF.fetch(request(path, a.credential))).status).toBe(400);
    }
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: Array(51).fill(op("tab", "x")) }))).status).toBe(400);
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("bad-id", "x")] }))).status).toBe(400);
  });
});

describe("Sync 5 gates", () => {
  it("pauses by env or account while leaving old endpoints and other accounts available", async () => {
    const a = await device("sync5-paused");
    const b = await device("sync5-unpaused");
    const pausedEnv = { ...env, SYNC_PAUSED: "true" };
    for (const path of ["/changes?after=0", "/batch"]) {
      const method = path === "/batch" ? "POST" : "GET";
      const res = await server.fetch(request(path, a.credential, method, method === "POST" ? { ops: [] } : undefined), pausedEnv);
      expect(res.status).toBe(503);
      expect(await res.json()).toEqual({ paused: true });
    }
    expect((await server.fetch(request("/records/tab", a.credential), pausedEnv)).status).toBe(200);
    await env.DB.prepare("INSERT INTO sync_account (account_id, head, paused) VALUES (?1, 0, 1)")
      .bind(a.accountId).run();
    expect((await SELF.fetch(request("/changes?after=0", a.credential))).status).toBe(503);
    expect((await SELF.fetch(request("/batch", a.credential, "POST", { ops: [op("tab", "t1")] }))).status).toBe(503);
    expect((await SELF.fetch(request("/changes?after=0", b.credential))).status).toBe(200);
    // Android legacy PUT remains usable when the live-sync kill switch is on.
    expect((await SELF.fetch(request("/records/tab/legacy", a.credential, "PUT", op("tab", "legacy")))).status).toBe(200);
    expect((await server.fetch(request("/records/tab/legacy", a.credential, "PUT", op("tab", "legacy", 1)), pausedEnv)).status).toBe(200);
  });

  it("compares numeric desktop versions and does not gate Android or older endpoints without a desktop header", async () => {
    const a = await device("sync5-version");
    const gated = { ...env, MIN_DESKTOP_VERSION: "0.28.670" };
    const fetch = (path: string, header?: string) => server.fetch(request(path, a.credential, "GET", undefined,
      header ? { "X-MP-Client": header } : {}), gated);
    expect((await fetch("/changes?after=0", "desktop/0.28.669/x64")).status).toBe(426);
    expect(await (await fetch("/changes?after=0", "desktop/0.28.669/x64")).json()).toEqual({
      error: "minimum desktop version", minDesktopVersion: "0.28.670",
    });
    expect((await fetch("/changes?after=0", "desktop/0.28.670/arm64")).status).toBe(200);
    expect((await fetch("/changes?after=0", "desktop/0.28.700/x64")).status).toBe(200);
    expect((await fetch("/records/tab", "desktop/0.28.1/x64")).status).toBe(426);
    expect((await fetch("/records/tab")).status).toBe(200);
  });
});
