<!--
SYNC IMPACT REPORT
==================
Version change: (none) → 1.0.0
Modified principles: n/a (initial ratification)
Added sections:
  - Core Principles (I–VI)
  - Quality Standards
  - Development Workflow
  - Governance
Removed sections: none
Templates reviewed for consistency:
  - plan-template.md: ✅ constitution-check section present
  - spec-template.md: ✅ no technology leakage expected
  - tasks-template.md: ✅ TDD ordering aligns with Principle III
Follow-up TODOs: none
-->

# Slidev Polls Constitution

## Core Principles

### I. Markdown-First Authoring

Polls MUST be authored inline within Slidev markdown using the same editing
flow as any other slide content. Presenters MUST NOT be required to leave
their deck, log into a separate dashboard, or maintain out-of-band
configuration to define, run, or display a poll. Every poll type MUST be
round-trippable through version control as plain text. Rationale: Slidev's
entire value proposition is a developer-native, markdown-as-source-of-truth
authoring flow; polls that live elsewhere break that flow and defeat the
reason this project exists.

### II. Respondent Zero-Friction

Audience members MUST be able to respond to a poll without installing an
app, creating an account, or completing any step beyond following a join
link or code. The respondent experience MUST work on the devices people
already carry into a talk. Collecting personally identifiable information
MUST remain opt-in per poll and MUST NOT be required for baseline polling.
Rationale: every step between "I see a poll" and "I answered" costs
participation; talks are short and audiences will not fight the tool.

### III. Test-First (NON-NEGOTIABLE)

TDD is mandatory. For every user-facing requirement and every acceptance
criterion: tests are written first, approved by the author, observed to
fail, and only then is production code written. The red-green-refactor
cycle MUST be followed. Tests MUST NOT be weakened or deleted to make a
failing run pass — the production code is fixed instead. Rationale:
real-time state synchronisation between presenter and respondents is the
category of bug that silently corrupts live demos; assertions locked
before implementation are the only reliable floor.

### IV. Live-Reliability Over Feature Depth

Failure of any optional subsystem (polling backend, analytics, result
visualisation) MUST NOT crash the presentation or block the presenter from
advancing slides. The system MUST degrade visibly and recoverably: a poll
that cannot reach the backend shows a clear, non-blocking indicator rather
than a stack trace or a frozen slide. No feature ships without an explicit
answer to "what does this look like when the network, backend, or a
respondent misbehaves mid-talk?" Rationale: live talks have no retry; a
crash during a poll is worse for the presenter than having no poll at all.

### V. Simplicity and YAGNI

The simplest design that meets the stated requirement wins. New
abstractions, configuration knobs, and dependencies MUST be justified by a
concrete, present use case — not speculative future needs. Features
outside the documented scope (see `PREMISE.md`) MUST NOT be added without
amending the premise first. Rationale: this project is a presentation
addon maintained alongside talks, not a SaaS platform; every added surface
area is long-term maintenance paid by someone who just wants to give a
talk.

### VI. Observability for Live Events

All failure paths MUST surface actionable diagnostics through structured
logs and clear user-visible messages. Silent failures, swallowed
exceptions, and generic "something went wrong" text are prohibited.
Presenter-facing errors MUST distinguish "your deck is misconfigured"
from "the backend is unreachable" from "this respondent's input was
rejected." Rationale: when something breaks during a talk the presenter
has seconds — not a debugging session — to decide whether to skip the
poll, retry, or move on.

## Quality Standards

- Every merged change MUST include tests covering its acceptance criteria
  (see Principle III). Coverage decreases require explicit justification.
- Every user-visible behaviour change MUST include at least one end-to-end
  or integration-level assertion, not only unit tests.
- Code MUST NOT be merged with failing tests, skipped tests lacking a
  linked issue, or linter errors treated as warnings.
- Public APIs and authored-markdown surfaces MUST be documented before a
  feature is considered complete.

## Development Workflow

- All features MUST follow the intent-integrity-kit phase sequence:
  `PREMISE → CONSTITUTION → spec → (clarify) → plan → (checklist) →
  testify → tasks → implement`. Phases MUST NOT be skipped; phase
  artifacts MUST exist before downstream work begins.
- Assertion integrity hashes in `.specify/context.json` MUST NOT be
  overwritten or bypassed. Pre-commit hook failures MUST be resolved by
  regenerating testify artifacts, never by `--no-verify`.
- Every change MUST be reviewable as a unit that produces a working
  system; "half-landed" refactors without a follow-up issue are
  prohibited.
- Decisions that alter scope, principles, or cross-feature behaviour MUST
  be captured in the relevant artifact (premise, constitution, or plan),
  not left implicit in commit messages.

## Governance

This constitution supersedes any informal practice, ad-hoc preference, or
individual contributor style in this repository. Where guidance conflicts,
the constitution wins.

**Amendments**: Any change to a principle or to this governance section
requires (a) an explicit amendment commit whose message cites the section
amended, (b) a version bump per the rules below, and (c) an updated Sync
Impact Report at the top of this file.

**Versioning**: Semantic versioning applies to this document.
- MAJOR: removal or redefinition of a principle in a way that breaks
  compatibility with prior guidance.
- MINOR: addition of a new principle or materially expanded section.
- PATCH: clarifications, wording, and non-semantic fixes.

**Compliance**: Every pull request MUST be reviewable against this
constitution. Reviewers MUST flag principle violations explicitly;
authors MUST either fix the violation or propose an amendment — they MUST
NOT silently deviate. Complexity or deviations introduced to meet a
requirement MUST be justified in the plan for the feature that introduces
them.

**Version**: 1.0.0 | **Ratified**: 2026-04-19 | **Last Amended**: 2026-04-19
