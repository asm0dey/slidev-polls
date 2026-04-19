# Phase 0 Research: Core Polling Platform

Resolves the technical unknowns that sit between the feature spec and a
concrete plan. Each entry records the decision, why it was chosen, and
the alternatives that were considered and rejected.

## 1. Live-update transport: SSE vs WebSocket vs polling

**Decision**: Server-Sent Events (SSE), one stream per poll.

**Rationale**:
- Traffic is strictly one-way (backend → viewer); WebSocket's
  bidirectional framing buys nothing and doubles surface area.
- SSE is plain HTTP; it traverses corporate proxies and conference
  Wi-Fi more reliably than WebSocket upgrades.
- Browser `EventSource` gives automatic reconnection with bounded
  backoff, directly supporting Principle IV and SC-006.
- Spring MVC has native `SseEmitter` support — no new dependency.

**Alternatives considered**:
- **WebSocket / STOMP**: rejected. Adds a second protocol and a
  broker-style abstraction for a one-way stream.
- **HTTP long-polling**: rejected. Worse tail latency than SSE, harder
  cancellation on active-question change.
- **Client short-interval polling**: rejected. Wastes bandwidth, scales
  badly at SC-004's 200 concurrent clients, visibly jerky under SC-003.

## 2. Persistence: jOOQ vs JPA/Hibernate vs hand-rolled JDBC

**Decision**: **jOOQ** over PostgreSQL 16, with Flyway for migrations
and `testcontainers-jooq-codegen-maven-plugin` generating jOOQ classes
at build time from the live PostgreSQL dialect.

**Rationale**:
- The schema is small, relational, and queried with trivial joins and
  a single `GROUP BY` aggregate. None of JPA's strengths
  (relationship-graph navigation, lazy loading, cascading) are
  exercised; all of its costs (reflection, proxying, session and
  transaction subtleties, Hibernate version skew) would be paid.
- jOOQ produces type-safe SQL that mirrors the migrations, which
  catches schema drift at compile time rather than at first test run.
- Codegen against a real PostgreSQL container means the generated
  types encode PostgreSQL-specific column types (e.g., `jsonb` for
  `polls.style`) accurately — no annotation workarounds.
- Transitive footprint: jOOQ + JDBC is smaller than Hibernate + its
  ByteBuddy / Jandex / Antlr chain. Principle VIII.

**Alternatives considered**:
- **Spring Data JPA / Hibernate**: rejected for the reasons above. The
  value Hibernate adds over jOOQ on a schema this shape is negative.
- **Spring Data JDBC**: rejected. Still carries an aggregate-mapping
  model with its own learning tax; jOOQ's SQL-first model is a closer
  match to how the queries will actually be written.
- **Hand-rolled JDBC / `JdbcClient`**: rejected. Loses compile-time
  checking of column names and types against migrations — exactly the
  property that makes jOOQ worth the codegen step.

## 3. Java 25 + Spring Boot line

**Decision**: Java 25 (LTS) on Spring Boot 3.4.x.

**Rationale**:
- Java 25 is the September 2025 LTS release and is in active support
  as of 2026-04-19; it is the right default for a new service with a
  multi-year horizon.
- Spring Boot 3.4 is the first Spring Boot line with full Java 25
  runtime certification and still carries the framework 6.x baseline
  the team is familiar with.
- Virtual threads (`spring.threads.virtual.enabled`) are a clean fit
  for the SSE endpoint's per-subscriber hold-open thread shape,
  keeping SC-004's 200 concurrent subscribers cheap to hold.

**Alternatives considered**:
- **Java 21 LTS**: rejected as the default; it works, but starting a
  new service on the older LTS the day after the newer LTS has
  shipped just defers the upgrade.
- **Spring Boot 3.3**: rejected. Certified against JDK 21, not JDK 25.

## 4. Build tooling: Maven multi-module vs Gradle vs single module

**Decision**: **Maven** multi-module reactor with four modules:
`poll-core`, `poll-persistence`, `poll-realtime`, `poll-api`.

**Rationale**:
- Separating `poll-core` from web and JDBC dependencies lets the
  domain be tested without booting Spring or talking to a database —
  directly serving Principle III (cheap, fast tests where the cost
  would otherwise silently accumulate).
- `poll-persistence` is the only module that owns Flyway migrations
  and jOOQ codegen, so codegen artefacts live exactly where the
  schema they reflect does.
- `poll-realtime` is separate because the SSE hub's concurrency
  semantics (subscribe/unsubscribe/broadcast under races) are worth
  unit-testing without the REST layer on top.
- Maven is chosen over Gradle deliberately: the build needs are
  plain (reactor + plugin), Maven's declarative pom matches the
  "human-authored, legible repo" stance of Principle IX, and the
  project has no build-performance problem that would justify the
  Gradle/Groovy/Kotlin DSL surface area.

**Alternatives considered**:
- **Single-module Maven**: rejected. Would force `poll-core` tests to
  carry Spring Web and JDBC on the classpath.
- **Gradle multi-module**: rejected on Principle V — no present
  requirement Maven does not meet.

## 5. Frontend toolchain: bun + Vite workspace

**Decision**: **bun** as the single frontend toolchain (install, run,
lockfile) in a workspace rooted at `frontends/`, with **Vite** as the
bundler for each SPA.

**Rationale**:
- bun's workspace support with one lockfile covers the four-package
  layout (`shared`, `voter`, `backoffice`, `slidev-component`) with
  no extra glue.
- Treating bun as the only installed-runtime expectation simplifies
  developer onboarding (no `nvm` + pnpm matrix) and compresses CI.
- Vite stays as the bundler because the Slidev addon package is
  consumed by Slidev's Vite runtime; keeping both the addon and the
  SPAs on Vite avoids a second build tool.

**Alternatives considered**:
- **pnpm workspaces**: rejected. Functionally similar for our needs,
  but adds a second CLI / lockfile / runtime to the developer
  environment for no present advantage.
- **Turborepo / Nx**: rejected on Principle V. Four packages do not
  justify a task runner.

## 6. Backoffice authentication

**Decision**: Spring Security form login backed by an `admin_user`
table with BCrypt-hashed passwords. Session cookies are `HttpOnly`,
`Secure`, `SameSite=Lax`. No self-serve sign-up in this feature;
users are seeded by a Flyway migration and/or a local admin CLI
(out of scope for v1 UI).

**Rationale**:
- FR-001, FR-016, and SC-005 require every backoffice route is behind
  auth. Spring Security's filter chain is the smallest code path to
  get there.
- Session cookies are simpler than a JWT for a single first-party
  backoffice on one origin; they also avoid any cross-origin
  discussion that the single-origin deployment already sidesteps.

**Alternatives considered**:
- **JWT bearer tokens**: rejected for v1. No cross-origin or
  multi-client need.
- **OAuth / social login**: rejected. Adds a per-deploy IdP
  configuration for zero present value — the presenter population is
  small and known.

## 7. Respondent identity and single-vote enforcement

**Decision**: A `voter_token` (UUID) that lives in the voter SPA's
`localStorage` and is sent in a first-party cookie for server-side
uniqueness. The database enforces
`UNIQUE (question_id, voter_token)`. Duplicate submissions are
surfaced as `ALREADY_VOTED` (a 409 Problem), not a server error.

**Rationale**:
- FR-009 is best-effort single-vote per device per question.
  A client-generated UUID persisted across reloads is the simplest
  mechanism that does not require PII (FR-011, SC-007).
- DB-level uniqueness prevents double-counting under concurrent
  double-tap on mobile.
- `localStorage` and the cookie hold the same token; the cookie is
  the authoritative server-visible signal, `localStorage` is a UX
  hint that lets the voter SPA render "you already voted" without a
  round trip.

**Alternatives considered**:
- **IP-based enforcement**: rejected. Conference NAT = one IP for
  many legitimate respondents.
- **LocalStorage only, no cookie**: rejected. Server cannot enforce
  uniqueness without the token on the wire; trivially bypassed if
  relied on alone.

## 8. Memorable slug addressing

**Decision**: Each poll has a human-memorable **slug** (e.g.,
`my-poll`) that forms its public URL `/{slug}`. Slugs are generated
from the poll title at creation time, validated, and editable by the
owning presenter. Reserved slugs cannot be allocated.

**Rationale**:
- The premise is "memorable join by link"; an opaque join code is a
  fallback, not the primary affordance on a conference slide.
  A slug is more memorable than `j/AB7K9Q` and easier to read out
  loud.
- Serving `/{slug}` on the same origin as `/api/*` and `/admin/*`
  forces an explicit reserved-word list, which is better than letting
  user content collide with routes.

**Slug rules (enforced in `poll-core/SlugValidator` and mirrored in
`backoffice/SlugField`)**:
- Charset: `^[a-z0-9]+(-[a-z0-9]+)*$`.
- Length: 3–40 characters.
- Case-insensitive unique across live polls; stored lowercase.
- Reserved list: `admin`, `api`, `assets`, `static`, `j`, `login`,
  `logout`, plus the empty string (the site root reserved for the
  voter SPA landing page).
- Editable by the owning presenter. Rotating a slug invalidates the
  old URL immediately; no redirect is issued in v1 (out of scope).

**Alternatives considered**:
- **Opaque `joinCode` only** (previous plan revision): rejected —
  less memorable, works against the premise's "following a join
  link or code" ergonomics.
- **Short-code + optional slug alias**: rejected for v1. Two URL
  shapes for the same resource is an extra surface; revisit if
  presenters ask.

## 9. QR code generation

**Decision**: ZXing (`com.google.zxing:core` + `javase`) inside
`poll-api` to produce a PNG for the poll's public `/{slug}` URL on
demand, cached in-process per poll for the lifetime of the JVM.

**Rationale**:
- FR-005 requires a QR; ZXing is the canonical Java library, small,
  no transitive surprises.
- In-process cache keyed by the slug is sufficient — no distributed
  cache, no CDN (Principle V).
- Slug rotation cleanly invalidates the cache entry.

**Alternatives considered**:
- **Client-side QR in the backoffice**: rejected. The QR is also used
  in-deck on a Slidev slide; one server implementation keeps the
  output consistent.
- **External QR API**: rejected on Principle VIII — a network
  dependency and a privacy footprint for three lines of library code.

## 10. Single-origin deployment

**Decision**: The backoffice SPA (`/admin/`) and the voter SPA (`/`
and `/{slug}`) are served as static resources from `poll-api`. The
catch-all that forwards SPA routes to `index.html` explicitly excludes
`/api/**`, `/admin/api/**`, and static-asset prefixes.

**Rationale**:
- One origin removes any CORS configuration, any separate web tier,
  and any discussion of where the session cookie lives (Principle V).
- It also makes "what is the poll URL printed on the QR code?" a
  single-environment-variable question.

## Tessl Tiles

No Tessl tiles are currently installed for Java / Spring Boot / jOOQ /
Vue 3 / Slidev / bun beyond the `intent-integrity-kit` workflow tile.
No eval scores are available. If tiles for these technologies become
available in the workspace's registry, they should be evaluated here
and the relevant eval scores written into
`.specify/context.json::planview.evalScores`.
