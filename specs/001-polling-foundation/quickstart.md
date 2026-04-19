# Quickstart: Core Polling Platform

Walkthrough that a developer or reviewer runs to exercise the three
user stories against a locally running stack. Doubles as the manual
smoke checklist before any implementation task is marked done.

## Prerequisites

- JDK 21, Node.js 20 LTS, pnpm 9, Docker (for Testcontainers and for
  running PostgreSQL locally).

## 1. Bring up the stack

```bash
# Terminal 1 — backend
cd backend
./gradlew bootRun            # starts Spring Boot on :8080, applies Flyway migrations

# Terminal 2 — respondent app
cd frontend
pnpm install
pnpm --filter respondent-app dev      # Vite dev server on :5173

# Terminal 3 — demo Slidev deck consuming the addon
cd examples/demo-deck
pnpm install
pnpm dev                             # Slidev on :3030, addon auto-loaded
```

Seed data (one presenter + one two-question poll) is inserted by a
`dev` Flyway migration.

## 2. Story 1 — Presenter authors and controls a poll

1. Open `http://localhost:8080/backoffice` in a browser.
2. Verify unauthenticated access is refused (redirect to sign-in page,
   no poll data visible). → SC-005, FR-001.
3. Sign in as the seeded presenter.
4. Create a poll `"Quickstart demo"` with two questions (Q1, Q2), each
   with two options.
5. Mark Q1 active. Confirm the backoffice shows Q1 as `ACTIVE` and Q2
   as `DRAFT`.
6. Mark Q2 active. Confirm Q1 becomes `CLOSED` in the same view
   refresh. → FR-004.
7. Copy the join link and QR image from the poll detail page.

## 3. Story 2 — Audience votes anonymously

1. Open the join link from Story 1 in a **private / incognito** window
   (new device session).
2. Confirm the active question loads with no sign-in prompt. Measure
   first-meaningful-paint; expect well under 3 s on a fresh cache.
   → SC-001, FR-007, FR-008.
3. Submit an answer. Observe the confirmation state appear in under 5
   s end-to-end. → SC-002, FR-009.
4. Attempt to submit again from the same window — UI prevents it; a
   direct API call is rejected with a structured "already voted"
   response. → FR-009.
5. In the backoffice, close the active question. Then try to vote from
   a fresh incognito window on the same join link — UI shows "waiting
   for the next question". → FR-008, FR-010.
6. Scan the QR image with a phone camera; confirm it resolves to the
   same join URL. → FR-005.

## 4. Story 3 — Slidev slide shows live results

1. Open the Slidev deck (`http://localhost:3030`) and navigate to the
   slide that embeds `<PollResults pollId="..." />`.
2. Reopen the active question in the backoffice (create and activate a
   new question for the same poll).
3. From two or three incognito windows, vote. Confirm each vote
   appears on the Slidev slide within 2 s of submission. → SC-003,
   FR-013.
4. In the backoffice, activate a different question. Confirm the
   Slidev slide swaps to the new question's title and aggregate
   without manual refresh. → FR-014.
5. Stop the backend (`Ctrl-C` Terminal 1). Confirm the Slidev slide
   displays a visible but non-blocking "live updates paused" badge,
   does **not** crash the deck, and deck navigation still works.
   → FR-015, SC-006.
6. Restart the backend. Confirm the badge clears and updates resume
   automatically within 10 s. → SC-006.

## 5. Acceptance mapping

| Story | Quickstart step | Spec references |
|-------|------------------|-----------------|
| S1    | §2 steps 1–7     | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-016, SC-005 |
| S2    | §3 steps 1–6     | FR-007, FR-008, FR-009, FR-010, FR-011, SC-001, SC-002, SC-007 |
| S3    | §4 steps 1–6     | FR-012, FR-013, FR-014, FR-015, SC-003, SC-004, SC-006 |
