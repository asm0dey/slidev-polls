# Tech Stack (pre-plan notes)

Informal record of stack decisions made before per-feature `plan.md` artifacts
exist. Authoritative tech choices still live in each feature's `plan.md`
(see `/iikit-02-plan`); this file is a pointer, not a substitute.

## Backend

- Quarkus 3.15.7 (Java 21 bytecode, built with JDK 25). Ships as a JVM jar
  (`target/quarkus-app`) and as a GraalVM/Mandrel native binary
  (`target/*-runner`). jOOQ for type-safe SQL, Flyway for migrations,
  Server-Sent Events for live tally fan-out.

## Slidev component

- Vue 3 + TypeScript (as a Slidev addon)

## Databases

Two engines are supported, both compiled into the artifact (including the
native image). The active engine is selected at runtime by
`app.database.vendor` (env `APP_DATABASE_VENDOR`, default `postgres`). Each
engine has its own Quarkus named datasource (`postgres` / `h2`);
`FlywayMigrator` runs the active vendor's migrations on `StartupEvent`, and
jOOQ's dialect follows the active datasource.

| Engine        | Vendor flag           | When                                                            |
|---------------|-----------------------|-----------------------------------------------------------------|
| PostgreSQL 18 | `postgres` (default)  | Production, compose stacks, Testcontainers / Dev Services       |
| H2 2.x (file) | `h2`                  | Single-binary deploys, demos, offline dev (`go-task dev:h2`)    |

Flyway uses vendor-scoped locations:
- `db/migration/postgresql` — V1–V9 history (PG only).
- `db/migration/h2`         — V1 baseline (mirrors post-V9 state in H2 DDL).
- `db/migration/common`     — every future migration; both engines apply.

The runtime schema is portable: `text[]` was replaced by the `poll_allowed_origins` child table; the partial unique on `poll_questions(status='ACTIVE')` became a generated `active_poll_id` column with a NULL gap; the functional unique on `lower(slug)` became a generated `slug_lower` column. jOOQ codegen still runs against PostgreSQL — the generated types are identical for both engines.
