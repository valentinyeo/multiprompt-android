
import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from "jose";

export interface AuthEnv {
  OIDC_ISSUER: string;
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
  accountId: string;
  email: string | null;
}

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
