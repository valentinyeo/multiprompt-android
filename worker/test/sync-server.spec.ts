import { SELF, fetchMock } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { TEST_AUDIENCE, TEST_ISSUER, mintAccessToken, testJwks } from "./jwt-fixture";

// verifyRequest() fetches `${issuer}/jwks` on the first token it needs to
// verify, then jose caches the resolved keyset in-memory — so one persistent
// mock covers every test in this file, however many times (or few) it's
// actually hit.
beforeAll(async () => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
  const jwks = await testJwks();
  fetchMock
    .get(TEST_ISSUER)
    .intercept({ path: "/jwks", method: "GET" })
    .reply(200, jwks, { headers: { "content-type": "application/json" } })
    .persist();
});

function req(path: string, init: RequestInit & { token?: string } = {}): Request {
  const headers = new Headers(init.headers);
  if (init.token !== undefined) headers.set("Authorization", `Bearer ${init.token}`);
  return new Request(`https://sync.multiprompt.dev${path}`, { ...init, headers });
}

describe("auth gate", () => {
  it("401s with no Authorization header", async () => {
    const res = await SELF.fetch(req("/records/hosts"));
    expect(res.status).toBe(401);
  });

  it("401s with a malformed Authorization header", async () => {
    const res = await SELF.fetch(req("/records/hosts", { headers: { Authorization: "not-bearer" } }));
    expect(res.status).toBe(401);
  });

  it("401s with an invalid token", async () => {
    const res = await SELF.fetch(req("/records/hosts", { token: "garbage.not.a.jwt" }));
    expect(res.status).toBe(401);
  });

  it("401s with an expired token", async () => {
    const token = await mintAccessToken({ expiresAtSeconds: Math.floor(Date.now() / 1000) - 60 });
    const res = await SELF.fetch(req("/records/hosts", { token }));
    expect(res.status).toBe(401);
  });

  it("401s with a token for the wrong audience", async () => {
    const token = await mintAccessToken({ audience: "some-other-app" });
    const res = await SELF.fetch(req("/records/hosts", { token }));
    expect(res.status).toBe(401);
  });

  it("403s a request carrying a service-token header, even with a bearer token", async () => {
    const token = await mintAccessToken({});
    const res = await SELF.fetch(
      req("/records/hosts", { token, headers: { "CF-Access-Client-Id": "abc" } }),
    );
    expect(res.status).toBe(403);
  });
});

describe("entity sync", () => {
  it("round-trips a push then a list", async () => {
    const token = await mintAccessToken({ sub: "acct-roundtrip" });

    const push = await SELF.fetch(
      req("/records/hosts/host-1", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "sealed-payload-v1" }),
      }),
    );
    expect(push.status).toBe(200);
    expect(await push.json()).toEqual({ revision: 1 });

    const list = await SELF.fetch(req("/records/hosts?since=0", { token }));
    expect(list.status).toBe(200);
    expect(await list.json()).toEqual([
      { entityId: "host-1", revision: 1, tombstone: false, payload: "sealed-payload-v1" },
    ]);

    const listSinceAhead = await SELF.fetch(req("/records/hosts?since=2", { token }));
    expect(await listSinceAhead.json()).toEqual([]);
  });

  it("increments the revision on a second successful push", async () => {
    const token = await mintAccessToken({ sub: "acct-increment" });

    await SELF.fetch(
      req("/records/hosts/host-1", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "v1" }),
      }),
    );
    const second = await SELF.fetch(
      req("/records/hosts/host-1", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 1, tombstone: false, payload: "v2" }),
      }),
    );
    expect(second.status).toBe(200);
    expect(await second.json()).toEqual({ revision: 2 });
  });

  it("returns 409 with the current row on a stale expectedRevision", async () => {
    const token = await mintAccessToken({ sub: "acct-conflict" });

    await SELF.fetch(
      req("/records/hosts/host-1", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "v1" }),
      }),
    );
    // Someone else already wrote revision 1; this client still thinks it's 0.
    const stale = await SELF.fetch(
      req("/records/hosts/host-1", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "v2-stale" }),
      }),
    );
    expect(stale.status).toBe(409);
    expect(await stale.json()).toEqual({
      revision: 1,
      conflict: { entityId: "host-1", revision: 1, tombstone: false, payload: "v1" },
    });
  });

  it("isolates entities per account", async () => {
    const tokenA = await mintAccessToken({ sub: "acct-a" });
    const tokenB = await mintAccessToken({ sub: "acct-b" });

    await SELF.fetch(
      req("/records/hosts/shared-id", {
        method: "PUT",
        token: tokenA,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "account-a-payload" }),
      }),
    );

    const listForB = await SELF.fetch(req("/records/hosts?since=0", { token: tokenB }));
    expect(await listForB.json()).toEqual([]);

    // Account B can create the same entityId independently (expectedRevision 0 = create).
    const pushForB = await SELF.fetch(
      req("/records/hosts/shared-id", {
        method: "PUT",
        token: tokenB,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, tombstone: false, payload: "account-b-payload" }),
      }),
    );
    expect(pushForB.status).toBe(200);
  });
});

describe("vault envelope bootstrap", () => {
  it("401s with no Authorization header", async () => {
    const res = await SELF.fetch(req("/vault"));
    expect(res.status).toBe(401);
  });

  it("404s before a vault has been created", async () => {
    const token = await mintAccessToken({ sub: "acct-no-vault" });
    const res = await SELF.fetch(req("/vault", { token }));
    expect(res.status).toBe(404);
  });

  it("creates the vault, then fetches it back", async () => {
    const token = await mintAccessToken({ sub: "acct-vault-create" });

    const create = await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, envelope: "sealed-envelope-v1" }),
      }),
    );
    expect(create.status).toBe(200);
    expect(await create.json()).toEqual({ revision: 1 });

    const fetched = await SELF.fetch(req("/vault", { token }));
    expect(fetched.status).toBe(200);
    expect(await fetched.json()).toEqual({ revision: 1, envelope: "sealed-envelope-v1" });
  });

  it("409s a stale rewrap and reports the current envelope as the conflict", async () => {
    const token = await mintAccessToken({ sub: "acct-vault-conflict" });

    await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, envelope: "envelope-v1" }),
      }),
    );

    const stale = await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, envelope: "envelope-v2-conflicting" }),
      }),
    );
    expect(stale.status).toBe(409);
    expect(await stale.json()).toEqual({
      revision: 1,
      conflict: { revision: 1, envelope: "envelope-v1" },
    });

    // The right expectedRevision (rewrap after reading the current one) succeeds.
    const rewrap = await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 1, envelope: "envelope-v2" }),
      }),
    );
    expect(rewrap.status).toBe(200);
    expect(await rewrap.json()).toEqual({ revision: 2 });
  });

  it("isolates vaults per account", async () => {
    const tokenA = await mintAccessToken({ sub: "acct-vault-a" });
    const tokenB = await mintAccessToken({ sub: "acct-vault-b" });

    await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token: tokenA,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: 0, envelope: "account-a-envelope" }),
      }),
    );

    const forB = await SELF.fetch(req("/vault", { token: tokenB }));
    expect(forB.status).toBe(404);
  });

  it("rejects a malformed vault body", async () => {
    const token = await mintAccessToken({ sub: "acct-vault-bad-body" });
    const res = await SELF.fetch(
      req("/vault", {
        method: "PUT",
        token,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ envelope: "missing-expected-revision" }),
      }),
    );
    expect(res.status).toBe(400);
  });
});
