import path from "node:path";
import { defineWorkersConfig, readD1Migrations } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  const migrations = await readD1Migrations(path.join(__dirname, "migrations"));
  return {
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          wrangler: { configPath: "./wrangler.sync.toml" },
          miniflare: {
            bindings: {
              TEST_MIGRATIONS: migrations,
              // Point JWT verification at a fixture issuer/audience instead of
              // the real Access app, so tests sign tokens with a local test
              // key (test/jwt-fixture.ts) and never touch the network.
              OIDC_ISSUER: "https://sync-test.invalid",
              OIDC_CLIENT_ID: "test-client-id",
            },
          },
        },
      },
    },
  };
});
