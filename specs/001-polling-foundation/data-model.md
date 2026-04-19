# Phase 1 Data Model: Core Polling Platform

Entities are derived directly from the spec's Key Entities section and
from the functional requirements that constrain their lifecycles.
All identifiers are server-assigned opaque UUIDv7 values unless noted.

## Entities

### Presenter

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `username` | string, unique, 3–64 chars | Used for sign-in |
| `display_name` | string, 1–120 chars | Shown in backoffice UI |
| `password_hash` | string | BCrypt hash; never returned by API |
| `created_at` | timestamp | Set on insert |

Relationships:
- `Presenter 1 — * Poll` via `polls.owner_id`.

Validation:
- `username` is case-insensitive unique.
- `password_hash` is required; the raw password never touches storage.

### Poll

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `owner_id` | UUID, FK → `presenters.id` | NOT NULL |
| `title` | string, 1–200 chars | Presenter-visible |
| `join_code` | string, 6–10 chars, unique | Human-friendly slug used in the join URL and QR |
| `active_question_id` | UUID, nullable, FK → `questions.id` | See FR-004 constraint below |
| `created_at` / `updated_at` | timestamp | |

Relationships:
- `Poll 1 — * Question`
- `Poll 1 — 0..1 "active question"` (via `active_question_id`)

Invariants:
- **FR-004**: at most one question per poll may be active at any time.
  Enforced by the storage layer as either (a) a partial unique index on
  `questions(poll_id) WHERE state = 'ACTIVE'`, or (b) the
  `active_question_id` pointer plus a CHECK that the referenced
  question belongs to the same poll. The implementation MUST pick one
  and document it in code; both are acceptable, duplication is not.
- If `active_question_id` is non-null, the referenced row's
  `poll_id` MUST equal this poll's `id` (FK with composite uniqueness).

### Question

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `poll_id` | UUID, FK → `polls.id`, NOT NULL | |
| `prompt` | string, 1–500 chars | Shown to respondents |
| `type` | enum: `SINGLE_CHOICE` | Only value for v1; reserved for future expansion |
| `ordinal` | integer | Presenter-defined order within the poll |
| `state` | enum: `DRAFT` \| `ACTIVE` \| `CLOSED` | See transitions below |
| `activated_at` | timestamp, nullable | Set on the DRAFT → ACTIVE transition |
| `closed_at` | timestamp, nullable | Set on ACTIVE → CLOSED transition |

Relationships:
- `Question 1 — * Option`
- `Question 1 — * Response`

State transitions:

```
DRAFT  ── activate ──▶  ACTIVE  ── close ──▶  CLOSED
                 ▲                              │
                 └──────────── reopen ◀─────────┘   (explicitly disallowed for v1)
```

- `DRAFT → ACTIVE`: allowed only when the parent poll currently has no
  ACTIVE question; the transition MUST atomically set any previously
  ACTIVE question on the same poll to `CLOSED` (FR-004).
- `ACTIVE → CLOSED`: allowed at any time by the owning presenter
  (FR-006). After this transition, new `Response` rows for this
  question MUST be rejected (FR-006, FR-010).
- `CLOSED → *`: not supported in v1; spec does not require reopening.
- `DRAFT → CLOSED`: permitted (presenter may discard a draft
  question).

### Option

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `question_id` | UUID, FK → `questions.id`, NOT NULL | |
| `label` | string, 1–200 chars | Shown to respondents |
| `ordinal` | integer | Stable display order within the question |

Validation:
- A question MUST have at least 2 options before it can transition to
  ACTIVE. Enforced at the service layer on the transition attempt.

### Response

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `question_id` | UUID, FK → `questions.id`, NOT NULL | |
| `option_id` | UUID, FK → `options.id`, NOT NULL | MUST belong to `question_id` |
| `device_session_id` | UUID | Cookie-scoped, anonymous |
| `created_at` | timestamp | |

Invariants:
- **FR-009**: unique constraint on
  `(question_id, device_session_id)` enforces best-effort single-vote
  per device per question. A duplicate submission MUST be detected by
  constraint violation and surfaced as an "already voted" outcome,
  not as a 5xx error.
- Responses MAY only be inserted when `questions.state = 'ACTIVE'`
  (FR-010). Enforced at the service layer inside the same transaction
  that reads the question's state; the `created_at` timestamp serves
  as the tie-breaker against a racing `ACTIVE → CLOSED` transition,
  which MUST roll back any inserts it observes in flight.

### DeviceSession (respondent-side, not a database entity)

A short opaque identifier (UUID) persisted in an `HttpOnly` cookie
(`sp_device`) set on the respondent's first interaction with the
respondent app. Not stored in its own table — it appears only as
`responses.device_session_id`. Carries no PII (SC-007, FR-011).

## Indexes

- `polls(owner_id)` — list "my polls" in the backoffice.
- `polls(join_code)` unique — lookup on the respondent path.
- `questions(poll_id, ordinal)` — ordered render in the backoffice.
- Partial unique index on `questions(poll_id) WHERE state = 'ACTIVE'`
  if that enforcement variant is chosen.
- `responses(question_id, device_session_id)` unique — FR-009.
- `responses(question_id, option_id)` — supports the aggregate
  `GROUP BY option_id` for the live results stream.

## Aggregate projection (derived, not persisted)

The "live aggregate" streamed to Slidev is derived on read:

```sql
SELECT option_id, COUNT(*) AS tally
FROM   responses
WHERE  question_id = :questionId
GROUP  BY option_id;
```

This projection is published by the SSE broadcaster on each accepted
response and on each subscriber connect, so the Slidev view starts
consistent and stays consistent under reconnects (Principle IV,
SC-003, SC-006).
