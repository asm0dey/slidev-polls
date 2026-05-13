# Tech Stack (pre-plan notes)

Informal record of stack decisions made before per-feature `plan.md` artifacts
exist. Authoritative tech choices still live in each feature's `plan.md`
(see `/iikit-02-plan`); this file is a pointer, not a substitute.

## Backend

- Spring (Java / Spring Boot)

## Slidev component

- Vue 3 + TypeScript (as a Slidev addon)

## Databases

Two engines are supported. **No Spring profiles** — the engine is selected by
`SPRING_DATASOURCE_URL` alone. Spring Boot's Flyway autoconfig substitutes the
`{vendor}` placeholder in `spring.flyway.locations` based on the JDBC URL, and
jOOQ's dialect is auto-detected from the same URL.

| Engine        | JDBC URL prefix       | When                                                            |
|---------------|-----------------------|-----------------------------------------------------------------|
| PostgreSQL 18 | `jdbc:postgresql://…` | Production, compose stacks, Testcontainers                      |
| H2 2.x (file) | `jdbc:h2:file:…`      | Single-binary deploys, demos, offline dev (`go-task dev:h2`)    |

Flyway uses vendor-scoped locations:
- `db/migration/postgresql` — V1–V9 history (PG only).
- `db/migration/h2`         — V1 baseline (mirrors post-V9 state in H2 DDL).
- `db/migration/common`     — every future migration; both engines apply.

The runtime schema is portable: `text[]` was replaced by the `poll_allowed_origins` child table; the partial unique on `poll_questions(status='ACTIVE')` became a generated `active_poll_id` column with a NULL gap; the functional unique on `lower(slug)` became a generated `slug_lower` column. jOOQ codegen still runs against PostgreSQL — the generated types are identical for both engines.

> **Security note:** `spring.h2.console.enabled` is left at its default (false). Never enable it — `/h2-console` would expose a SQL shell that bypasses every other auth surface.
