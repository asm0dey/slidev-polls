# Phase 1 Quickstart: Presenter Auth Gating in Slidev Deck

Walkthrough that exercises every user story end-to-end against a
running local stack. Assumes feature 001 is already functional
(backend + both SPAs + addon build pipeline).

## Prerequisites

1. Postgres running (`docker compose up -d postgres`).
2. Backend running: `./mvnw spring-boot:run -pl poll-api -am`.
3. Frontends built and copied into the backend static dir, or the
   Slidev dev server running against the addon workspace:
   `cd frontends/slidev-component && bun run slidev ../../examples/demo-deck.md`
   (any deck that uses `<PollResults>` on a slide).
4. One presenter account seeded (default dev account `alice` /
   `correct-horse` from 001's `TestPollApiApplication`).

## 0. Mint a deck token

```bash
# Sign in as the presenter and grab a deck token for the demo poll.
curl -c /tmp/c.jar -X POST http://localhost:8080/api/admin/login \
  -H 'content-type: application/json' \
  -d '{"username":"alice","password":"correct-horse"}'

XSRF=$(grep XSRF-TOKEN /tmp/c.jar | awk '{print $NF}')

# (Assume a poll with id $POLL_ID already exists.)
curl -b /tmp/c.jar -X POST \
  "http://localhost:8080/api/admin/polls/$POLL_ID/deck-tokens" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H 'content-type: application/json' \
  -d '{"label":"demo-deck"}'
# → { id, pollId, label, plaintext: "<TOKEN>" , ... }
```

Copy the `plaintext` value — this is the bearer the presenter will
paste into the in-deck auth control.

## 1. Story 1 — Presenter signs in inside the deck (P1)

1. Open the deck URL in a fresh browser profile (private window,
   empty `localStorage`).
2. In the top-overlay tool strip added by the addon, click the
   "🔒 Sign in" button.
3. Paste the `plaintext` value from step 0 and submit.
4. **Expect**: the control transitions to a "✅ Signed in" pill
   carrying the token's label (FR-002, FR-003).
5. Navigate to a slide that hosts `<PollResults :slug="…"
   :pollId="$POLL_ID" :questionId="…" />`.
6. **Expect**: within the SC-003 live-update budget, the backend's
   active question for that poll matches `questionId`. Verify via:
   ```bash
   curl -s http://localhost:8080/api/polls/by-slug/$SLUG | jq '.activeQuestion.id'
   ```
7. Reload the tab.
8. **Expect**: the control re-renders "Signed in" without
   re-prompting (SC-003). Verify the network panel shows exactly
   one `GET /api/deck/auth/me` call returning 200.

## 2. Story 2 — Unauthenticated viewer cannot hijack (P1)

1. Open the deck URL in a second browser profile with no prior
   auth state.
2. Navigate rapidly across every poll slide in the deck.
3. **Expect**: zero `POST /api/deck/polls/*/activate` calls appear
   in that browser's network panel (FR-007). The backend's active
   question is still whatever Story 1's signed-in browser last
   navigated to — regardless of what slide the unauthenticated
   browser is on.
4. **Revocation variant**: from the backoffice, revoke the deck
   token (`DELETE /api/admin/polls/$POLL_ID/deck-tokens/$TOKEN_ID`).
   Then, in the Story 1 browser, navigate to a different poll
   slide.
5. **Expect**: the activation POST returns 401 with
   `Problem{code: DECK_TOKEN_INVALID}`; the auth control flips to
   "not signed in" and surfaces the FR-014 authentication-failure
   message; the active question on the poll is unchanged by that
   navigation (SC-005).

## 3. Story 3 — Unauthenticated viewer sees live results (P2)

1. While Story 1's signed-in browser has activated a question on
   the poll, fire a few votes at the voter slug URL so tallies
   exist:
   ```bash
   curl -X POST http://localhost:8080/api/polls/$SLUG/votes \
     -H 'content-type: application/json' \
     -d "{\"optionId\":\"$OPT\",\"voterToken\":\"$(uuidgen)\"}"
   ```
2. In Story 2's unauthenticated browser, reach the same poll
   slide.
3. **Expect**: the `<PollResults>` component renders the current
   tally and updates live within the SC-003 budget as new votes
   arrive; no presenter-only controls are visible (FR-010, FR-012,
   SC-004).
4. Simulate live-update loss: kill the SSE connection in devtools.
5. **Expect**: the unobtrusive "live updates paused" indicator
   appears (FR-013); the slide remains navigable; on restore, the
   indicator disappears and tallies resume.

## 4. Edge-case sanity checks

- **Invalid token on sign-in**: pasting garbage produces a "credential
  not recognised" message on the auth control; no `localStorage`
  entry is written; the next navigation still fires zero activation
  calls.
- **Sign-out mid-talk on a poll slide**: clicking "Sign out" flips
  the control to "not signed in" immediately; the current active
  question on the poll is NOT changed by the sign-out action
  itself. Subsequent navigation is read-only.
- **Two browsers signed in simultaneously**: both count as
  presenter; the last navigation wins on the backend (001 FR-004
  atomic activation).
- **Deck export (`slidev export`)**: the auth control is visible in
  the export but inert; no network call is attempted — matches 001
  Principle IV posture.

## Divergence notes (T048 walkthrough, 2026-05-09)

Run with backend on `:8080`, slidev-demo dev server on `:3030`,
`@slidev/cli@52.15.1`. Steps below diverge from the manual script:

- §1 step 2: "top-overlay tool strip added by the addon" predates the
  BUG-001 fix. The auth control now lives in Slidev's nav bar via
  `custom-nav-controls.vue` — bottom of the viewport, auto-hidden in
  SPA mode (hover/focus reveals it), always visible in presenter
  mode. There is no top-overlay strip on the slide canvas.
- §1 step 2: the trigger reads "sign in" (no emoji prefix), not
  "🔒 Sign in".
- §1 step 3: BUG-002 replaced the opaque-token paste with a
  login + password form. The popover now takes admin-equivalent
  credentials and POSTs them to `/api/deck/auth/login`; the backend
  mints a fresh deck token internally and returns
  `{token, tokenId, pollId, label}`. Step 0's
  `/api/admin/polls/$POLL_ID/deck-tokens` mint flow still works and
  remains useful for tooling (`scripts/seed.sh` uses it), but is no
  longer the presenter-facing path.
- §1 step 4: the signed-in pill renders the token's label only
  (no "✅" emoji prefix), confirmed by `CustomNavControls.vue`.
- §3 in-browser verification of the read-only path could not be
  completed from the slidev-demo dev server: `data.ts` hardcodes
  `pollServer = "http://localhost:8080"` while the deck is served
  from `:3030`, and the backend has no CORS configuration, so
  EventSource is silently blocked and `<PollResults>` shows
  "live updates paused / Waiting for the next question…" even when
  the backend has an active question. The backend SSE stream itself
  is healthy (verified with `curl -N` against
  `/api/polls/{slug}/stream`). Production deployments serve the
  slidev export from the backend origin (same-origin) and the
  `task up` flow exposes everything on `:8080`, so neither is
  affected. A per-poll `allowedOrigins` configuration to fix the
  cross-origin dev/GitHub-Pages case is planned as a separate
  feature (proposed as `003-per-poll-cors-allowlist`).
- §2 backoffice token revocation flow was not exercised in this
  walkthrough; the activation 401 → revoked transition is covered
  by `useDeckAuth.test.ts` and `DeckAuthControllerTest`.
- Backend activate / deactivate / anonymous-block path verified
  end-to-end against the seeded demo poll via curl:
  `POST /api/deck/auth/login` mints a deck token; signed-in
  `POST /api/deck/polls/{pollId}/activate` flips
  `activeQuestion` between Q1 and Q2; anonymous activate (no
  `X-Deck-Token` header) returns `401 DECK_TOKEN_INVALID` and
  leaves `activeQuestion` unchanged; anonymous
  `GET /api/polls/by-slug/{slug}` returns the full state with
  options and tallies (read-only path unbroken).

## Automated coverage

| Story | Automated assertion |
|-------|---------------------|
| 1 | `DeckAuthControl.test.ts` + `useDeckAuth.test.ts` (verify flow, persistence) |
| 1 scenario 3 | `PollResults.test.ts` — signed-in state fires activation POST |
| 2 | `PollResults.test.ts` — anonymous state produces zero activation POSTs; `slidev-results.spec.ts` e2e asserts the same over a real browser |
| 2 revocation | `useDeckAuth.test.ts` — `markRevoked()` clears storage; `DeckAuthControllerTest` — revoked bearer returns 401 `DECK_TOKEN_INVALID` |
| 3 | Existing `PollResults.test.ts` SSE scenarios remain green — the read-only path is unchanged code |
