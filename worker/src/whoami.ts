/**
 * M0 auth-spike stub — see docs/sync-protocol-v1.md, "Milestone M0".
 *
 * Validates the Cloudflare Access JWT from the Cf-Access-Jwt-Assertion header
 * (the pattern documented by Cloudflare) and echoes the identity. Storage (M3)
 * and production auth wiring are intentionally absent.
 */

interface Env {
  /** Zero Trust team domain, e.g. https://yeoux.cloudflareaccess.com */
  TEAM_DOMAIN: string;
  /** Application Audience (AUD) tag of the Access application. */
  POLICY_AUD: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Authorization, Content-Type",
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });

    // Service tokens are machine auth (CI/ops) and must never pass the user path
    // (docs/sync-protocol-v1.md). This rejection is live from the first deploy.
    if (
      request.headers.has("CF-Access-Client-Id") ||
      request.headers.has("CF-Access-Client-Secret")
    ) {
      return json({ ok: false, error: "service tokens are not accepted on the user path" }, 403, cors);
    }

    const token = request.headers.get("Cf-Access-Jwt-Assertion");
    if (!token) {
      return json(
        {
          error: "missing Cf-Access-Jwt-Assertion",
          hint: "open multiprompt.dev/sync/whoami behind Access, or run the M0 spike flow",
          policyAudConfigured: Boolean(env.POLICY_AUD),
        },
        403,
        cors,
      );
    }

    if (!env.POLICY_AUD) {
      // POLICY_AUD is set once the Access application exists; validation cannot run
      // without the audience tag, so no token is trusted before then.
      return json(
        {
          error: "waiting for configuration",
          hint: "POLICY_AUD is set after the Access application is created (docs/sync-protocol-v1.md, M0). No token is trusted until then.",
        },
        503,
        cors,
      );
    }

    const { jwtVerify, createRemoteJWKSet } = await import("jose");
    try {
      const JWKS = createRemoteJWKSet(new URL(`${env.TEAM_DOMAIN}/cdn-cgi/access/certs`));
      const { payload } = await jwtVerify(token, JWKS, {
        issuer: env.TEAM_DOMAIN,
        audience: env.POLICY_AUD,
      });
      return json(
        {
          ok: true,
          email: payload.email ?? null,
          userId: payload.sub,
          expiresIn: (payload as Record<string, number>).exp - Math.floor(Date.now() / 1000),
        },
        200,
        cors,
      );
    } catch (error) {
      return json({ ok: false, error: error instanceof Error ? error.message : "invalid token" }, 403, cors);
    }
  },
};

function json(body: unknown, status: number, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...extra },
  });
}
