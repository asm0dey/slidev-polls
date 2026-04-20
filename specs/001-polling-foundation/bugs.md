# Bug Reports: 001-polling-foundation

## BUG-001

**Reported**: 2026-04-20
**Severity**: critical
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Backend fails to start under `compose.dev.yml` because Flyway migration V3 cannot resolve `gen_salt('bf', 10)`.

**Reproduction Steps**:
1. From the repo root, run `docker compose -f compose.dev.yml up -d` (or `task up`).
2. Wait for `slidev-polls-postgres` to report healthy and `slidev-polls-backend` to start.
3. Tail `docker compose -f compose.dev.yml logs backend`.
4. Observe the container exits after Flyway logs `Migration of schema "public" to version "3 - admin user" failed! Changes successfully rolled back.` with root cause `ERROR: function gen_salt(unknown, integer) does not exist` at `db/migration/V3__admin_user.sql` line 25. Spring then fails to start the `deckTokenAuthenticationFilter` bean because the datasource initialization aborted, and the JVM exits non-zero.

**Root Cause**: `V3__admin_user.sql` ran `CREATE EXTENSION IF NOT EXISTS pgcrypto;` followed immediately by `INSERT ... crypt('correct-horse', gen_salt('bf', 10))`. In some Postgres 16 environments Flyway's migration session could not resolve `gen_salt(unknown, integer)` even after the extension create had logged success, which aborted V3 and left the application context unable to initialize the DeckTokenAuthenticationFilter chain. The dependency on a contrib extension being resolvable in the migration's session made V3 fragile across environments.

**Fix Reference**: T-B001 in `tasks.md`. Dropped the `CREATE EXTENSION pgcrypto` + `crypt(..., gen_salt(...))` dance in `V3__admin_user.sql` and seeded alice with a precomputed BCrypt hash (`$2a$10$9CvgAtcz7/XAUwDSz/7Uuu7K85lbQAGe8EqYH8Wvh4auT.ZO1Siai`). Spring Security's `BCryptPasswordEncoder` validates any `$2a$` hash regardless of per-install salt, so the seeded-login contract is unchanged and the migration no longer depends on pgcrypto being resolvable at Flyway time.
