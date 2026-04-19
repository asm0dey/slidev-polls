# Specification Analysis Report

Feature: `001-polling-foundation` — Core Polling Platform
Generated: 2026-04-19
Artifacts analysed: spec.md, plan.md, tasks.md, tests/features/*.feature, CONSTITUTION.md

## Findings

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| F1 | Inconsistency | MEDIUM | plan.md:204 vs plan.md:42, tasks.md T002 | Project-structure comment says "Spring Boot 3.4.x" while Technical Context and T002 specify "Spring Boot 4.0.5". | Update the inline comment in plan.md's `pom.xml` tree to "Spring Boot 4.0.5" so the structure diagram matches Technical Context. |
| F2 | Coverage Gaps | LOW | tasks.md Story-to-task summary | Table totals: US2=20, US3=20, total=102. Actual: US2=19 (6 test + 13 impl — no T089), US3=19 (6 test + 13 impl — T103 removed per 2026-04-19 clarification), grand total=100. | Adjust the story-to-task summary to US2=19, US3=19, total=100 so the summary tracks the numbered task list. |
| F3 | Underspecification | LOW | spec.md Edge Cases ("QR scanned after session ended") | Edge case listed, but no FR defines "session ended" semantics and no `.feature` scenario exercises the behaviour. Closest coverage is TS-010 (active QR resolves to slug) and TS-021 (waiting state when no ACTIVE question), neither of which is the scanned-after-end path. | Either add a requirement + test for the respondent experience when the poll is CLOSED, or note in spec that this edge case reduces to the "no ACTIVE question" state already covered by TS-021. |
| F4 | Underspecification | LOW | spec.md Edge Cases ("presenter deletes poll while viewers on join page") | Edge case enumerated with no FR, no test spec, and no task. Data model cascades deletes of questions/votes, but the voter-SPA behaviour on a deleted poll is not pinned. | Either add a small requirement + test for the voter fetch after delete (expected: 404 with user-facing copy, no crash) or mark the edge case "out of scope for this feature" the way the multi-presenter edit case is. |

## Constitution Alignment

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Markdown-First Authoring | ALIGNED | `slidev-component` package exposes Vue components usable inline in slides; no external authoring surface required for live results. |
| II. Respondent Zero-Friction | ALIGNED | Public `/{slug}` route; device-scoped `voter_token`; no PII; no auth. T086 makes `sp_voter` server-authoritative and HttpOnly — consistent with FR-011/SC-007. |
| III. Test-First (NON-NEGOTIABLE) | ALIGNED | Per-story phases put test tasks (T04x, T07x, T10x) before implementation tasks; assertion-integrity pre-commit hook enforces. |
| IV. Live-Reliability Over Feature Depth | ALIGNED | SSE paused-indicator and bounded-backoff reconnect (T030, T031); addon never throws; TS-033/TS-034 assert deck stays usable on backend loss. |
| V. Simplicity and YAGNI | ALIGNED | Single process, single DB, single SSE channel per poll, single lockfile. Multi-module split is justified by `poll-core` purity. |
| VI. Observability for Live Events | ALIGNED | `CorrelationIdFilter` (T025); `Problem.code` set matches FR-017 distinction; GlobalExceptionHandler (T026) with coverage assertion (T027, TS-042). |
| VII. No BDD Frameworks | ALIGNED | JUnit 5 / Vitest / `bun test` only. No `tests/step_definitions/` directory; Gherkin treated as spec artefact per tasks.md Notes and plan.md Testing. |
| VIII. Minimal External Dependencies | ALIGNED | Each dependency has a concrete present use listed per module. No UI framework, no Node-on-path, no message broker. |
| IX. Human-Authored Presentation | ALIGNED | No AI-attribution lines observed in committed artefacts under `specs/001-polling-foundation/`. |

## Coverage Summary (Requirements → Tests / Tasks / Plan)

| Requirement | Task IDs | Plan ref | Feature tag |
|-------------|----------|----------|-------------|
| FR-001 | T055, T043 | §Constraints | TS-001, TS-002 |
| FR-002 | T058, T040 | §Summary | TS-002, TS-006, TS-041 |
| FR-003 | T058, T050–T053 | §Summary | TS-002 |
| FR-004 | T015, T053, T042 | §Constraints | TS-003, TS-004, TS-051 |
| FR-005 | T059, T045 | §Summary | TS-002, TS-010–TS-015, TS-026 |
| FR-006 | T058, T041 | §Summary | TS-005 |
| FR-007 | T055, T070 | §Constraints | TS-020 |
| FR-008 | T085, T092 | §Summary | TS-020, TS-021 |
| FR-009 | T015, T083, T072 | §Constraints | TS-022, TS-023, TS-024 |
| FR-010 | T083, T092 | §Summary | TS-005, TS-025 |
| FR-011 | T131 | §Constraints | TS-027, TS-046 |
| FR-012 | T111, T121 | §Summary | TS-030 |
| FR-013 | T111, T121, T102 | §Summary | TS-030, TS-032 |
| FR-014 | T111, T121 | §Summary | TS-031 |
| FR-015 | T121, T030 | §Constraints | TS-033, TS-034 |
| FR-016 | T055, T043 | §Constraints | TS-001, TS-040, TS-041 |
| FR-017 | T024, T026, T027 | §Constitution Check VI | TS-042 |
| FR-018 | T117, T118, T123, T104 | §Constraints | TS-050, TS-051, TS-052 |
| FR-019 | T017, T113–T118, T104 | §Constraints | TS-053–TS-057 |
| SC-001 | perf goal in plan.md | ✓ | TS-020 |
| SC-002 | perf goal in plan.md | ✓ | TS-022 |
| SC-003 | T102 | ✓ | TS-030 |
| SC-004 | DEFERRED per clarification | ✓ (architectural choices retained) | TS-035 (commented out) |
| SC-005 | T043 | ✓ | TS-001, TS-040 |
| SC-006 | T030, T121, T105 | ✓ | TS-033, TS-034 |
| SC-007 | T131 | ✓ | TS-020, TS-027, TS-046 |
| SC-008 | T118, T123 | ✓ | TS-050 |

Coverage: 19/19 FRs (100%); 7/7 active SCs (100%). SC-004 acceptance explicitly deferred per 2026-04-19 clarification.

### Feature Traceability

- **H1 Untested requirements**: none. Every FR and every active SC carries at least one matching `@FR-XXX`/`@SC-XXX` tag in `tests/features/*.feature`.
- **H2 Orphaned tags**: none. Every `@FR-XXX`/`@SC-XXX` tag in `.feature` files refers to an ID that exists in spec.md.
- **H3 Step-definition coverage**: N/A — `tests/step_definitions/` intentionally absent per Principle VII (no BDD runner).

## Phase Separation Violations

None.

- Constitution stays at principle level; no technology or implementation detail.
- Spec stays at requirement/user-story level; no schema, no endpoint shape, no library names.
- Plan carries technology choices (Java 25, jOOQ, Flyway, bun) and implementation constraints; no governance principles re-litigated.

## Metrics

- Total requirements: 27 (19 FR + 8 SC)
- Total tasks: 100
- Requirement coverage (active): 100%
- Ambiguity findings: 0
- Critical issues: 0
- Total findings: 4 (0 CRITICAL / 0 HIGH / 1 MEDIUM / 3 LOW)

## Health Score

**Health Score: 97/100 (↑ improving)**

Previous: 91/100 (2026-04-19T00:00:00Z, 1 HIGH + 2 MEDIUM).

## Score History

| Run | Score | Coverage | Critical | High | Medium | Low | Total |
|-----|-------|----------|----------|------|--------|-----|-------|
| 2026-04-19T00:00:00Z | 91 | 100% | 0 | 1 | 2 | 0 | 3 |
| 2026-04-19T12:00:00Z | 97 | 100% | 0 | 0 | 1 | 3 | 4 |

## Next Actions

No CRITICAL or HIGH issues. Feature is ready to proceed to `/iikit-07-implement`.

Suggested low-cost fixes before implement:

1. Fix plan.md:204 "Spring Boot 3.4.x" → "Spring Boot 4.0.5" (F1).
2. Correct the story-to-task summary totals (F2).
3. Either pin or explicitly out-of-scope the two orphan edge cases (F3, F4).

None of the above block implementation.

## Remediation Offer

Would you like concrete remediation edits suggested for F1–F4? They will be proposed, not auto-applied.
