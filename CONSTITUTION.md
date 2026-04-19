<!--
SYNC IMPACT REPORT
==================
Version change: 1.2.0 → 1.3.0
Modified principles: none
Added principles:
  - XI. Reactor-Native Maven Invocation
Added sections: none (addition lands inside Core Principles)
Removed sections: none
Templates reviewed for consistency:
  - plan-template.md: ✅ constitution-check section still valid;
    Principle XI is a contributor-workflow rule, not a per-feature gate
  - spec-template.md: ✅ unaffected
  - tasks-template.md: ✅ unaffected (invocation-form is a contributor
    duty, not a standalone task)
Follow-up TODOs: none.

---- Previous SYNC IMPACT REPORT (1.1.0 → 1.2.0) ----
Added principles:
  - X. Documentation-Verified Library Usage

---- Previous SYNC IMPACT REPORT (1.0.0 → 1.1.0) ----
Added principles:
  - VII. No BDD Frameworks
  - VIII. Minimal External Dependencies
  - IX. Human-Authored Presentation
Templates reviewed for consistency:
  - plan-template.md: ✅ constitution-check section still valid
  - spec-template.md: ✅ unaffected
  - tasks-template.md: ✅ testify step now treated as spec-only; real
    assertions remain in standard test tasks per Principle VII
Follow-up TODOs:
  - When `/iikit-04-testify` runs, treat generated `.feature` files as
    documentation artifacts; mirror Given/When/Then as comments inside
    ordinary test code (Principle VII).
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

### VII. No BDD Frameworks

Tests MUST be written using standard unit and integration test frameworks
native to the chosen language. BDD- or Gherkin-based test runners MUST
NOT be introduced as a dependency. Given/When/Then scenarios produced by
`/iikit-04-testify` are treated as specification artifacts only; their
assertions MUST be expressed inside ordinary test code, and the scenario
text SHOULD be mirrored as comments above the corresponding assertions
with the spec ID referenced for traceability. Rationale: BDD runners add
a second test-discovery path and a layer of indirection without catching
failures that plain tests miss; keeping every assertion in the native
test framework gives direct failure output, simpler tooling, and one
obvious place to look when something breaks.

### VIII. Minimal External Dependencies

New runtime or build-time dependencies MUST be justified by a concrete,
present requirement that cannot be met reasonably with the standard
library, an already-adopted dependency, or a small amount of local code.
Each addition MUST be recorded in the feature's `plan.md` with its
purpose. Transitive footprint, release cadence, and maintenance burden
count against the case for adoption. "Saves a few lines" is not
sufficient justification. Rationale: every dependency is a supply-chain
surface, a version-skew risk, and a future upgrade tax; a project this
size pays more over its lifetime in dependency maintenance than in the
code the dependency replaces.

### IX. Human-Authored Presentation

All committed repository content — commit messages, source code,
comments, documentation, issue descriptions, and pull-request
descriptions — MUST read as authored by a human contributor. AI-generated
attribution lines (for example "Generated with …", co-author trailers
referencing AI assistants, or tool watermarks) MUST NOT appear in
committed artifacts. Contributors using AI assistance remain responsible
for the output and MUST review and edit it to match the project's voice
before committing. Rationale: AI attribution leaks tooling choices into a
public-facing record, produces inconsistent style across contributors,
and degrades the perceived craftsmanship of the project; the repository
should present as a coherent human-authored codebase regardless of how
any individual change was drafted.

### X. Documentation-Verified Library Usage

When writing or modifying code that uses an external library, framework,
SDK, API, CLI tool, or cloud service — even one considered well-known —
contributors MUST consult current documentation for the exact version in
use before landing the change. Recalled knowledge and training data go
stale; "I already know this API" is not a substitute. In this repository
the authoritative mechanism is the Context7 MCP server (invoked as
`resolve-library-id` followed by `query-docs`), which MUST be preferred
over general web search or memory for any library-specific question,
including API syntax, configuration, version-migration notes, and setup
instructions. This obligation does not apply to refactoring that does
not change library surface usage, to debugging of business logic, or to
general programming concepts. Rationale: silently-broken upgrades,
removed APIs, and deprecated configuration surface only at runtime or on
the next release, and the cost of that failure during a live talk — or
of rolling back a dependency bump after the fact — far exceeds the cost
of a single doc lookup before the change is written.

### XI. Reactor-Native Maven Invocation

Contributors MUST NOT run `mvn install` or `./mvnw install` against
this repository, nor any later lifecycle phase such as `deploy`. The
Maven reactor already resolves sibling-module dependencies from the
in-memory build graph without writing to `~/.m2/repository`; installing
project artefacts into the local repository pollutes that cache with
mutable snapshots that can shadow a later remote resolution, masks
coupling problems that would surface on a clean contributor checkout,
and lengthens the build with I/O that adds no verification value. All
local Maven work MUST take one of two forms:

1. A reactor-wide goal run at the repository root, e.g. `./mvnw
   validate`, `./mvnw compile`, `./mvnw test`, `./mvnw verify`,
   `./mvnw spotless:apply`.
2. A targeted subset with also-make: `./mvnw -pl <module[,module...]>
   -am <goal>` (e.g. `./mvnw -pl backend/poll-core -am test`). The
   reactor transparently rebuilds upstream modules in memory for that
   invocation.

Rationale: reactor goals exercise the same resolution path that CI and
a fresh contributor checkout take; `install` creates a second mutable
path that drifts from both, so "my build works" stops being equivalent
to "their build works". Keeping the local Maven repository free of
project-authored JARs removes that drift surface entirely.

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

**Version**: 1.3.0 | **Ratified**: 2026-04-19 | **Last Amended**: 2026-04-19
