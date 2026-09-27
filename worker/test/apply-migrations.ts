import { applyD1Migrations, env } from "cloudflare:test";

// Applies migrations/0001_sync_entity.sql (read at config time by
// vitest.config.ts) to the isolated per-test-run D1 instance.
await applyD1Migrations(env.DB, (env as unknown as { TEST_MIGRATIONS: never }).TEST_MIGRATIONS);
