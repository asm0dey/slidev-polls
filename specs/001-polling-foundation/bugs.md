# Bug Reports: 001-polling-foundation

## BUG-001

**Reported**: 2026-04-20
**Severity**: critical
**Status**: reported
**GitHub Issue**: _(none)_

**Description**: Backend fails to start under `compose.dev.yml` because Flyway migration V3 cannot resolve `gen_salt('bf', 10)`.

**Reproduction Steps**:
1. From the repo root, run `docker compose -f compose.dev.yml up -d` (or `task up`).
2. Wait for `slidev-polls-postgres` to report healthy and `slidev-polls-backend` to start.
3. Tail `docker compose -f compose.dev.yml logs backend`.
4. Observe the container exits after Flyway logs `Migration of schema "public" to version "3 - admin user" failed! Changes successfully rolled back.` with root cause `ERROR: function gen_salt(unknown, integer) does not exist` at `db/migration/V3__admin_user.sql` line 25. Spring then fails to start the `deckTokenAuthenticationFilter` bean because the datasource initialization aborted, and the JVM exits non-zero.

**Root Cause**: _(empty until investigation)_

**Fix Reference**: _(empty until implementation)_
