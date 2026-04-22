# Tasks: Presenter Auth Gating in Slidev Deck

**Input**: Design documents from `/specs/002-presenter-auth-gating/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi-delta.yaml, tests/features/

**Tests**: Required by Constitution Principle III (Test-First NON-NEGOTIABLE). Every acceptance / contract / validation scenario from the three `.feature` files gets a red-first test task before its implementation task.

**Organization**: Tasks grouped by user story. US1 and US2 are both P1; US2 depends on US1's composable and auth endpoint being in place. US3 (P2) verifies the read-only path once US2's gating lands.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies).
- **[Story]**: US1 / US2 / US3. Setup/Foundational/Polish tasks carry no story label.
- File paths are exact and rooted at the repository.
- **Traceability**: Test spec IDs are listed as explicit comma-separated sets, never prose ranges.

## Path Conventions

- Backend (Maven reactor): `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/deck/`, tests under `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/deck/`.
- Frontend addon (bun workspace): `frontends/slidev-component/` (components, composables, e2e all live here).
- Per Principle XI: all Maven invocations reactor-wide (`./mvnw verify`) or `-pl poll-api -am <goal>`; `install` is prohibited.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the empty files and overlay hookup that both stories will fill in. No behaviour yet; these establish the layout declared in `plan.md` §Project Structure.

- [x] T001 Create `frontends/slidev-component/composables/` directory and an empty placeholder `useDeckAuth.ts` (exports stub symbol) so downstream import paths resolve during red-phase test runs.
- [x] T002 [P] Add empty `frontends/slidev-component/global-top.vue` (template-only, mounts nothing yet) so Slidev's overlay slot is claimed by the addon build.
- [x] T003 [P] Add empty `frontends/slidev-component/components/DeckAuthControl.vue` (script+template shell, no behaviour) as the target for component tests.
- [x] T004 [P] Add empty `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/deck/dto/` directory and placeholder `DeckPrincipalView.java` record with the three fields from `data-model.md` (no controller wired yet).

**Checkpoint**: Workspace compiles (`./mvnw -pl poll-api -am compile`, `bun --cwd frontends/slidev-component run build`). No behaviour added; no tests expected to pass or fail yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared wiring both P1 stories depend on. `useDeckAuth` type surface is referenced by US1 (sign-in flow) and US2 (revoked flip from `<PollResults>`); publishing the type stub lets both story's tests compile in parallel.

**CRITICAL**: No user story implementation work can begin until this phase is complete.

- [x] T005 Define the `DeckAuthStatus` union (`"anonymous" | "signed-in-pending" | "signed-in" | "revoked"`) and the `DeckAuthState` / `UseDeckAuthReturn` TypeScript interfaces in `frontends/slidev-component/composables/useDeckAuth.ts` per `data-model.md` §DeckAuthState. Export types only; runtime functions throw `not-yet-implemented` so composable tests will fail red as required by Principle III.
- [x] T006 [P] Declare `DeckPrincipalView` fields (`tokenId: UUID`, `pollId: UUID`, `label: String?`) as an immutable Java record in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/deck/dto/DeckPrincipalView.java` per `contracts/openapi-delta.yaml` and `data-model.md` §DeckPrincipalView. Controller wiring lands in T017.

**Checkpoint**: Shared types resolvable by both TS and Java toolchains; no business logic yet.

---

## Phase 3: User Story 1 — Presenter authenticates inside the deck (Priority: P1) MVP

**Goal**: A fresh browser can sign in by pasting a deck token into an in-deck control, the control reflects signed-in state, state survives navigation and reload, and sign-out is local-only. All mediated by a new `GET /api/deck/auth/me` endpoint that validates a bearer without side effects.

**Independent Test**: Open the deck in a fresh profile, click the auth control, paste a valid deck token minted by 001; the control transitions to "signed in" and a `GET /api/deck/auth/me` returns 200 with the token's scope. Reload; the control stays signed in without re-prompting. See `quickstart.md` §1.

### Tests for User Story 1

> **Write these FIRST and observe them fail before moving to implementation.**

- [x] T007 [P] [US1] `DeckAuthControllerTest.returns200WithScope_whenDeckTokenValid` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/deck/DeckAuthControllerTest.java` — MockMvc call with `X-Deck-Token: <valid>` returns 200 and body fields `tokenId`, `pollId`, `label`. [TS-107]
- [x] T008 [P] [US1] `DeckAuthControllerTest.returns401DeckTokenInvalid_whenHeaderMissing` — no `X-Deck-Token` header → 401 Problem envelope code `DECK_TOKEN_INVALID`. [TS-108]
- [x] T009 [P] [US1] `DeckAuthControllerTest.returns401DeckTokenInvalid_whenBearerUnknown` — header present but token hash not in `deck_tokens` → 401 code `DECK_TOKEN_INVALID`. [TS-109]
- [x] T010 [P] [US1] `DeckAuthControllerTest.returns401DeckTokenInvalid_whenBearerRevoked` — token row present but marked revoked → 401 code `DECK_TOKEN_INVALID` (covers the cross-story prerequisite used later by TS-123).
- [x] T011 [P] [US1] `useDeckAuth.test.ts` in `frontends/slidev-component/composables/useDeckAuth.test.ts` — scenario: anonymous start → call `signIn(token)` → `fetch` returns 200 → status transitions `anonymous → signed-in-pending → signed-in`, `localStorage["slidev-polls:deck-auth"]` contains `{token, tokenId, pollId, label, verifiedAt}`. [TS-101]
- [x] T012 [P] [US1] `useDeckAuth.test.ts` — reload simulation: preload `localStorage` then call `useDeckAuth()`; status starts `signed-in-pending`; after a mocked 200 from `GET /api/deck/auth/me` status becomes `signed-in` with no user input. [TS-103]
- [x] T013 [P] [US1] `useDeckAuth.test.ts` — `signOut()` transitions back to `anonymous`, removes the `localStorage` key, and does NOT issue any backend request. [TS-104]
- [x] T014 [P] [US1] `useDeckAuth.test.ts` — `signIn` with a garbage token where the verify returns 401 `DECK_TOKEN_INVALID` keeps status `anonymous` and exposes the authentication-failure message `"credential not recognised"`. [TS-105]
- [x] T015 [P] [US1] `useDeckAuth.test.ts` — `signIn` where `fetch` rejects (network error) keeps status `anonymous` and exposes the distinct transport message `"couldn't reach server"`. [TS-106]
- [x] T016 [P] [US1] `DeckAuthControl.test.ts` in `frontends/slidev-component/components/DeckAuthControl.test.ts` — default render when `useDeckAuth()` status is `anonymous`: visible text/label reads "not signed in"; input field and submit button present. [TS-100]
- [x] T017 [P] [US1] `DeckAuthControl.test.ts` — signed-in render: when composable status is `signed-in` with `label: "Laptop"`, component shows a pill containing "Laptop" and a sign-out affordance. [TS-101]
- [x] T018 [P] [US1] `DeckAuthControl.test.ts` — activating sign-out affordance calls composable `signOut()` exactly once. [TS-104]
- [x] T019 [P] [US1] `DeckAuthControl.test.ts` — submitting garbage input surfaces `"credential not recognised"`; a network-failure variant surfaces `"couldn't reach server"`; both messages are rendered distinctly. [TS-105, TS-106]
- [x] T020 [P] [US1] `DeckAuthControl.test.ts` — across multiple mount/unmount cycles (simulating slide navigation) the component re-reads composable state without re-prompting; no hidden local state contradicts the composable. [TS-102]

### Implementation for User Story 1

- [x] T021 [US1] Implement `DeckAuthController` (`GET /api/deck/auth/me`) in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/deck/DeckAuthController.java`: resolves `DeckPrincipal` from Spring Security context (populated by existing `DeckTokenAuthenticationFilter`), loads row via `DeckTokenService.resolveLive(...)`, returns `DeckPrincipalView`. No new filter, no new auth surface (FR-009). Unblocks T007–T010.
- [x] T022 [US1] Implement the `useDeckAuth` composable body in `frontends/slidev-component/composables/useDeckAuth.ts`: reactive `status`, `state`, `signIn()`, `signOut()`, `markRevoked()` (stub — full revoked wiring lands in T028), and the reload-time background verify per `research.md` D-006. Uses `localStorage` key `slidev-polls:deck-auth`. Unblocks T011–T015.
- [x] T023 [US1] Implement `DeckAuthControl.vue` in `frontends/slidev-component/components/DeckAuthControl.vue`: three visible states (not signed in / pending / signed-in pill with label and sign-out); token input (trimmed, 20–512 printable-ASCII per `data-model.md`); three distinct status messages per FR-014. Unblocks T016–T020.
- [x] T024 [US1] Wire `DeckAuthControl` into `frontends/slidev-component/global-top.vue` so Slidev mounts it as an overlay on every slide per `research.md` D-001. Verify the overlay renders in both single-page and presenter-mode Slidev builds.

**Checkpoint**: US1 green. Presenter can sign in in-deck, state persists, sign-out works, reload rehydrates. `<PollResults>` is unchanged and still uses the old prop path — US2 cuts it over.

---

## Phase 4: User Story 2 — Unauthenticated viewer cannot hijack the active question (Priority: P1)

**Goal**: Anonymous deck browsers never issue an activation POST. Signed-in deck browsers still do. A revoked credential is detected on the next activation attempt and reverts the control to the not-signed-in visual with the FR-014 authentication-failure message. No new backend route mutates the active question.

**Independent Test**: With US1's control signed in in browser A and no auth state in browser B, rapidly navigate every poll slide in B for 60s — zero activation POSTs originate from B (network-panel / e2e assertion). Revoke the token from the backoffice, then navigate in A; A's control reverts to "not signed in" and the poll's active question is unchanged by that navigation. See `quickstart.md` §2.

### Tests for User Story 2

- [x] T025 [P] [US2] Extend `PollResults.test.ts` in `frontends/slidev-component/components/PollResults.test.ts` with case `anonymous mount issues zero activation fetches`: mount with `useDeckAuth()` stub returning `{status: "anonymous"}` and `questionId`/`pollId` props supplied; assert `fetch` is NEVER called with any URL containing `/api/deck/polls/`. [TS-120, TS-121]
- [x] T026 [P] [US2] Extend `PollResults.test.ts` with case `signed-in mount issues exactly one activation POST with X-Deck-Token header`: composable stub `{status: "signed-in", token: "dtk-1", ...}`; assert one `fetch` call to the activate URL with method POST, header `X-Deck-Token: dtk-1`, body `{"questionId": "..."}`. [TS-122]
- [x] T027 [P] [US2] Extend `PollResults.test.ts` with case `activation 401 DECK_TOKEN_INVALID flips composable to revoked`: mocked `fetch` returns 401 with `Problem{code: DECK_TOKEN_INVALID}`; assert `useDeckAuth().markRevoked()` was invoked exactly once and composable status transitions to `"revoked"`; also assert the poll's local active-question pointer in the mounted component is not changed by the failed POST. [TS-123]
- [x] T028 [P] [US2] Rewrite pre-existing `PollResults.test.ts` scenarios `@TS-050`, `@TS-053`, `@TS-054`, `@TS-055` to drive the token through the `useDeckAuth()` stub instead of the deleted `deckToken` prop. Assertions preserved; the prop path goes away.
- [x] T029 [P] [US2] Add `DeckActivationIT.anonymousPostReturns401DeckTokenInvalid` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/deck/DeckActivationIT.java` — POST `/api/deck/polls/{pollId}/activate` with no `X-Deck-Token` header and no session cookie returns 401 with code `DECK_TOKEN_INVALID`; poll's active-question pointer unchanged. [TS-124]
- [x] T030 [P] [US2] Add backend route-audit test `ActiveQuestionMutationRouteAuditTest` asserting that every controller method whose effect mutates `polls.active_question_id` is either under `/api/deck/**` (guarded by `DeckTokenAuthenticationFilter`) or `/api/backoffice/**` (guarded by session auth), and that each rejects anonymous calls with one of `AUTH_REQUIRED` / `DECK_TOKEN_INVALID` / `FORBIDDEN`. [TS-125, TS-126]
- [x] T031 [P] [US2] Extend `frontends/slidev-component/e2e/slidev-results.spec.ts` with scenario `anonymous browser issues zero activation traffic`: Playwright opens the deck page with empty storage, navigates across multiple poll slides, asserts no network request matched `**/api/deck/polls/*/activate`. [TS-120]
- [x] T032 [P] [US2] Extend `e2e/slidev-results.spec.ts` with scenario `signed-in browser issues activation on navigation`: test signs in via the in-deck control (DOM form), navigates, asserts exactly one `POST **/api/deck/polls/*/activate` with the `X-Deck-Token` header. [TS-122]

### Implementation for User Story 2

- [x] T033 [US2] Modify `frontends/slidev-component/components/PollResults.vue`: delete the `deckToken` prop; import `useDeckAuth()`; in `activateFromDeck()` short-circuit when composable status is NOT `"signed-in"` (FR-007); read the bearer from `useDeckAuth().state.value.token`; on 401 with `Problem{code: DECK_TOKEN_INVALID}` call `useDeckAuth().markRevoked()` then swallow (Principle IV keeps the slide rendering).
- [x] T034 [US2] Extend `markRevoked()` in `composables/useDeckAuth.ts` to wipe `localStorage`, flip status to `"revoked"`, and expose the FR-014 authentication-failure message so `DeckAuthControl.vue` renders it on the next paint.
- [x] T035 [US2] Add a dev-only `console.warn("[slidev-polls] `deckToken` prop removed in 002; sign in via the in-deck auth control.")` at the top of `PollResults.vue`'s `setup` if `props.deckToken` is present, per `research.md` D-005. Production builds omit (guard on `import.meta.env.DEV`).
- [x] T036 [US2] Delete `deckToken` from the prop type union in `PollResults.vue` and from `index.ts` public-surface exports in `frontends/slidev-component/index.ts` (if re-exported). Slides that still pass `:deckToken` get a Vue unused-prop warning in dev — which is the signal.

**Checkpoint**: US2 green. Anonymous deck emits zero activation traffic; signed-in deck activates normally; revocation is detected on the very next activation attempt. Backend refuses anonymous mutators on every route that can change the active-question pointer.

---

## Phase 5: User Story 3 — Unauthenticated viewer sees live results in read-only mode (Priority: P2)

**Goal**: Anonymous browsers on poll slides render the live results visualisation, updates arrive within the 2-second budget, loss of SSE shows the "live updates paused" indicator without blocking navigation, and no presenter-only affordance leaks into the anonymous render. The production code is largely unchanged from 001 — US3 is assertion coverage that US2's gating did not regress the read-only path.

**Independent Test**: With a poll receiving votes, an anonymous browser on the Q1 slide shows the current tally, updates as votes arrive, shows no presenter-only controls, survives an SSE drop/reconnect with the paused indicator, and continues showing Q1's results even while the presenter activates Q2 elsewhere. See `quickstart.md` §3.

### Tests for User Story 3

- [x] T037 [P] [US3] In `PollResults.test.ts` add case `anonymous mount renders live results and no activation affordance`: mount with `status: "anonymous"`, feed a `SnapshotEvent`, assert results visualisation renders and no element with a "set active" affordance is in the DOM. [TS-140]
- [x] T038 [P] [US3] In `PollResults.test.ts` add case `tally updates within 2s on TallyDeltaEvent`: fake-timer-based assertion that a `TallyDeltaEvent` arriving at t=0 is reflected in rendered counts at t≤2000ms. [TS-141]
- [x] T039 [P] [US3] In `PollResults.test.ts` add case `SSE drop shows paused indicator, nav remains responsive`: stub `openPollStream` to emit `onConnectionStateChange("paused")`, assert the `[data-testid="poll-paused"]` element renders with text "live updates paused" and that unmount still works cleanly. [TS-142]
- [x] T040 [P] [US3] In `PollResults.test.ts` add case `SSE reconnect clears paused and resumes updates`: flip connection state back to live, emit a fresh snapshot, assert paused indicator disappears and new tallies render. [TS-143]
- [x] T041 [P] [US3] In `PollResults.test.ts` add case `local slide's question results are independent of presenter's stage activation`: component mounted with `questionId: Q1` and stream receiving Q1-scoped snapshots continues to render Q1's tally even when a simulated Q2 activation event arrives for the same poll; the local `questionId` prop wins. [TS-144]

### Implementation for User Story 3

- [x] T042 [US3] Audit `PollResults.vue` and `DeckAuthControl.vue` for any template branch that renders presenter-only controls when `useDeckAuth().status !== "signed-in"`; verify v-if gating. Expected diff is zero — this is a conformance sweep, not a change — but confirms FR-012 holds after US2.

**Checkpoint**: US3 green. Anonymous read-only path unchanged in behaviour from 001; all of its observable properties asserted.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T043 [P] Add a comment block to `global-top.vue` referencing `research.md` D-001 so future readers understand why the auth control lives in the Slidev overlay slot rather than inside a slide.
- [x] T044 [P] Update `quickstart.md` §"Automated coverage" table if any `TS-*` → test mapping shifted during implementation.
- [x] T045 Run `./mvnw verify` from the repo root (Principle XI). All new backend tests (T007–T010, T029, T030) must be green.
- [x] T046 Run `bun --cwd frontends/slidev-component test` and `bun --cwd frontends/slidev-component run build`. All new composable + component + PollResults tests must be green.
- [x] T047 Run `bun --cwd frontends/slidev-component run e2e` — `slidev-results.spec.ts` auth scenarios (T031, T032) must be green.
- [ ] T048 Run the `quickstart.md` walkthrough §1–§4 against a live dev stack; record any divergence between the manual steps and the automated coverage.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — can start immediately.
- **Foundational (Phase 2)**: depends on Phase 1; BLOCKS both P1 stories because both depend on the shared type surface.
- **User Story 1 (Phase 3)**: depends on Phase 2. Delivers the composable body, the control, the overlay mount, and the backend `GET /api/deck/auth/me`.
- **User Story 2 (Phase 4)**: depends on Phase 3 — needs `useDeckAuth()` runtime (T022) to gate `<PollResults>` and the `markRevoked()` hook to flip status on a 401 from the activation POST.
- **User Story 3 (Phase 5)**: depends on Phase 4 — validates that after the US2 prop removal, the read-only path still meets FR-010 / FR-011 / FR-013.
- **Polish (Phase 6)**: depends on all of the above.

### Task-Level Dependencies Inside a Story

- All `DeckAuthControllerTest` cases (T007–T010) block T021; T021 unblocks them.
- All `useDeckAuth.test.ts` cases (T011–T015) block T022.
- All `DeckAuthControl.test.ts` cases (T016–T020) block T023; T023 depends on T022.
- T024 depends on T023.
- `PollResults.test.ts` rewrites (T025–T028) block T033–T036; T033 depends on T022 and T034.
- e2e tasks T031 / T032 depend on T024 + T033 (full stack must be in place to drive the flow end-to-end).

### Parallel Opportunities

- T002, T003, T004 run in parallel after T001.
- T006 runs in parallel with T005.
- Within US1, every test task marked `[P]` (T007 through T020) runs in parallel — they touch distinct files or independent test cases.
- Within US2, every test task marked `[P]` (T025 through T032) runs in parallel.
- Within US3, every test task marked `[P]` (T037 through T041) runs in parallel.
- Across stories: once Phase 3 implementation lands, no further cross-story parallelism because US2 modifies files US1 created (PollResults gating consumes the composable from US1); US3 is an assertion layer on top of US2.

### Suggested Parallel Batches

- **Batch 1 (Phase 1)**: T002 ∥ T003 ∥ T004 after T001.
- **Batch 2 (Phase 2)**: T005 ∥ T006.
- **Batch 3 (US1 tests)**: T007 ∥ T008 ∥ T009 ∥ T010 ∥ T011 ∥ T012 ∥ T013 ∥ T014 ∥ T015 ∥ T016 ∥ T017 ∥ T018 ∥ T019 ∥ T020.
- **Batch 4 (US1 impl)**: T021 ∥ T022 ∥ T023 (three files, independent) → T024 depends on T023.
- **Batch 5 (US2 tests)**: T025 ∥ T026 ∥ T027 ∥ T028 ∥ T029 ∥ T030 ∥ T031 ∥ T032.
- **Batch 6 (US2 impl)**: T033 → T034 ∥ T035 ∥ T036.
- **Batch 7 (US3)**: T037 ∥ T038 ∥ T039 ∥ T040 ∥ T041 → T042.
- **Batch 8 (Polish)**: T043 ∥ T044 → T045 ∥ T046 → T047 → T048.

---

## MVP Scope Suggestion

The defect-closing MVP is **Phase 1 + Phase 2 + Phase 3 (US1) + Phase 4 (US2)**. With both P1 stories landed, the live-talk hijack defect is closed and the presenter has a working in-deck sign-in. Phase 5 (US3) is a P2 conformance sweep; Phase 6 is polish. The feature can legitimately ship after Batch 6 is green.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps the task to a specific user story for traceability; Setup / Foundational / Polish tasks carry no story label.
- Every test task references its source spec IDs from `tests/features/*.feature`.
- Constitution Principle III mandates that each test task runs red before its paired implementation task; do not merge an implementation task without evidence that its tests failed first.
- Principle VII: `.feature` scenarios are spec artefacts; the tests above are plain Vitest / JUnit assertions, with the Given/When/Then mirrored as comments above each assertion block.
- Principle XI: all backend test / verify invocations are reactor-wide or `-pl poll-api -am`. No `install`.
- Auto-commit after each task per the implement skill; the pre-commit hook enforces assertion-integrity hashes — do not bypass.

## Bug Fix Tasks

- [x] T-B001 [BUG-001] Implement fix for BUG-001 referencing test spec bugfix_BUG-001.feature: register a `custom-nav-controls.vue` in `frontends/slidev-component/` that renders a "sign in" button opening a popover with the token input (signed-in pill + sign-out when authenticated); delete `global-top.vue` so no auth UI remains on the slide canvas
- [x] T-B002 [BUG-001] Verify fix passes test bugfix_BUG-001.feature for BUG-001: auth control renders inside Slidev's nav bar in both SPA and presenter modes, no auth input painted on the slide canvas, nav auto-hide during play is accepted (hover/focus reveals the control)
- [ ] T-B003 [BUG-002] Implement fix for BUG-002 referencing test spec bugfix_BUG-002.feature: replace the single-token input in `frontends/slidev-component/components/DeckAuthControl.vue` with a login+password form; wire `useDeckAuth` / the deck auth API to the admin UI credential flow so deck sign-in uses the same username+password mechanism as `/admin`
- [ ] T-B004 [BUG-002] Verify fix passes test bugfix_BUG-002.feature for BUG-002: deck auth popover renders login and password inputs, valid admin-equivalent credentials authenticate the deck, no opaque "deck token" input remains
- [ ] T-B005 [BUG-003] Implement fix for BUG-003 referencing test spec bugfix_BUG-003.feature: in `frontends/slidev-component/components/CustomNavControls.vue` flip the popover anchor to open upward (`bottom: calc(100% + …)` / equivalent) so the popover stays inside the viewport when the Slidev nav bar is at the bottom of the window
- [ ] T-B006 [BUG-003] Verify fix passes test bugfix_BUG-003.feature for BUG-003: popover opens upward from the nav bar trigger, no content clipped below the viewport edge in SPA and presenter modes
- [ ] T-B007 [BUG-004] Implement fix for BUG-004 referencing test spec bugfix_BUG-004.feature: identify the component/slot painting the TOC overlay on the slide canvas (candidates: residual `global-top.vue`/`global-bottom.vue`, stray addon export, Slidev side-nav default-open config in `slidev-demo`) and remove/reconfigure it so the slide canvas shows only authored content
- [ ] T-B008 [BUG-004] Verify fix passes test bugfix_BUG-004.feature for BUG-004: no TOC list is rendered over the slide canvas on first load in SPA and presenter modes; the Slidev menu/TOC remains reachable only via its explicit toggle

## Clarifications

### Session 2026-04-22

- Q: Which Slidev slot hosts the auth control after the BUG-001 fix? -> A: `custom-nav-controls.vue` (Slidev nav bar, SPA + presenter) [T-B001, T-B002]
- Q: Nav bar auto-hides during play mode — how resolve FR-001 "reachable from every slide"? -> A: Accept nav auto-hide; presenter signs in pre-show, hover reveals nav mid-play [T-B001, T-B002]
- Q: In-toolbar affordance for sign-in? -> A: Button "sign in" opens small popover with token input [T-B001]
