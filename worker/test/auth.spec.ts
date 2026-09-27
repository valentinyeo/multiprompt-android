import { describe, expect, it } from "vitest";
import { createLocalJWKSet } from "jose";
import { AuthError, verifyToken } from "../src/auth";
import { TEST_AUDIENCE, TEST_ISSUER, mintAccessToken, testJwks } from "./jwt-fixture";

async function localJwks() {
  return createLocalJWKSet((await testJwks()) as never);
}

describe("verifyToken", () => {
  it("accepts a valid token and returns its sub as accountId", async () => {
    const token = await mintAccessToken({ sub: "user-abc", email: "valentin.yeo@gmail.com" });
    const identity = await verifyToken(token, await localJwks(), TEST_ISSUER, TEST_AUDIENCE);
    expect(identity.accountId).toBe("user-abc");
    expect(identity.email).toBe("valentin.yeo@gmail.com");
  });

  it("rejects an expired token", async () => {
    const token = await mintAccessToken({ expiresAtSeconds: Math.floor(Date.now() / 1000) - 60 });
    await expect(verifyToken(token, await localJwks(), TEST_ISSUER, TEST_AUDIENCE)).rejects.toThrow(AuthError);
  });

  it("rejects a token with the wrong audience", async () => {
    const token = await mintAccessToken({ audience: "some-other-client-id" });
    await expect(verifyToken(token, await localJwks(), TEST_ISSUER, TEST_AUDIENCE)).rejects.toThrow(AuthError);
  });

  it("rejects a token with the wrong issuer", async () => {
    const token = await mintAccessToken({ issuer: "https://not-the-real-issuer.invalid" });
    await expect(verifyToken(token, await localJwks(), TEST_ISSUER, TEST_AUDIENCE)).rejects.toThrow(AuthError);
  });

  it("rejects a garbage token", async () => {
    await expect(verifyToken("not-a-jwt", await localJwks(), TEST_ISSUER, TEST_AUDIENCE)).rejects.toThrow(AuthError);
  });
});
