# Implementation Plan: Core Polling Platform

**Branch**: `001-polling-foundation` | **Date**: 2026-04-19 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-polling-foundation/spec.md`

## Summary

Deliver the first working end-to-end loop of Slidev Polls on a single
origin, single backend process:

- a Spring Boot service on Java 25 exposes a small REST API plus a
  Server-Sent Events stream and serves the two built SPAs as its own
  static resources;
- a Vue 3 + TypeScript **voter** SPA, reached by a human-memorable slug
  URL (`/{slug}`) with no authentication, no account, and no install;
- a Vue 3 + TypeScript **backoffice** SPA under `/admin/` for
  authenticated presenters to author polls and control the active
  question, including assigning and editing the slug;
- a Vue 3 + TypeScript **Slidev addon** that renders the same live
  aggregates on-slide and, when mounted on a slide that declares a
  specific question, automatically asks the backend to activate that
  question (FR-018) using a presenter-minted **deck token** (FR-019).

Persistence is PostgreSQL 16 through jOOQ, with Flyway-managed
migrations and codegen run at build time against a Testcontainers
PostgreSQL instance. The backend is a Maven multi-module reactor
(`poll-core`, `poll-persistence`, `poll-realtime`, `poll-api`) so that
the domain is decoupled from the web layer and testable without
starting Spring Web. The frontend is a bun workspace
(`shared`, `voter`, `backoffice`, `slidev-component`); the two SPAs
are built and copied into `poll-api`'s `src/main/resources/static` so
production runs as one JAR.

## Technical Context

**Language/Version**:
- Backend: Java 25 (LTS), Spring Boot 4.0.5 (Spring Framework 7,
  Jakarta 11). Project bootstrapped via start.spring.io; reactor is a
  hand-authored Maven multi-module layout on top of the generated
  `poll-api` module. Build tool: Maven (wrapper checked in).
- Frontend: TypeScript 5.x. Toolchain: **bun** (install, run, test,
  lockfile). Bundler: Vite. No Node-on-path requirement for
  developers; bun is the single runtime for the frontend workspace.

**Primary Dependencies**:
- Backend (per module):
  - `poll-core`: `spring-context`, `jakarta.validation-api`. No web, no
    persistence — pure domain, pure unit-testable.
  - `poll-persistence`: `jooq`, `jooq-codegen` via
    `testcontainers-jooq-codegen-maven-plugin` (codegen runs against a
    Testcontainers PostgreSQL, so generated classes match the real
    dialect; no live DB required at build), `flyway-core`,
    `postgresql` JDBC, HikariCP (transitive via Spring Boot).
  - `poll-realtime`: `spring-web` (only for `SseEmitter`), `spring-context`.
  - `poll-api`: `spring-boot-starter-web`, `spring-boot-starter-security`,
    `spring-boot-starter-validation`, `flyway-core`,
    `zxing-core` + `zxing-javase` for QR PNG generation.
- Frontend:
  - `shared`: no runtime dependencies beyond the DOM (`EventSource`,
    `fetch`).
  - `voter`, `backoffice`, `slidev-component`: Vue 3, vue-router, Vite.
    No UI framework; hand-written CSS. The `slidev-component` package
    additionally declares the Slidev addon metadata in its
    `package.json` so it is consumable as `slidev-addon-polls`.

**Storage**: PostgreSQL 16 in production and in every integration
test (via Testcontainers). No H2 / no in-memory fallback. Schema is
owned by Flyway migrations in `poll-persistence`; jOOQ code is
generated from those migrations at build time.

**Testing**:
- Backend: JUnit 5 across all four modules.
  - `poll-core`: plain unit tests, no Spring context.
  - `poll-persistence`: JUnit 5 + Testcontainers PostgreSQL; an
    `AbstractPostgresTest` base class starts one shared container per
    JVM and runs Flyway migrations against it before each test class.
  - `poll-realtime`: plain unit tests for the SSE hub's
    concurrency semantics (subscribe / unsubscribe / broadcast under
    racing threads); a lightweight `@WebMvcTest`-level check that a
    `VoteCastEvent` produces a `tally` delivery.
  - `poll-api`: `@SpringBootTest` with MockMvc for the REST surface,
    an `@WebMvcTest` slice for Spring Security rules, Testcontainers
    PostgreSQL for full vertical flows, Awaitility for SSE timing.
  - No BDD runner anywhere (Principle VII). Gherkin scenarios from
    `/iikit-04-testify` are mirrored as comments above the
    corresponding JUnit assertions.
- Frontend: Vitest (run via `bun test` when convenient, via Vitest
  directly where DOM test APIs are needed) for unit and component
  tests; a small Playwright smoke suite that drives the voter flow
  and the Slidev results view against a running backend.

**Target Platform**:
- Backend: Linux server, JDK 25, containerisable. Runs as a single JAR
  produced by `poll-api`.
- Voter SPA and backoffice SPA: evergreen Chrome, Safari, Firefox,
  Edge (last 2 majors). Mobile Safari and mobile Chrome on current
  iOS / Android are explicit targets for the voter path.
- Slidev addon: Slidev's Vite + Vue 3 runtime on the presenter's
  machine.

**Project Type**: Monorepo with a **Maven multi-module backend** and a
separate **bun workspace frontend**. Built artefacts of the two SPAs
are copied into `poll-api/src/main/resources/static` so production
runs as one JAR on one origin — no CORS, no separate web tier.

**Performance Goals**:
- Join-link (`/{slug}`) TTFB under 500 ms p95 so that SC-001 (<3 s to
  first view of the active question on mobile) holds after network
  overhead.
- Vote submission round-trip under 800 ms p95 so SC-002 (<5 s
  end-to-end) holds with UI feedback budget.
- SSE broadcast latency from "response accepted by backend" to
  "delivered to connected clients" under 500 ms p95 so SC-003 (<2 s
  reflected on slide) holds.
- Sustain at least 200 concurrent SSE subscribers per active question
  (SC-004) on a single backend instance.

**Constraints**:
- **Single origin, single process**: both SPAs and the API MUST be
  served by the same Spring Boot process under one host. The voter
  SPA lives at the site root (`/`) and at `/{slug}`; the backoffice
  SPA lives at `/admin/`. Any catch-all forwarding to a SPA
  `index.html` MUST exclude `/api/**`, `/admin/api/**`, and static
  asset prefixes so route collisions cannot swallow the API.
- **Reserved slugs**: `admin`, `api`, `assets`, `static`, `j`,
  `login`, `logout`, and the empty string are reserved and MUST NOT
  be allocatable as poll slugs. Enforced in `poll-core`'s
  `ReservedSlugs` and in the backoffice `SlugField` component as a
  UX hint; authoritative enforcement is on the server.
- **Slug format**: lowercase kebab-case, 3–40 chars, `[a-z0-9]` plus
  `-`, must start and end with alphanumeric, no consecutive `-`.
  Validated identically in `poll-core`'s `SlugValidator` and in the
  client's `SlugField`.
- Respondent path requires zero authentication and zero PII
  (FR-007, FR-011, SC-007).
- All backoffice endpoints and all `/admin/**` SPA routes MUST be
  gated on an authenticated session (FR-001, FR-016, SC-005).
- Live-update loss MUST NOT break the Slidev deck (FR-015, SC-006):
  visible "live updates paused" indicator plus auto-reconnect.
- "At most one active question per poll" (FR-004) MUST be enforced at
  the storage layer, not only in the service layer. Implementation
  is a partial unique index on `poll_questions(poll_id) WHERE status
  = 'ACTIVE'`.
- FR-009 single-vote enforcement is a unique constraint on
  `(question_id, voter_token)`.
- **Deck-driven activation (FR-018, FR-019)**: the Slidev addon's
  `<PollResults>` component accepts a `questionId` and a `deckToken`
  prop. On mount, if both are present and the question is not already
  ACTIVE on the poll, the addon issues
  `POST /api/deck/polls/{pollId}/activate` with the question id and
  the `X-Deck-Token` header. The call is idempotent: activating an
  already-active question is a no-op. Deck tokens are minted by the
  backoffice (`POST /api/admin/polls/{pollId}/deck-tokens`), scoped
  to a single poll, and revocable. They are NOT session cookies and
  do NOT grant access to any other backoffice resource.

**Scale/Scope**:
- One backend instance sized for ~200 concurrent respondents per
  active question across up to ~50 concurrently open polls — well
  inside a single JVM's footprint.
- Expected dataset for v1: low thousands of polls, tens of thousands
  of responses per poll in the worst case (a viral conference talk).

## Constitution Check

| Principle | Gate | Result |
|-----------|------|--------|
| I. Markdown-First Authoring | `slidev-component` exposes Vue components (`PollResults`, `PollBar`, `PollHeader`) usable directly inside slide markdown; no external dashboard required for the presenter to render live results on a slide. | Pass |
| II. Respondent Zero-Friction | Voter SPA is public; slug URL is memorable (`example.com/my-poll`) and carries no auth gate. Only a device-scoped `voter_token` (UUID in `localStorage`, mirrored to a cookie for server-side uniqueness) is stored. No PII. | Pass |
| III. Test-First (NON-NEGOTIABLE) | TDD enforced via `/iikit-04-testify` and the assertion-integrity pre-commit hook. Test tasks precede implementation tasks in `/iikit-05-tasks`. `poll-core` unit-testability is preserved by keeping Spring-web and JDBC out of that module. | Pass |
| IV. Live-Reliability Over Feature Depth | SSE client reconnects with bounded backoff and renders a paused badge; Slidev addon never throws out of a component; server-side `SseHub` survives individual emitter failures without propagating to the publisher. | Pass |
| V. Simplicity and YAGNI | One backend process, one database, one SSE channel per poll, one Slidev addon package, one lockfile (`bun.lockb`). Multi-module split is the minimum that keeps `poll-core` web-free — not an architectural flourish. No message broker, no separate front-end server, no container orchestration beyond `docker-compose.yml` for Postgres. | Pass |
| VI. Observability for Live Events | Structured JSON logs with a per-request `correlationId`. `GlobalExceptionHandler` maps exceptions to distinct `Problem.code` values: `AUTH_REQUIRED`, `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_FAILED`, `ALREADY_VOTED`, `QUESTION_NOT_ACTIVE`, `SLUG_TAKEN`, `SLUG_INVALID`, `SLUG_RESERVED`, `ACTIVATION_REJECTED`, `TRANSPORT_FAILURE`. | Pass |
| VII. No BDD Frameworks | JUnit 5 and Vitest only. Gherkin scenarios from `/iikit-04-testify` are mirrored as comments above the corresponding assertions. | Pass |
| VIII. Minimal External Dependencies | Every dependency listed above has a concrete present use. jOOQ replaces JPA/Hibernate (a smaller transitive footprint for a schema this size) and removes the reflection / entity-graph surface area. No UI framework, no additional Node tooling — bun covers install, test, run. | Pass |
| IX. Human-Authored Presentation | Commit messages, code comments, migration comments, and generated doc artefacts MUST NOT include AI-assistant attribution lines or co-author trailers. | Pass |

No violations. Complexity Tracking table is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-polling-foundation/
  plan.md              # This file
  research.md          # Phase 0 output
  data-model.md        # Phase 1 output
  quickstart.md        # Phase 1 output
  contracts/           # Phase 1 output (OpenAPI + SSE event schemas)
  tasks.md             # Phase 2 output (/iikit-05-tasks)
  checklists/          # /iikit-03-checklist output
```

### Source Code (repository root)

```text
slidev-polls/
├── pom.xml                              # parent: Java 25, Spring Boot 3.4.x, module list, plugin mgmt
├── README.md
├── .gitignore
├── .editorconfig
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw ; mvnw.cmd
├── docker-compose.yml                   # postgres:16 for local dev
├── scripts/
│   ├── build-frontends.sh               # bun install + build all, copy dist → backend static
│   └── dev.sh                           # postgres + spring-boot:run + bun dev servers
│
├── backend/
│   ├── pom.xml                          # aggregator: lists 4 modules
│   ├── poll-core/                       # domain + services; no spring-web, no JDBC
│   ├── poll-persistence/                # jOOQ repositories, Flyway migrations, mappers
│   ├── poll-realtime/                   # SseHub + TallyBroadcaster
│   └── poll-api/                        # Spring Boot entrypoint, controllers, DTOs, security,
│                                        # SPA static serving
│
└── frontends/                           # bun workspace root (package.json + bun.lockb)
    ├── shared/                          # @polls/shared — types, api-client, sse-client
    ├── voter/                           # @polls/voter — SPA served at '/'
    ├── backoffice/                      # @polls/backoffice — SPA served at '/admin/'
    └── slidev-component/                # @polls/slidev-addon — Slidev addon package
```

(The expanded internal tree — per-module `src/main/java` layouts, per-SPA
`src/pages` / `src/components` layouts, Flyway migration files, and the
endpoint map — is documented alongside this plan and referenced in
`/iikit-05-tasks` when tasks are generated.)

**Structure Decision**: Maven multi-module backend + bun-workspace
frontend, with the built SPAs packaged inside the backend JAR.
Rationale:

- Splitting the backend into `poll-core`, `poll-persistence`,
  `poll-realtime`, and `poll-api` keeps the domain pure-Java and
  test-cheap (Principle III, Principle V), without turning into
  microservices.
- A bun workspace with a dedicated `shared` package is the honest way
  to express that the voter SPA, the backoffice SPA, and the Slidev
  addon all speak the same DTOs and consume the same SSE stream —
  Principle IV relies on identical reconnect behaviour in every
  surface, and that's only tractable if the client logic is literally
  the same module.
- Serving both SPAs as static resources from `poll-api` removes a CORS
  story, removes a second deployable, and removes any discussion of
  which origin the SSE cookie lives on (Principle V).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations; table intentionally empty.
