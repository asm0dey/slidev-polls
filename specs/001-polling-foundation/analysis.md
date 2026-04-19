# Specification Analysis Report

**Feature**: 001-polling-foundation
**Generated**: 2026-04-19
**Artifacts analysed**: spec.md, plan.md, tasks.md, tests/features/*.feature, CONSTITUTION.md

## Findings

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| A001 | Inconsistency / Traceability | HIGH | tests/features/live-results.feature:54; tasks.md:7 | `@TS-035` scenario still present in `live-results.feature` despite the 2026-04-19 clarification mandating its removal ("remove … TS-035 scenario entirely"). Feature files are integrity-anchored and cannot be hand-edited. | Re-run `/iikit-04-testify` so the regenerated `.feature` file and `context.json` hash reflect the clarification, then re-hash. |
| A002 | Coverage Gaps (plan) | MEDIUM | plan.md | plan.md references only FR-001, FR-004, FR-007, FR-009, FR-011, FR-015, FR-016, FR-018, FR-019 and SC-001..SC-007. FR-002, FR-003, FR-005, FR-006, FR-008, FR-010, FR-012, FR-013, FR-014, FR-017 and SC-008 have no explicit plan.md reference. All have task coverage; the risk is narrative drift, not missing implementation. | In the Summary / Constraints / Performance sections, add ID tags where each requirement is realised (e.g. tag the SPA-catch-all constraint with FR-012..FR-014, the QR sentence with FR-005, the Problem-code list with FR-017, the auto-activation paragraph with SC-008). |
| A003 | Coverage Gaps (NFR) | MEDIUM | spec.md:271 (SC-004); tests/features/live-results.feature:54 | SC-004 (200 concurrent respondents) is tagged only by `@TS-035`. After the clarification (load testing deferred), SC-004 will have zero remaining test-spec coverage and zero tasks. | Either (a) mark SC-004 explicitly deferred in spec.md with a note that acceptance is re-opened when load testing returns, or (b) retain a cheap non-load assertion (e.g. 50 concurrent SSE subscribers in `SseHubConcurrencyTest`, T100) and re-tag it `@SC-004`. Option (a) is cheapest and matches the clarification. |

(Detection passes A–H ran; only items above reached reporting threshold. 3 findings / 50 cap.)

## Constitution Alignment

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Markdown-First Authoring | ALIGNED | `slidev-component` renders results inline in slides; presenter never leaves deck. |
| II. Respondent Zero-Friction | ALIGNED | Voter SPA under `/{slug}`, no auth, no PII (FR-007, FR-011, SC-007). |
| III. Test-First (NON-NEGOTIABLE) | ALIGNED | Every US phase has its test tasks listed before impl tasks; assertion-integrity hashes present in `context.json`. |
| IV. Live-Reliability Over Feature Depth | ALIGNED | SSE reconnect + paused indicator (FR-015, SC-006); SseHub isolates emitter failures. |
| V. Simplicity and YAGNI | ALIGNED | Single process, single DB, single SSE channel; no broker; no second web tier. |
| VI. Observability for Live Events | ALIGNED | `CorrelationIdFilter`, structured JSON logs, enumerated `ProblemCode`s. |
| VII. No BDD Frameworks | ALIGNED | JUnit 5 + Vitest only; no step_definitions directory; scenarios mirrored as comments. |
| VIII. Minimal External Dependencies | ALIGNED | Every listed dependency has a concrete present use; jOOQ replaces JPA; no UI framework. |
| IX. Human-Authored Presentation | ALIGNED | Principle stated; no AI attribution lines observed in any tracked artifact reviewed here. |

## Coverage Summary

Spec requirement IDs that have (a) feature-file coverage and (b) task coverage:

| ID | Feature tag | Task(s) | Plan ref? |
|----|-------------|---------|-----------|
| FR-001 | ✓ | T043, T055 | yes |
| FR-002 | ✓ | T053, T058 | no |
| FR-003 | ✓ | T050, T053, T058 | no |
| FR-004 | ✓ | T042, T053, T118 | yes |
| FR-005 | ✓ | T045, T059 | no |
| FR-006 | ✓ | T041, T053, T058 | no |
| FR-007 | ✓ | T055, T070, T087 | yes |
| FR-008 | ✓ | T070, T085, T092 | no |
| FR-009 | ✓ | T072, T082, T083, T086 | yes |
| FR-010 | ✓ | T071, T083 | no |
| FR-011 | ✓ | T086, T091, T131 | yes |
| FR-012 | ✓ | T110, T111, T112, T121 | no |
| FR-013 | ✓ | T101, T102, T111, T121 | no |
| FR-014 | ✓ | T101, T111, T121 | no |
| FR-015 | ✓ | T105, T121 | yes |
| FR-016 | ✓ | T043, T055 | yes |
| FR-017 | ✓ | T024, T026, T027 | no |
| FR-018 | ✓ | T104, T118, T123 | yes |
| FR-019 | ✓ | T104, T113, T115, T117, T118 | yes |
| SC-001 | ✓ | quickstart §S2 | yes |
| SC-002 | ✓ | quickstart §S2 | yes |
| SC-003 | ✓ | T102 | yes |
| SC-004 | ✓ (TS-035 only — see A003) | none after clarification | yes |
| SC-005 | ✓ | T043 | yes |
| SC-006 | ✓ | T105 | yes |
| SC-007 | ✓ | T131 | yes |
| SC-008 | ✓ | T104, T123 | no |

All 19 functional requirements and all 8 success criteria carry at least one `@FR-XXX`/`@SC-XXX` tag in the feature files (H1: 0 untested requirements). No orphaned tags reference non-existent IDs (H2: 0 orphans). No step-definition directory exists — H3 not applicable (intentional per Principle VII).

## Phase Separation Violations

None found. Constitution contains no technology choices; plan contains technology and implementation detail; spec contains no implementation detail.

## Metrics

- Total requirements: 27 (19 FR + 8 SC)
- Total tasks: 102
- Feature-file tag coverage: 27 / 27 (100 %)
- Plan.md explicit reference coverage: 16 / 27 (59 %) — see A002
- Ambiguity findings: 0
- Critical issues: 0

## Health Score

**Score: 91/100 (→ stable — first run)**

Formula: `100 − (0·20 + 1·5 + 2·2 + 0·0.5) = 91`.

## Score History

| Run | Score | Coverage | Critical | High | Medium | Low | Total |
|-----|-------|----------|----------|------|--------|-----|-------|
| 2026-04-19T00:00:00Z | 91 | 100% | 0 | 1 | 2 | 0 | 3 |

## Next Actions

No CRITICAL issues. Proceeding to `/iikit-07-implement` is permitted, but resolving **A001** is strongly recommended first because `.feature`-file drift will block pre-commit hooks as soon as any commit touches a feature-tagged area. **A002** and **A003** are documentation-level tidy-ups and do not block implementation.

Suggested order:
1. Re-run `/iikit-04-testify` to regenerate `live-results.feature` without the TS-035 scenario and refresh `context.json` hashes (fixes A001, reinforces A003).
2. Edit plan.md to tag FR-002/003/005/006/008/010/012/013/014/017 and SC-008 where they are realised (fixes A002).
3. Add an explicit "deferred — load testing out of scope for this feature" note beside SC-004 in spec.md (completes A003).

## Remediation Offer

Want concrete remediation edits drafted for any of A001, A002, A003? (No edits have been applied.)
