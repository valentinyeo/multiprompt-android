/**
 * Bearer-token auth for the production sync server (IGSH-249, M3).
 *
 * This worker is NOT sat behind a Cloudflare Access application — unlike the
 * M0 whoami stub (src/whoami.ts), which validates the edge-injected
 * `Cf-Access-Jwt-Assertion` header. Here the client (Android or desktop) is
 * the one that completed the Access-for-SaaS OIDC login and holds its own
 * bearer access token; this worker verifies that token directly against the
 * Access app's own per-app JWKS, issuer, and audience (its client_id). See
 * docs/sync-protocol-v1.md, "M0 spike result" and "Storage and sync
 * semantics".
 */

import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from "jose";

export interface AuthEnv {
  /** Per-app OIDC issuer, e.g. https://<team>.cloudflareaccess.com/cdn-cgi/access/sso/oidc/<client_id> */
  OIDC_ISSUER: string;
  /** The Access-for-SaaS app's client_id — also the expected JWT audience. */
  OIDC_CLIENT_ID: string;
}

export class AuthError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Cached across invocations within the same isolate. */
let jwks: ReturnType<typeof createRemoteJWKSet> | null = null;
let jwksIssuer: string | null = null;

function jwksFor(issuer: string) {
  if (!jwks || jwksIssuer !== issuer) {
    jwks = createRemoteJWKSet(new URL(`${issuer}/jwks`));
    jwksIssuer = issuer;
  }
  return jwks;
}

export interface Identity {
  /** Access JWT `sub` — the stable identity used as D1's account_id. */
  accountId: string;
  email: string | null;
}

/**
 * Verifies a bearer token against the given JWKS/issuer/audience and expiry
 * (checked by jose). Throws AuthError with the status to return on any
 * failure. Extracted from verifyRequest so tests can inject a local JWKS
 * (jose's createLocalJWKSet) instead of hitting the network.
 */
export async function verifyToken(
  token: string,
  jwksResolver: JWTVerifyGetKey,
  issuer: string,
  audience: string,
): Promise<Identity> {
  let payload: JWTPayload;
  try {
    const result = await jwtVerify(token, jwksResolver, { issuer, audience });
    payload = result.payload;
  } catch (error) {
    throw new AuthError(401, error instanceof Error ? error.message : "invalid token");
  }

  const accountId = typeof payload.sub === "string" ? payload.sub : null;
  if (!accountId) {
    throw new AuthError(401, "token has no sub claim");
  }
  const email = typeof payload.email === "string" ? payload.email : null;
  return { accountId, email };
}

/**
 * Verifies `Authorization: Bearer <token>` against the Access-for-SaaS app's
 * remote JWKS. Never accepts Cloudflare service-token headers on this path
 * (docs/sync-protocol-v1.md, "Service tokens — machine auth only, never in
 * the APK").
 */
export async function verifyRequest(request: Request, env: AuthEnv): Promise<Identity> {
  if (request.headers.has("CF-Access-Client-Id") || request.headers.has("CF-Access-Client-Secret")) {
    throw new AuthError(403, "service tokens are not accepted on the user path");
  }

  const header = request.headers.get("Authorization");
  if (!header || !header.startsWith("Bearer ")) {
    throw new AuthError(401, "missing Authorization: Bearer <token>");
  }
  const token = header.slice("Bearer ".length).trim();
  if (!token) {
    throw new AuthError(401, "empty bearer token");
  }

  return verifyToken(token, jwksFor(env.OIDC_ISSUER), env.OIDC_ISSUER, env.OIDC_CLIENT_ID);
}
