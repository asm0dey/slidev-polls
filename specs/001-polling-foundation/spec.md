# Feature Specification: Core Polling Platform

**Feature Branch**: `001-polling-foundation`
**Created**: 2026-04-19
**Status**: Draft
**Input**: User description: "create a structure of the project … Backoffice
only for authenticated users; Polling frontend: non-authenticated users
should be able to open poll (by link or generated QR code) and immediately
see the currently active question of the poll they opened, and vote
anonymously; slidev frontend: should be able to show the results of
current question in realtime"

## User Stories *(mandatory)*

### User Story 1 - Presenter authors and controls a poll (Priority: P1)

A presenter signs in to a private backoffice and creates a poll composed
of one or more questions. They can edit questions, choose which question
is currently active, close a question to stop accepting responses, and
obtain a shareable join link and QR code for the poll. The backoffice is
reachable only after authentication; anonymous visitors are redirected
away.

**Why this priority**: Nothing else works without data. Polls must exist
and have a controllable "currently active" question before audience
voting or live results can be tested end-to-end.

**Independent Test**: Signed-in presenter creates a poll with two
questions, marks one active, retrieves the join link and QR code,
closes the question, and verifies an unauthenticated visitor cannot
reach any backoffice view. All without involving audience or Slidev.

**Acceptance Scenarios**:

1. **Given** a valid presenter account, **When** they sign in and create
   a poll with at least one question, **Then** the poll is persisted and
   appears in their poll list with its join link and QR code available.
2. **Given** a poll with multiple questions, **When** the presenter marks
   one question as the active question, **Then** the system records
   exactly that question as active and the previous active question (if
   any) is marked closed.
3. **Given** an unauthenticated visitor, **When** they attempt to open a
   backoffice URL, **Then** they are not shown any backoffice data and
   are redirected to an authentication entry point.
4. **Given** an authenticated presenter, **When** they close the active
   question, **Then** no question is active on that poll and subsequent
   vote submissions against that question are rejected.

---

### User Story 2 - Audience votes anonymously via link or QR (Priority: P2)

An audience member follows a shared link or scans a QR code from a
presenter's slide. Without signing up, creating an account, or
installing anything, they immediately see the poll's currently active
question and the available options, submit a single response, and
receive confirmation that their vote was counted. If no question is
currently active, they see a neutral "waiting" state rather than an
error.

**Why this priority**: This is the audience-facing core of the product.
It delivers the primary user value — capturing live audience input —
and is the path most observers of the product will actually exercise.

**Independent Test**: With a poll (seeded by Story 1 or test fixtures)
that has an active question, an anonymous client opens the join link,
sees the current question and options, submits a vote, and receives
acknowledgement — all without authentication and without leaving the
page.

**Acceptance Scenarios**:

1. **Given** a poll with a currently active question, **When** an
   anonymous visitor opens the poll join link, **Then** the active
   question and its options are displayed within an acceptable time
   budget (see SC-001) without any authentication prompt.
2. **Given** an audience member viewing the active question, **When**
   they select an option and submit, **Then** the system accepts the
   response and shows confirmation that their vote was recorded.
3. **Given** a poll whose active question has been closed between page
   load and submission, **When** the audience member submits, **Then**
   the system rejects the vote with a clear, non-technical message and
   offers to refresh.
4. **Given** a poll with no currently active question, **When** an
   anonymous visitor opens the join link, **Then** the page shows a
   neutral "waiting for the next question" state rather than an error.
5. **Given** a QR code generated for a poll, **When** a camera scans it,
   **Then** it resolves to the same join destination as the shared link.

---

### User Story 3 - Slidev slide shows live results (Priority: P3)

While presenting, the presenter's Slidev slide embeds a component that
displays the aggregated results of the currently active question and
updates as new responses arrive, without the presenter taking any
action on the slide. When the active question changes, the slide
content changes to reflect the new question.

**Why this priority**: Closes the loop between audience input and the
talk itself, which is the differentiator of this product. Story 1 and
Story 2 together already let a presenter run a poll; Story 3 makes the
experience feel live inside the deck.

**Independent Test**: With a poll (seeded) and a running active
question receiving simulated votes, open a Slidev deck containing the
results component; observe the tallies change on-screen as votes
arrive, and observe the display swap when the active question is
changed in the backoffice.

**Acceptance Scenarios**:

1. **Given** a Slidev slide embedding the results view for a poll,
   **When** a new response is recorded for the active question,
   **Then** the displayed aggregate reflects the new response within
   the live-update budget (see SC-003) without manual refresh.
2. **Given** the results view is visible on-slide, **When** the
   presenter switches the active question in the backoffice, **Then**
   the Slidev view swaps to the new question's title and starts
   displaying its aggregate.
3. **Given** the backend is temporarily unreachable, **When** the
   Slidev view cannot fetch updates, **Then** the slide does not crash
   or freeze the deck; it shows a small, unobtrusive "live updates
   paused" indicator and resumes automatically when connectivity
   returns.

---

### Edge Cases

- What happens when a respondent submits multiple times in quick
  succession for the same active question from the same device?
- What happens when the presenter deletes a poll while anonymous
  viewers are currently on its join page?
- What happens when the active question is changed while a respondent
  is mid-selection but has not yet submitted?
- What happens to the Slidev results view if the presenter pauses and
  resumes the deck, or navigates away and back to the slide?
- What happens when a QR code is scanned after the poll's session has
  ended?
- What happens when multiple presenters are signed in and one edits a
  poll another presenter is actively running? (Out of scope for this
  feature — single-presenter-per-poll assumed; flagged for future
  work.)

## Requirements *(mandatory)*

### Functional Requirements

**Backoffice (authenticated presenter)**

- **FR-001**: The backoffice MUST be accessible only to authenticated
  users; every backoffice view and action MUST require a valid
  authenticated session.
- **FR-002**: Authenticated presenters MUST be able to create, edit,
  and delete polls they own.
- **FR-003**: A poll MUST support one or more questions, each with a
  question text and a set of answer options.
- **FR-004**: At any moment, a poll MUST have at most one "active"
  question; activating a question MUST atomically close any currently
  active question on that poll.
- **FR-005**: The system MUST provide each poll with a stable join
  link and a QR code that resolves to the same join destination.
- **FR-006**: Presenters MUST be able to close the active question
  explicitly, after which further vote submissions for that question
  MUST be rejected.

**Audience polling (anonymous respondent)**

- **FR-007**: The join link and QR code MUST lead directly to the
  poll's current state without any authentication, signup, or app
  install step.
- **FR-008**: On opening a poll join destination, the respondent MUST
  immediately see the currently active question and its options, or a
  neutral "waiting" state if no question is active.
- **FR-009**: Respondents MUST be able to submit exactly one response
  per active question per device session on a best-effort basis, and
  MUST receive explicit confirmation when a submission is accepted.
- **FR-010**: The system MUST reject submissions for questions that
  are no longer active and MUST communicate the rejection reason in
  user-facing, non-technical language.
- **FR-011**: Respondents MUST NOT be required to disclose any
  personal information to vote.

**Slidev live results**

- **FR-012**: The system MUST expose a Slidev-embeddable view that
  displays the aggregate of responses for a given poll's currently
  active question.
- **FR-013**: The Slidev view MUST update to reflect new responses in
  near real time without requiring manual refresh by the presenter.
- **FR-014**: When the poll's active question changes, the Slidev view
  MUST swap to the new question's aggregate automatically.
- **FR-015**: If the live-update channel becomes temporarily
  unavailable, the Slidev view MUST NOT crash the deck, MUST surface
  a visible "live updates paused" indicator, and MUST resume updates
  when connectivity is restored.

**Cross-cutting**

- **FR-016**: The system MUST prevent anonymous (non-presenter)
  traffic from reading or modifying any backoffice-only resource.
- **FR-017**: All failure paths visible to presenters MUST distinguish
  authentication failure, authorisation failure, and transport
  failure in their messages.

### Key Entities *(include if feature involves data)*

- **Presenter**: An account with credentials, authorised to sign in to
  the backoffice and own polls. Identity is meaningful; display name
  and authentication identity are persisted.
- **Poll**: A collection owned by exactly one presenter, bearing a
  human-readable title, a stable join identifier used for the link
  and QR code, an ordered set of questions, and an "active question"
  pointer (zero or one).
- **Question**: Belongs to exactly one poll, has a prompt text, a type
  (e.g., single-choice), a set of answer options, and a lifecycle
  state (draft, active, closed). At most one question per poll may be
  active at a time.
- **Option**: An answer choice belonging to exactly one question; has
  display text and stable ordering within its question.
- **Response**: An anonymous vote for exactly one option of an
  active-at-the-time question, associated with a device-session
  identifier (not a user identity) and a timestamp.
- **Session** (device, not user): A lightweight respondent-side
  identifier used only for best-effort single-vote enforcement; does
  not carry personal data and is not linkable to any presenter-visible
  identity.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time audience member can go from opening the
  join link (or QR scan) to seeing the active question in under 3
  seconds on a typical mobile connection, with no intermediate
  authentication prompt.
- **SC-002**: An audience member can go from "sees active question"
  to "vote submitted and confirmed" in under 5 seconds end-to-end
  under normal load.
- **SC-003**: A new response is reflected in the Slidev live-results
  view within 2 seconds of being accepted by the backend under
  normal load.
- **SC-004**: The system sustains at least 200 concurrent respondents
  voting on one active question without degradation of SC-001,
  SC-002, or SC-003.
- **SC-005**: Zero backoffice endpoints return protected data to an
  unauthenticated caller (verified by automated test across every
  backoffice route).
- **SC-006**: A simulated loss of live-update connectivity does not
  crash, freeze, or block slide navigation in the Slidev deck; the
  deck remains fully usable and recovers automatically within 10
  seconds of connectivity returning.
- **SC-007**: A respondent can complete a vote without entering any
  personally identifying information — zero required PII fields on
  the respondent path.
