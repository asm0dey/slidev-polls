# Phase 1 Data Model: Core Polling Platform

Entities are derived from the spec's Key Entities section and from the
functional requirements that constrain their lifecycles. The schema is
owned by Flyway migrations in `poll-persistence/src/main/resources/db/migration/`;
jOOQ types are generated from those migrations at build time.

Identifiers are UUIDv7 unless noted.

## Tables (Flyway-owned)

### `admin_user` (V3)

| Column | Type | Notes |
|--------|------|-------|
| `username` | `text` primary key | Used for sign-in; case-insensitive unique (stored lowercase) |
| `display_name` | `text` | Shown in backoffice UI |
| `bcrypt_hash` | `text` | BCrypt of the password; never returned by API |
| `created_at` | `timestamptz` default `now()` | |

Notes:
- Seeded by migration / admin CLI; no self-serve sign-up in v1.

### `polls` (V1, V2)

| Column | Type | Notes |
|--------|------|-------|
| `id` | `uuid` primary key | UUIDv7 |
| `owner_username` | `text` references `admin_user(username)` | Set on insert; immutable |
| `title` | `text` NOT NULL, 1–200 chars | Presenter-visible |
| `slug` | `text` NOT NULL | Human-memorable URL segment; see rules below |
| `status` | `text` NOT NULL check in `('DRAFT','OPEN','CLOSED')` | Poll-level lifecycle |
| `style` | `jsonb` NOT NULL default `'{}'` | Theme overrides (colors, font, layout) |
| `active_question_id` | `uuid` nullable, references `poll_questions(id)` | See FR-004 invariant below |
| `created_at` / `updated_at` | `timestamptz` | |

Indexes / constraints:
- `CREATE UNIQUE INDEX polls_slug_lower_uq ON polls (lower(slug));` (V2)
- `CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(slug) BETWEEN 3 AND 40)`
- Reserved-slug rejection is enforced in `poll-core/ReservedSlugs`
  before insert/update (validation error surfaces as
  `Problem.code = SLUG_RESERVED`).
- FK to `poll_questions` uses `DEFERRABLE INITIALLY DEFERRED` so that
  creating a poll and its first question in one transaction does not
  chicken-and-egg.

### `poll_questions` (V1)

| Column | Type | Notes |
|--------|------|-------|
| `id` | `uuid` primary key | |
| `poll_id` | `uuid` references `polls(id)` ON DELETE CASCADE | |
| `prompt` | `text` NOT NULL, 1–500 chars | |
| `type` | `text` NOT NULL default `'SINGLE_CHOICE'` | Only value in v1 |
| `ordinal` | `int` NOT NULL | Presenter-defined order within the poll |
| `status` | `text` NOT NULL check in `('DRAFT','ACTIVE','CLOSED')` | |
| `activated_at` | `timestamptz` nullable | Set on DRAFT → ACTIVE |
| `closed_at` | `timestamptz` nullable | Set on ACTIVE → CLOSED |

State transitions:

```
DRAFT  ── activate ──▶  ACTIVE  ── close ──▶  CLOSED
                 ▲
                 └─ reopen: NOT SUPPORTED in v1
```

- `DRAFT → ACTIVE`: allowed only when the parent poll has no other
  ACTIVE question. The transition MUST atomically close any
  previously ACTIVE question on the same poll (FR-004).
- `ACTIVE → CLOSED`: allowed at any time by the owning presenter
  (FR-006). After this, further vote inserts for this question MUST
  fail (FR-006, FR-010).
- `DRAFT → CLOSED`: permitted.

Indexes / constraints:
- **FR-004 invariant** (storage-level): partial unique index
  `CREATE UNIQUE INDEX poll_questions_one_active_uq
    ON poll_questions (poll_id) WHERE status = 'ACTIVE';`
- `(poll_id, ordinal)` index for ordered render.
- `CHECK ((status = 'ACTIVE') = (activated_at IS NOT NULL))` — status
  and timestamp cannot drift.

### `poll_options` (V1)

| Column | Type | Notes |
|--------|------|-------|
| `id` | `uuid` primary key | |
| `question_id` | `uuid` references `poll_questions(id)` ON DELETE CASCADE | |
| `label` | `text` NOT NULL, 1–200 chars | |
| `position` | `int` NOT NULL | Stable display order within the question |

Validation (service layer):
- A question MUST have at least 2 options before it can transition
  to ACTIVE. Enforced in `PollService.activate`; surfaces as
  `Problem.code = ACTIVATION_REJECTED`.

### `votes` (V1)

| Column | Type | Notes |
|--------|------|-------|
| `id` | `uuid` primary key | |
| `poll_id` | `uuid` references `polls(id)` ON DELETE CASCADE | denormalised for cheap SSE fan-out by poll |
| `question_id` | `uuid` references `poll_questions(id)` ON DELETE CASCADE | |
| `option_id` | `uuid` references `poll_options(id)` ON DELETE CASCADE | MUST belong to `question_id` |
| `voter_token` | `text` NOT NULL | Client-generated UUID, cookie-mirrored |
| `created_at` | `timestamptz` default `now()` | |

Indexes / constraints:
- **FR-009** uniqueness: `UNIQUE (question_id, voter_token)`.
  Duplicate inserts surface as `Problem.code = ALREADY_VOTED`.
- `(question_id, option_id)` index for the live-aggregate
  `GROUP BY option_id` query.
- Service layer enforces that `poll_questions.status = 'ACTIVE'` at
  insert time inside the same transaction (FR-010); a concurrent
  `ACTIVE → CLOSED` transition MUST roll back any in-flight inserts
  it observes, surfacing `Problem.code = QUESTION_NOT_ACTIVE`.

## Voter token (not a table)

- A UUID generated client-side on first visit to the voter SPA.
- Persisted in `localStorage` and mirrored to an `HttpOnly`,
  `Secure`, `SameSite=Lax` cookie `sp_voter`.
- Carries no PII (FR-011, SC-007).
- Appears only as `votes.voter_token`.

## Aggregate projection (derived, not persisted)

The live aggregate streamed to Slidev / voter views is derived on
read:

```sql
SELECT option_id, COUNT(*) AS tally
FROM   votes
WHERE  question_id = :questionId
GROUP  BY option_id;
```

`TallyBroadcaster` (in `poll-realtime`) listens for
`VoteCastEvent` (emitted by `VoteService` after a successful insert)
and fans a `tally` delta out through `SseHub`. On subscribe and on
active-question change, the hub re-emits a full `snapshot` so clients
start consistent and stay consistent under reconnects (Principle IV,
SC-003, SC-006).
