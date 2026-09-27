import { SignJWT, exportJWK, generateKeyPair } from "jose";

export const TEST_ISSUER = "https://sync-test.invalid";
export const TEST_AUDIENCE = "test-client-id";
const KEY_ID = "test-key-1";

let keyPairPromise: ReturnType<typeof generateKeyPair> | null = null;

function keyPair() {
  if (!keyPairPromise) keyPairPromise = generateKeyPair("RS256");
  return keyPairPromise;
}

/** The JWKS document the mocked `${issuer}/jwks` endpoint should serve. */
export async function testJwks(): Promise<{ keys: unknown[] }> {
  const { publicKey } = await keyPair();
  const jwk = await exportJWK(publicKey);
  return { keys: [{ ...jwk, kid: KEY_ID, alg: "RS256", use: "sig" }] };
}

export interface MintOptions {
  sub?: string;
  email?: string;
  issuer?: string;
  audience?: string;
  /** Absolute epoch-seconds expiry. Defaults to 5 minutes from now. */
  expiresAtSeconds?: number;
}

export async function mintAccessToken(options: MintOptions = {}): Promise<string> {
  const { privateKey } = await keyPair();
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ email: options.email ?? "valentin.yeo@gmail.com" })
    .setProtectedHeader({ alg: "RS256", kid: KEY_ID })
    .setIssuedAt(now)
    .setSubject(options.sub ?? "access-sub-1234")
    .setIssuer(options.issuer ?? TEST_ISSUER)
    .setAudience(options.audience ?? TEST_AUDIENCE)
    .setExpirationTime(options.expiresAtSeconds ?? now + 300)
    .sign(privateKey);
}
