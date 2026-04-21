# Implementation Plan: Presenter Auth Gating in Slidev Deck

**Branch**: `002-presenter-auth-gating` | **Date**: 2026-04-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-presenter-auth-gating/spec.md`

## Summary

Today's addon (feature 001) reads the **deck token** directly from a
Vue prop — i.e. from the Slidev markdown source — and ships the bearer
to every viewer of the deck URL along with the rest of the slide
bundle. Any audience member who opens the same URL carries that
token, so navigating through the slides calls
`POST /api/deck/polls/{pollId}/activate` from their browser and
rewrites the poll's active question on the backend. That is the
"anyone in the room can hijack the active question" defect this
feature closes.

The fix is entirely frontend-first with a small, auth-only backend
addition:

- The Slidev addon stops accepting `deckToken` as a `<PollResults>`
  prop. The deck token is **never embedded in slide markdown** again.
- The addon ships a persistent in-deck **auth control** (rendered
  through Slidev's `global-top.vue` overlay, so it sits above every
  slide exactly like the existing navigation affordances). The control
  is a small button in the not-signed-in state; opening it shows an
  input where the presenter pastes the deck token (the same plaintext
  bearer already minted by the backoffice in 001). On successful
  verification it transitions to a "signed in" pill showing the
  token's label and offers sign-out.
- A new composable, `useDeckAuth()`, owns the per-browser auth state.
  It persists the token in `localStorage` (key
  `slidev-polls:deck-auth`), exposes a reactive `status` of
  `"anonymous" | "signed-in" | "revoked"`, and re-validates on load so
  reloads restore "signed in" without re-prompting (FR-005, SC-003).
- `<PollResults>` reads `useDeckAuth()` instead of its deleted
  `deckToken` prop. On mount, if `status === "signed-in"` **and** the
  slide declares a `questionId`, it fires the existing activation
  POST. Otherwise it skips the activation call entirely — the SSE
  subscription still runs, so unauthenticated viewers get the live
  read-only view for free (FR-010 – FR-013).
- The addon also listens for `DECK_TOKEN_INVALID` responses from the
  activation POST. On such a response `useDeckAuth()` flips to
  `"revoked"`, the control reverts to the not-signed-in visual, and a
  presenter-facing message distinguishes *authentication* from
  *authorisation* from *transport* failure (FR-008, FR-014, SC-005).
- **Backend delta** is a single new endpoint,
  `GET /api/deck/auth/me`, authenticated by the existing
  `DeckTokenAuthenticationFilter`. It exists purely so the auth
  control can validate a freshly-pasted token *before* the presenter
  navigates to a poll slide (so step 1 of story 1 does not silently
  accept garbage) and so the composable can re-validate on reload.
  It returns `{ tokenId, pollId, label }` on 200 and the standard
  `Problem{DECK_TOKEN_INVALID}` envelope on 401.

This feature introduces **no new authorisation surface**: the backend
continues to refuse anonymous callers on every route that mutates a
poll's active question (FR-009, SC-006). The deck token remains the
single presenter credential for deck-driven activation.

## Technical Context

**Language/Version** (unchanged from 001):

- Backend: Java 25, Spring Boot 4.0.5 (Spring Framework 7, Jakarta 11).
- Frontend: TypeScript 5.x; bun toolchain; Vite bundler; Vue 3.

**Primary Dependencies** (unchanged — no new runtime or build
dependency is added):

- Backend: `spring-boot-starter-web`, `spring-boot-starter-security`,
  existing `DeckTokenAuthenticationFilter` and `DeckTokenService` from
  `poll-api` / `poll-core`. The new endpoint is a plain
  `@RestController` method.
- Frontend: Vue 3 `ref` / `computed` / `provide` / `inject`; existing
  `fetch` + `EventSource` usage from `@polls/shared`. The global-top
  overlay is a built-in Slidev extension point (no new dependency).
  Per Principle VIII, no new frontend package is introduced.

**Storage**: No schema change. Deck tokens already live in the
`deck_tokens` table (feature 001, Flyway migration V3). The new
`GET /api/deck/auth/me` reads from that table via the existing
`DeckTokenService.resolveLive(plaintext)`.

**Testing**:

- Backend: JUnit 5. `DeckAuthController` gets a `@SpringBootTest` with
  MockMvc covering (a) happy path 200 with body shape, (b) missing
  header → 401 `DECK_TOKEN_INVALID`, (c) unknown bearer → 401, (d)
  revoked bearer → 401. One `@WebMvcTest` slice re-uses the existing
  filter wiring.
- Frontend unit (`@polls/slidev-addon`):
  - `useDeckAuth.test.ts` — Vitest + jsdom. Covers the reactive state
    machine: anonymous → signed-in via verify, signed-in persists
    across a simulated reload, revoked transition on 401, sign-out
    clears `localStorage`.
  - `DeckAuthControl.test.ts` — Vitest + `@vue/test-utils`. Covers the
    three rendered states (not-signed-in / signed-in pill / revoked
    with message), form submission wiring, and accessibility-minimum
    assertions (role=button, keyboard focus).
  - Extend `PollResults.test.ts` with two scenarios: mount with
    `useDeckAuth()` stub in `"anonymous"` state must NOT call
    `fetch` with the activation URL (FR-007); mount with
    `"signed-in"` state must call it exactly once. Existing
    `deckToken` prop scenarios (`@TS-050`, `@TS-053` –
    `@TS-055`) are rewritten to drive the state through the
    composable rather than the deleted prop.
- Frontend e2e (`frontends/slidev-component/e2e`): extend
  `slidev-results.spec.ts` with an auth scenario — page opens the
  voter SPA shell (same-origin context), the test drives the
  composable's `signIn` via the exposed DOM form, asserts the
  resulting POST reaches `/api/deck/auth/me` with the header, and
  that a follow-up fetch to the activation endpoint only fires in the
  signed-in variant. The read-only variant asserts zero activation
  traffic, mirroring User Story 2's independent test.

**Target Platform**: unchanged (Linux server backend; evergreen
desktop + mobile browsers for the frontends; Slidev dev server for
the addon).

**Project Type**: unchanged — Maven multi-module backend + bun
workspace frontend.

**Performance Goals**:

- `GET /api/deck/auth/me` is a single-row lookup by token hash; p95
  under 100 ms on the development machine (well inside the 10-second
  sign-in budget of SC-002).
- The reload-time `verify()` call runs once on composable init before
  the first navigation; it MUST NOT block slide rendering — the call
  is fired-and-awaited in the background and the addon continues to
  render read-only until it resolves.

**Constraints**:

- **Token is never embedded in slide markdown.** Removing the
  `deckToken` prop from `<PollResults>` is part of the contract. A
  prop-based override will not be re-introduced under any flag,
  because any such override is, by construction, a way to leak the
  credential back into the deck source (the exact regression this
  feature exists to prevent).
- **Activation is gated on in-memory state, not network state.** The
  composable's `status` is the single source of truth consulted on
  mount; no optimistic activation is attempted while `status ===
  "anonymous"`, because FR-007 requires *zero* activation calls in
  that case — not "try and recover on 401."
- **Sign-out MUST be local-only.** It clears `localStorage` and flips
  the composable; it does NOT call the backend to revoke the token
  (the same token may still be in use on another device, per the last
  edge case in the spec). Credential revocation remains a backoffice
  action (FR-015).
- **Same-origin assumption for `localStorage`** matches the rest of
  001: the deck, both SPAs, and the backend all live on one origin.
  Cross-origin deploys are out of scope (001 ruled them out with
  "single origin, single process" — this feature inherits it).
- **Live-update loss MUST NOT change auth state.** A dropped SSE
  connection produces the existing "live updates paused" badge from
  `<PollResults>`; the auth control's state is completely independent
  of it (FR-013, Principle IV).
- **Failure classification** — the auth control surfaces three
  distinct messages per FR-014: "credential not recognised"
  (authentication), "credential not authorised for this poll"
  (authorisation — only possible on activation, not on verify),
  "couldn't reach server" (transport). These map to the existing
  `Problem` codes `DECK_TOKEN_INVALID`,
  `DECK_TOKEN_POLL_MISMATCH`, and `TRANSPORT_FAILURE` so no new
  code needs to be added to `ProblemCode`.

**Scale/Scope**: unchanged from 001. Auth state is per-browser, so
adding it scales trivially with viewer count.

## Constitution Check

| Principle | Gate | Result |
|-----------|------|--------|
| I. Markdown-First Authoring | The auth control is rendered by the addon as an overlay; the presenter does not leave the deck to sign in. Markdown loses the (broken) `deckToken` frontmatter prop; nothing moves *out* of markdown — the deck token was never meaningful slide content, only a security leak. | Pass |
| II. Respondent Zero-Friction | Unauthenticated viewers retain the read-only poll results view; no install, no account, no change in their experience. Only the presenter sees the auth affordance. | Pass |
| III. Test-First (NON-NEGOTIABLE) | `/iikit-04-testify` will emit `.feature` files for every user story; `/iikit-05-tasks` orders test tasks before the implementation tasks that satisfy them. The composable, the control component, and the new backend endpoint each get a red-first test per acceptance scenario. | Pass |
| IV. Live-Reliability Over Feature Depth | Failure of `GET /api/deck/auth/me` leaves the deck in `"anonymous"` (read-only) — never throws out of the composable. The activation POST retains 001's swallowed-error behaviour so a flaky network on slide change never crashes the slide. The paused badge is reused for SSE loss. | Pass |
| V. Simplicity and YAGNI | One new endpoint; one new client composable; one new Vue component; one deleted prop. No new package, no new dependency, no new storage, no new auth primitive. The single-source-of-truth deck token remains the one credential type. | Pass |
| VI. Observability for Live Events | Reuse of existing `Problem{code}` envelope — `DECK_TOKEN_INVALID`, `DECK_TOKEN_POLL_MISMATCH`, `TRANSPORT_FAILURE` — gives the presenter the FR-014 distinction out of the box. The new endpoint inherits `CorrelationIdFilter`. | Pass |
| VII. No BDD Frameworks | Vitest + JUnit 5 only. Gherkin scenarios from `/iikit-04-testify` are mirrored as comments above the corresponding assertions; no runner is added. | Pass |
| VIII. Minimal External Dependencies | No new runtime or build dependency in either the frontend or the backend. The Slidev global-top overlay is a built-in extension point. | Pass |
| IX. Human-Authored Presentation | Commit messages, code comments, docs, and PR descriptions authored by humans; no AI-attribution trailers will be introduced. | Pass |
| X. Documentation-Verified Library Usage | The Slidev global-top / addon publishing surface used here was re-verified against current Slidev docs via Context7 before this plan was written (see `research.md`). Spring Security filter reuse does not change library surface — no re-verification needed. | Pass |
| XI. Reactor-Native Maven Invocation | All backend work is reactor-wide (`./mvnw verify`) or `-pl poll-api -am test`. No `install` is invoked. | Pass |

No violations. Complexity Tracking table is intentionally empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-presenter-auth-gating/
  plan.md              # This file
  research.md          # Phase 0 output
  data-model.md        # Phase 1 output
  quickstart.md        # Phase 1 output
  contracts/           # Phase 1 output (OpenAPI delta for /api/deck/auth/me)
  tasks.md             # Phase 2 output (/iikit-05-tasks)
  checklists/          # /iikit-03-checklist output (if invoked)
```

### Source Code (delta against 001)

```text
slidev-polls/
├── backend/
│   └── poll-api/
│       └── src/main/java/site/asm0dey/slidev/polls/api/deck/
│           ├── DeckActivationController.java      # (unchanged, from 001)
│           └── DeckAuthController.java            # NEW — GET /api/deck/auth/me
│       └── src/main/java/site/asm0dey/slidev/polls/api/deck/dto/
│           └── DeckPrincipalView.java             # NEW — response DTO
│       └── src/test/java/…/api/deck/
│           └── DeckAuthControllerTest.java        # NEW — MockMvc cases above
│
└── frontends/
    └── slidev-component/
        ├── global-top.vue                         # NEW — mounts DeckAuthControl
        ├── composables/
        │   ├── useDeckAuth.ts                     # NEW — reactive auth state
        │   └── useDeckAuth.test.ts                # NEW — state-machine tests
        ├── components/
        │   ├── DeckAuthControl.vue                # NEW — sign-in button + form
        │   ├── DeckAuthControl.test.ts            # NEW — rendered-state tests
        │   ├── PollResults.vue                    # MODIFIED — drops deckToken prop,
        │   │                                      #   reads useDeckAuth(); surfaces
        │   │                                      #   activation 401 to composable
        │   └── PollResults.test.ts                # MODIFIED — rewrites @TS-050 /
        │                                      #   @TS-053 / @TS-054 / @TS-055
        │                                      #   around the composable
        ├── e2e/
        │   └── slidev-results.spec.ts             # MODIFIED — new auth gating case
        ├── index.ts                               # (unchanged public surface;
        │                                      #   composable is internal)
        └── package.json                           # (unchanged)
```

**Structure Decision**: Extend the existing `@polls/slidev-addon`
package in place — the feature does not warrant a new module. The
auth UI is ordinary in-addon Vue (one component, one composable,
one Slidev global-overlay hookup). The backend delta is a single
new controller alongside the existing `DeckActivationController` in
`poll-api`, reusing the deck-token security filter wired in 001.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations; table intentionally empty.
