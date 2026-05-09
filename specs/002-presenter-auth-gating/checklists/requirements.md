# Spec Quality Checklist — Presenter Auth Gating in Slidev Deck

Feature: `002-presenter-auth-gating`
Generated: 2026-04-21

## Content Quality

- [x] Specification describes user-facing behaviour, not implementation.
- [x] No programming languages, frameworks, databases, or specific
      libraries are referenced in spec.md (tech choices deferred to
      plan.md per constitution phase separation).
- [x] User stories are independently testable and each delivers value.
- [x] User stories are prioritised (P1, P1, P2) and the ordering is
      justified in the "Why this priority" sections — the second P1 is
      intentional: the security gate is as critical as the auth control
      that makes it reachable.
- [x] Acceptance scenarios use Given/When/Then phrasing.
- [x] Edge cases are enumerated, not silently assumed.

## Requirement Completeness

- [x] Every user story maps to at least one functional requirement.
- [x] Every functional requirement is testable and phrased with MUST
      (or SHOULD where graceful-degradation is the intent, e.g. FR-005
      reload persistence).
- [x] Authentication boundary (signed-in deck vs unauthenticated deck)
      is explicit in both the gating (FR-006..FR-009) and the
      read-only (FR-010..FR-013) requirement groups.
- [x] Relationship to Feature 001 (DeckToken, FR-019) is explicit:
      FR-009 and the Presenter Credential entity both anchor on the
      existing mechanism rather than introducing a parallel surface.
- [x] Failure modes (invalid credential, revoked credential, lost
      live-update channel, simultaneous signed-in decks) each have a
      corresponding requirement or edge-case entry.
- [x] Key entities cover the nouns introduced by this feature (Deck
      Auth State, Presenter Credential) without duplicating entities
      already defined by Feature 001.

## Feature Readiness

- [x] Success criteria are measurable and technology-agnostic (counts,
      time budgets, booleans — no framework or storage-mechanism
      names).
- [x] Success criteria include at least one security assertion
      (SC-001, SC-005, SC-006).
- [x] Success criteria include at least one UX / read-only-viewer
      assertion (SC-004).
- [x] Success criteria reference Feature 001 budgets where they
      apply, so this feature does not silently weaken the
      live-reliability posture already accepted by the project.
- [x] No `[NEEDS CLARIFICATION]` markers remain.
- [x] Out-of-scope items are called out (multi-presenter coordination
      beyond last-write-wins, presenter-follow-mode for viewers,
      richer in-deck presenter controls — all deferred).
- [x] Spec is self-contained: a reader new to the project can
      understand what is being built and why without prior context
      beyond the named Feature 001 references.

## Open Assumptions (recorded, not blocking)

- The presenter credential is the **same DeckToken** introduced by
  Feature 001 FR-019; this feature changes *where* and *when* the
  token is supplied (interactively, from inside the deck) but not
  what it is. If the plan phase decides to introduce a different
  credential type, that decision must be reflected back into FR-009
  and the Presenter Credential entity.
- **Last-write-wins** across multiple signed-in decks is inherited
  from Feature 001 FR-004 and is not further constrained here. A
  future feature may introduce presenter-lease or single-presenter
  enforcement if the need arises.
- **Local navigation in an unauthenticated deck is independent** of
  the presenter's stage navigation (Story 3 scenario 3). A
  presenter-follow mode is explicitly out of scope; if it is later
  desired it will be a separate feature.
- Credential **input mechanism** in the auth control (typed, pasted,
  or an in-deck login flow) is a plan-phase decision; the spec
  requires only that a credential can be supplied and revoked.

## Downstream Impact Notes

- Feature 001 FR-018 ("Navigation to a Slidev slide … MUST
  automatically mark that question as active") is **narrowed** by
  FR-006 here: it now applies only when the deck browser is in the
  signed-in state. The plan phase must surface this narrowing as an
  explicit update to existing behaviour, not a silent code change.
- Feature 001 FR-019's DeckToken lifecycle (mint, scope, revoke)
  gains a new consumer: an interactive in-deck sign-in surface. Any
  tightening of revocation semantics (SC-005) must be compatible
  with Feature 001's existing revocation path.
