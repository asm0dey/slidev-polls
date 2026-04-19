# Implementation Plan: Core Polling Platform

**Branch**: `001-polling-foundation` | **Date**: 2026-04-19 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-polling-foundation/spec.md`

## Summary

Deliver the first working end-to-end loop of Slidev Polls: an authenticated
presenter backoffice that authors polls and controls the active question;
an anonymous, mobile-first respondent page reached via a stable join link
and QR code; and a Slidev-embeddable results view that updates live. The
implementation is a three-surface monorepo: a Spring Boot backend exposing
a small REST API plus a Server-Sent Events stream for live aggregates, a
Vue 3 + TypeScript respondent web app, and a Vue 3 + TypeScript Slidev
addon that renders the same live aggregates on-slide.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.x on Node 20 LTS
(respondent app, Slidev addon, shared tooling).
**Primary Dependencies**:
- Backend: Spring Boot 3.3 (web, security, validation), Spring Data JPA,
  Flyway for schema migrations, ZXing for QR code generation.
- Respondent app: Vue 3, Vite, vue-router. No UI framework; hand-written
  CSS. Native `EventSource` for SSE.
- Slidev addon: Vue 3 + TypeScript, packaged as a Slidev addon per the
  Slidev addon contract. Re-uses the same SSE client and aggregate
  rendering logic as the respondent app via a shared `packages/shared`
  workspace.
**Storage**: PostgreSQL 16 in production; an embedded H2 (PostgreSQL
compatibility mode) profile is permitted only for local dev and fast
unit runs. Integration tests MUST run against PostgreSQL via
Testcontainers.
**Testing**:
- Backend: JUnit 5 + Spring Boot Test (slice tests for web and data
  layers, a full `@SpringBootTest` for end-to-end API flows),
  Testcontainers for PostgreSQL, MockMvc for HTTP, Awaitility for
  SSE-timing assertions. No BDD runner (Principle VII).
- Frontend (respondent app + Slidev addon + shared package): Vitest for
  unit and component tests, Playwright for a small end-to-end smoke
  suite that covers the respondent vote path and the Slidev results
  view updating over SSE.
**Target Platform**:
- Backend: Linux server (containerised), JDK 21.
- Respondent app: evergreen mobile and desktop browsers (last 2 majors
  of Chrome, Safari, Firefox, Edge). No IE / legacy WebView support.
- Slidev addon: runs inside Slidev's Vite + Vue 3 runtime on the
  presenter's machine / browser during a talk.
**Project Type**: Monorepo with one backend service and an npm/pnpm
workspace covering the respondent app, the Slidev addon, and a shared
TypeScript package for poll/response types and the SSE client.
**Performance Goals**:
- Join-link TTFB under 500 ms p95 so that SC-001 (<3 s to first view of
  the active question on mobile) holds with network overhead absorbed.
- Vote submission round-trip under 800 ms p95 so SC-002 (<5 s end-to-end)
  holds with UI feedback budget.
- SSE broadcast latency from "response accepted by backend" to "delivered
  to connected clients" under 500 ms p95 so SC-003 (<2 s reflected on
  slide) holds.
- Sustain at least 200 concurrent SSE subscribers per active question
  (SC-004) on a single backend instance.
**Constraints**:
- Respondent path MUST require zero authentication and zero PII
  (FR-007, FR-011, SC-007).
- All backoffice endpoints MUST 401/redirect unauthenticated callers
  (FR-001, FR-016, SC-005).
- Live-update loss MUST NOT break the Slidev deck (FR-015, SC-006);
  connection failures degrade to a visible "live updates paused" badge
  and an auto-reconnect with bounded backoff.
- "At most one active question per poll" MUST be enforced transactionally
  at the storage layer, not only in the service layer (FR-004).
**Scale/Scope**:
- One backend instance sized for ~200 concurrent respondents per active
  question across up to ~50 concurrently open polls — well inside a
  single JVM's footprint.
- Expected dataset for v1: low thousands of polls, tens of thousands of
  responses per poll in the worst case (a viral conference talk); not
  designed for analytics-scale query loads.

## Constitution Check

| Principle | Gate | Result |
|-----------|------|--------|
| I. Markdown-First Authoring | Slidev addon exposes a Vue component usable directly inside slide markdown; no external dashboard required for the presenter to render live results on a slide. | Pass |
| II. Respondent Zero-Friction | Respondent app is a public SPA reachable by join link / QR with no auth, no account, no install; only a device-scoped session identifier (not PII) is set. | Pass |
| III. Test-First (NON-NEGOTIABLE) | TDD is enforced via `/iikit-04-testify` producing `.feature` specs and the constitution's pre-commit assertion-integrity hook; test tasks precede implementation tasks in `/iikit-05-tasks`. | Pass |
| IV. Live-Reliability Over Feature Depth | Slidev addon handles SSE disconnection with a visible paused badge and auto-reconnect; the backoffice and respondent pages degrade to explicit error states rather than blank screens. | Pass |
| V. Simplicity and YAGNI | Single backend service, single database, single SSE channel per question. No message broker, no microservices split, no admin UI framework. | Pass |
| VI. Observability for Live Events | Structured JSON logs with a request correlation id on every request; distinct user-visible error codes for auth failure, authorisation failure, rejected-because-closed, and transport failure. | Pass |
| VII. No BDD Frameworks | JUnit 5 and Vitest only. Gherkin scenarios from `/iikit-04-testify` will be mirrored as comments above the corresponding unit/integration assertions. | Pass |
| VIII. Minimal External Dependencies | Every dependency listed above has a concrete present use. No UI framework, no ORM helpers on top of Spring Data JPA, no test frameworks beyond what the chosen stacks already imply. | Pass |
| IX. Human-Authored Presentation | Commits, comments, and generated artifacts MUST NOT contain AI-assistant attribution lines or co-author trailers. | Pass |

No violations — Complexity Tracking table is empty.

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
  checklists/          # /iikit-03-checklist output (already present)
```

### Source Code (repository root)

```text
backend/                               # Spring Boot service
  src/main/java/dev/slidevpolls/
    poll/                              # polls, questions, options, responses
      api/                             # REST controllers + DTOs
      domain/                          # JPA entities + domain services
      events/                          # SSE broadcaster + event types
    auth/                              # Spring Security config for backoffice
    platform/
      qr/                              # QR code generation adapter
      logging/                         # structured-logging config
  src/main/resources/
    db/migration/                      # Flyway SQL migrations
    application.yml
  src/test/java/dev/slidevpolls/
    poll/
    auth/
    e2e/                               # full @SpringBootTest flows

frontend/
  packages/
    shared/                            # poll/response types, SSE client
      src/
      tests/
    respondent-app/                    # anonymous voter SPA
      src/
        pages/                         # join page, waiting state, vote page
        components/
        services/
      tests/
      e2e/                             # Playwright respondent flow
    slidev-addon/                      # Slidev addon exposing <PollResults/>
      components/
      setup/                           # Slidev addon setup hooks
      tests/
      e2e/                             # Playwright live-results flow
  package.json                         # pnpm workspace root
  pnpm-workspace.yaml

examples/
  demo-deck/                           # a Slidev deck consuming the addon
                                       # used as a manual-smoke surface and
                                       # as the fixture for the Playwright
                                       # end-to-end suite
```

**Structure Decision**: Two top-level directories, `backend/` and
`frontend/`, matching the "web application" layout. The frontend is a
pnpm workspace with three packages (`shared`, `respondent-app`,
`slidev-addon`) so that the SSE client, DTO types, and result-rendering
logic are authored once and consumed by both the public respondent page
and the Slidev addon — this directly serves Principle V (one source of
truth) and Principle IV (identical reconnect behaviour in both surfaces).
`examples/demo-deck` is a real Slidev deck used as both a manual-smoke
target and the Playwright fixture; it is not a shipped artifact.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations; table intentionally empty.
