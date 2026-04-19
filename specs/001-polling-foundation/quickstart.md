# Quickstart: Core Polling Platform

Walkthrough that a developer or reviewer runs to exercise the three
user stories against a locally running stack. Doubles as the manual
smoke checklist before any implementation task is marked done.

## Prerequisites

- JDK 25, Docker (for local Postgres and for Testcontainers), **bun**
  (the only frontend toolchain the project expects on `PATH`).

## 1. Bring up the stack

```bash
# Start Postgres (docker-compose.yml defines postgres:16 on :5432).
docker compose up -d postgres

# Build the frontends and copy their bundles into the backend's
# static resources. Also runs `bun install` on first run.
./scripts/build-frontends.sh

# Run the backend; Flyway applies migrations on startup. Serves
# both SPAs and the API on http://localhost:8080.
./mvnw -pl backend/poll-api spring-boot:run
```

For an inner-loop developer experience, `./scripts/dev.sh` starts
Postgres, the backend, and the voter / backoffice Vite dev servers
concurrently. (The dev servers proxy `/api/**` to the backend.)

Seed data (one presenter + one two-question poll with slug
`quickstart-demo`) is inserted by a `dev`-profile Flyway migration.

## 2. Story 1 — Presenter authors and controls a poll

1. Open `http://localhost:8080/admin/` in a browser.
2. Verify unauthenticated access is refused: backoffice shell loads
   but any API call (e.g., poll list) returns `AUTH_REQUIRED` and
   the UI redirects to the sign-in page. → SC-005, FR-001.
3. Sign in as the seeded presenter.
4. Create a poll `"Quickstart demo"` with two questions (Q1, Q2),
   each with two options. Leave the slug field blank; confirm the
   server-derived slug is `quickstart-demo`.
5. Try to change the slug to `admin`; confirm the UI rejects it
   client-side (`SLUG_RESERVED`) and the server would reject it if
   submitted.
6. Rename the slug to `my-talk`; confirm `http://localhost:8080/my-talk`
   now loads the voter SPA.
7. Activate Q1. Confirm the backoffice shows Q1 as `ACTIVE` and Q2 as
   `DRAFT`.
8. Activate Q2. Confirm Q1 becomes `CLOSED` in the same refresh.
   → FR-004.
9. Open the poll's QR image at `/api/admin/polls/{id}/qr.png`.
   Confirm it scans to the `/my-talk` URL.

## 3. Story 2 — Audience votes anonymously

1. Open `http://localhost:8080/my-talk` in a **private / incognito**
   window (fresh device).
2. Confirm the active question loads with no sign-in prompt. Measure
   first-meaningful-paint; expect well under 3 s on a fresh cache.
   → SC-001, FR-007, FR-008.
3. Submit an answer. Observe the confirmation state appear in under
   5 s end-to-end. → SC-002, FR-009.
4. Attempt to submit again from the same window. UI prevents it
   (`alreadyVoted` hint); a direct API call receives
   `Problem.code = ALREADY_VOTED`. → FR-009.
5. In the backoffice, close the active question. Then try to vote
   from a fresh incognito window on the same slug: UI shows "waiting
   for the next question"; a forced vote returns
   `Problem.code = QUESTION_NOT_ACTIVE`. → FR-008, FR-010.
6. Scan the QR image with a phone camera; confirm it resolves to the
   same `/my-talk` URL. → FR-005.

## 4. Story 3 — Slidev slide shows live results

1. From the `slidev-component` package, link the addon into a local
   Slidev deck (`examples/demo-deck`) and open the deck; navigate to
   the slide that embeds `<PollResults slug="my-talk" />`.
2. In the backoffice, activate a new question on the `my-talk` poll.
3. From two or three incognito windows, vote. Confirm each vote
   appears on the Slidev slide within 2 s. → SC-003, FR-013.
4. In the backoffice, activate a different question. Confirm the
   Slidev slide swaps to the new question's prompt and aggregate
   without manual refresh. → FR-014.
5. Stop the backend. Confirm the Slidev slide shows a visible but
   non-blocking "live updates paused" badge, does **not** crash the
   deck, and deck navigation still works. → FR-015, SC-006.
6. Restart the backend. Confirm the badge clears and updates resume
   automatically within 10 s. → SC-006.

## 5. Acceptance mapping

| Story | Quickstart step | Spec references |
|-------|------------------|-----------------|
| S1    | §2 steps 1–9     | FR-001..FR-006, FR-016, SC-005 |
| S2    | §3 steps 1–6     | FR-007..FR-011, SC-001, SC-002, SC-007 |
| S3    | §4 steps 1–6     | FR-012..FR-015, SC-003, SC-004, SC-006 |
