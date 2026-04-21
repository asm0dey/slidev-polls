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

## Automated coverage

| Story | Automated assertion |
|-------|---------------------|
| 1 | `DeckAuthControl.test.ts` + `useDeckAuth.test.ts` (verify flow, persistence) |
| 1 scenario 3 | `PollResults.test.ts` — signed-in state fires activation POST |
| 2 | `PollResults.test.ts` — anonymous state produces zero activation POSTs; `slidev-results.spec.ts` e2e asserts the same over a real browser |
| 2 revocation | `useDeckAuth.test.ts` — `markRevoked()` clears storage; `DeckAuthControllerTest` — revoked bearer returns 401 `DECK_TOKEN_INVALID` |
| 3 | Existing `PollResults.test.ts` SSE scenarios remain green — the read-only path is unchanged code |
