# Phase 0 Research: Core Polling Platform

Resolves the technical unknowns that sit between the feature spec and a
concrete plan. Each entry records the decision, why it was chosen, and
the alternatives that were considered and rejected.

## 1. Live-update transport: SSE vs WebSocket vs polling

**Decision**: Server-Sent Events (SSE), one stream per `(pollId,
questionId)` subscription.

**Rationale**:
- The live-update traffic is strictly one-way: the backend pushes
  aggregate tallies to the Slidev view. WebSockets' bidirectional
  framing buys nothing here and doubles the surface area.
- SSE is a plain HTTP response; it traverses corporate proxies and
  conference Wi-Fi more reliably than WebSockets and needs no extra
  upgrade handshake.
- The browser `EventSource` API handles automatic reconnection with a
  bounded backoff out of the box, which directly supports Principle IV
  (Live-Reliability) and SC-006 (auto-recovery within 10 s).
- Spring MVC supports SSE natively through `SseEmitter`; no extra
  dependency is needed (serves Principle VIII).

**Alternatives considered**:
- **WebSocket (e.g., Spring's STOMP over SockJS)**: rejected. Adds a
  second protocol, requires a message-broker abstraction to reach
  interesting semantics, and the traffic pattern is one-way anyway.
- **HTTP long-polling**: rejected. Worse latency than SSE under the same
  network conditions and harder to cancel cleanly when the active
  question changes.
- **Client-side short-interval polling (e.g., every 1 s)**: rejected.
  Wastes bandwidth, scales badly at SC-004 (200 concurrent respondents
  implies at least as many viewers) and produces a visibly jerky
  update cadence that would fail SC-003 at the tail.

## 2. Storage: PostgreSQL vs lighter alternatives

**Decision**: PostgreSQL 16 in production, with Testcontainers for
integration tests and an H2 PostgreSQL-compatibility profile for fast
unit-test runs.

**Rationale**:
- FR-004 requires atomic transitions of the "active question" pointer
  per poll; a relational database with row-level locking and a
  `CHECK` / partial-unique constraint is the simplest enforcement.
- The read pattern for aggregates is a trivial `GROUP BY option_id`
  keyed by question — trivially indexed in SQL.
- Flyway for migrations is the de-facto Spring Boot default and adds
  essentially zero new dependency weight (Spring Boot already pulls it
  on the classpath via its starter).

**Alternatives considered**:
- **Redis or an in-memory store**: rejected for authoritative storage;
  durability of the presenter's authored polls and collected responses
  is required, and adding Redis *in addition to* a database would
  violate Principle VIII without a present requirement.
- **SQLite**: rejected. Fine for local dev, but concurrent writes
  under SC-004's 200-respondents load on a single question are exactly
  SQLite's weak spot.
- **NoSQL (MongoDB, DynamoDB)**: rejected. The schema is small,
  relational, and benefits from transactional constraints; a document
  store would push the "one active question" invariant into
  application code, which Principle III (TDD) does not make easier to
  keep correct.

## 3. Backoffice authentication

**Decision**: Spring Security form login backed by a `Presenter` table
with BCrypt-hashed passwords. Session cookies are `HttpOnly`, `Secure`,
`SameSite=Lax`. No self-serve sign-up in this feature; presenters are
seeded by an admin-only migration / admin CLI (out of scope for v1
UI).

**Rationale**:
- FR-001, FR-016, and SC-005 require that every backoffice route is
  behind auth; Spring Security's built-in filter chain is the smallest
  amount of code that gets us there.
- Session cookies are simpler than a JWT scheme for a single first-party
  backoffice and avoid a token-rotation story that YAGNI does not yet
  justify.
- Presenter creation is intentionally not exposed as a UI in this
  feature; the spec does not require it and adding sign-up surfaces
  would violate Principle V.

**Alternatives considered**:
- **JWT bearer tokens**: rejected for v1. No present cross-origin or
  multi-client requirement.
- **OAuth / social login**: rejected. Adds an external dependency and a
  per-deploy identity-provider configuration for zero added value given
  the presenter population is small and known.
- **No auth, ambient trust**: rejected — violates Principle II's
  inverse on the presenter side (backoffice is not zero-friction; it
  is explicitly privileged).

## 4. Respondent session / single-vote enforcement

**Decision**: A short, opaque, device-scoped cookie (`sp_device`) set on
first visit to the respondent app. Uniqueness of `(questionId,
deviceSessionId)` is enforced at the storage layer with a unique
constraint. Attempts to vote twice return a well-formed "already voted"
response rather than an error.

**Rationale**:
- FR-009 requires best-effort single vote per device session per
  question; a cookie-scoped identifier is the simplest implementation
  and carries no PII (FR-011, SC-007).
- Enforcement at the DB level prevents double-counting under concurrent
  submissions from the same device, which is a real-world case when
  eager respondents double-tap on mobile.

**Alternatives considered**:
- **IP-based single-vote enforcement**: rejected. Conference NAT means
  many legitimate respondents share one IP.
- **LocalStorage-only**: rejected as the *only* mechanism. It is
  trivially bypassed and it is cleared unpredictably on iOS Safari; a
  cookie is both more reliable and server-visible for enforcement.
  LocalStorage is used as a UX hint ("you already voted") but not as
  the authoritative check.
- **No enforcement (audience self-moderates)**: rejected — violates the
  spirit of FR-009 and trivially corrupts small-audience demos.

## 5. QR code generation

**Decision**: ZXing (`com.google.zxing:core` + `javase`) invoked inline
from the backend to produce a PNG for a given join URL on demand,
cached per-poll in-process for the lifetime of the JVM.

**Rationale**:
- FR-005 requires a QR code that resolves to the join link; ZXing is
  the canonical Java library, small, and has no transitive surprises.
- Generation is cheap; in-process caching per poll keyed by the join
  URL is sufficient — no need for a distributed cache or a CDN in v1
  (Principle V).

**Alternatives considered**:
- **Client-side QR rendering (e.g., a JS library in the backoffice)**:
  rejected. The QR also appears on the presenter's Slidev deck itself
  (a natural future use); centralising generation on the backend keeps
  the output consistent and lets both surfaces reuse the same URL.
- **An external QR API**: rejected on Principle VIII — a network
  dependency and a privacy footprint for a feature that is three lines
  of library code.

## 6. Monorepo / frontend workspace

**Decision**: pnpm workspaces with three packages — `shared`,
`respondent-app`, `slidev-addon` — all Vue 3 + TypeScript under Vite.

**Rationale**:
- The respondent app and the Slidev addon both render the same
  aggregate-tally view and both consume the same SSE stream. A shared
  package is the honest way to express that, rather than duplicating
  the code or forcing an import across unrelated package roots.
- pnpm's workspace protocol gives us `workspace:*` dependencies
  without publishing any package to a registry — a clean fit for an
  internal monorepo.

**Alternatives considered**:
- **Three unrelated projects with copy-pasted SSE client**: rejected —
  directly invites the "my respondent app reconnects but the slide
  freezes" class of bug that Principle IV exists to prevent.
- **Single app that also acts as a Slidev addon**: rejected. The
  Slidev addon's public surface (a component registered through the
  addon hook) is different from the respondent app's public surface (a
  routed page bundle); collapsing them would couple release cadences
  unnaturally.

## Tessl Tiles

No Tessl tiles are currently installed for the technologies listed in
`plan.md` beyond the `intent-integrity-kit` workflow tile already in
use. No eval scores are available. If, in a later feature, tiles for
Spring Boot 3.3 or the Slidev addon framework become available in the
workspace's registry, they should be evaluated and recorded here with
their eval scores and the technology they cover.
