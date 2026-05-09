# Feature Specification: Presenter Auth Gating in Slidev Deck

**Feature Branch**: `002-presenter-auth-gating`
**Created**: 2026-04-21
**Status**: Draft
**Input**: User description: "I found an issue with slidev authentication: if anyone opens my presentation during my talk they will switch active questions by just navigating to a slide with a poll. I want to have an authentication button together with other tools in on a slide. If I'm authenticated — then I can actually switch active slides, otherwise I only see rendered results of the current question updated in realtime"

## User Stories *(mandatory)*

### User Story 1 - Presenter authenticates inside the deck (Priority: P1)

A presenter opens their Slidev deck before a talk. Among the standard
slide tools (the addon's tool strip rendered on every slide) they see an
authentication control. They activate it, supply a presenter credential,
and the deck records that this browser instance is acting as the
presenter. The control's visible state changes to indicate "signed in."
From this point on, navigating to any poll slide in this deck causes
that slide's question to become the poll's active question on the
backend.

**Why this priority**: Without an explicit, in-deck way for the
presenter to claim presenter status, no other story is reachable.
Today every viewer of the deck implicitly carries the presenter
credential simply by holding the deck URL, which is exactly the
defect this feature exists to fix. Making "I am the presenter" an
explicit, in-deck action is the single change that unlocks all the
gating logic downstream.

**Independent Test**: Open the deck in a fresh browser profile, click
the auth control in the slide tools, supply a valid presenter
credential, and confirm the control reports the signed-in state.
Navigate to a poll slide and verify (via the backoffice or backend
inspection) that the slide's question is now the poll's active
question. No audience or other viewer involvement required.

**Acceptance Scenarios**:

1. **Given** a deck loaded in a browser with no prior auth state, **When**
   the presenter opens any slide, **Then** the slide tools include a
   clearly identifiable authentication control whose default visible
   state is "not signed in."
2. **Given** the auth control is in the not-signed-in state, **When** the
   presenter activates it and supplies a valid presenter credential,
   **Then** the control transitions to a "signed in" state and remains
   in that state as the presenter navigates between slides in the same
   browser session.
3. **Given** a signed-in deck, **When** the presenter navigates to a
   poll slide whose question is not currently active on the backend,
   **Then** within the live-update budget the backend records that
   question as the poll's active question.
4. **Given** a signed-in deck, **When** the presenter reloads the
   page, **Then** the auth state is restored without re-prompting for
   the credential, until the presenter explicitly signs out or the
   credential is invalidated by the backend.

---

### User Story 2 - Unauthenticated viewer cannot hijack the active question (Priority: P1)

An audience member, a colleague, or anyone else with the deck URL opens
the presentation in their own browser during the talk. They navigate
freely through the deck — including to slides that contain polls — but
no navigation they perform causes the backend's active-question pointer
to change. The presenter's chosen active question on stage remains
authoritative regardless of how many other people are clicking through
the deck.

**Why this priority**: This is the security defect being fixed. Without
this story, the feature delivers no value; with it, the "anyone in the
room can derail the talk" failure mode is closed even before the
read-only experience (Story 3) is polished.

**Independent Test**: Open the deck in two browsers — one signed in as
presenter (per Story 1), one with no auth state. From the
unauthenticated browser, navigate rapidly through every poll slide in
the deck. Verify in the backend that the active question on every
poll changes only in response to the signed-in browser's navigation,
never in response to the unauthenticated browser's navigation.

**Acceptance Scenarios**:

1. **Given** a deck loaded with no presenter auth state, **When** the
   viewer navigates to a poll slide, **Then** the backend's active
   question on that poll MUST NOT change as a result of that
   navigation.
2. **Given** a deck loaded with no presenter auth state, **When** the
   viewer rapidly navigates back and forth across multiple poll slides,
   **Then** zero activation calls reach the backend from this browser
   for the duration of the session.
3. **Given** a deck whose presenter credential has been revoked from
   the backoffice mid-session, **When** the (now-stale) signed-in
   browser next navigates to a poll slide, **Then** the activation
   attempt is refused by the backend, the in-deck auth control reverts
   to the not-signed-in state with a clear reason, and the active
   question on the poll is not changed by that navigation.

---

### User Story 3 - Unauthenticated viewer sees live results in read-only mode (Priority: P2)

While the presenter runs the talk, a viewer who opened the deck without
signing in still wants to follow along. When they reach a poll slide,
they see the same results visualisation as the presenter's deck — vote
tallies for that slide's question, updating live as audience responses
arrive — without any presenter-only controls and without their
navigation causing any side effect on the backend.

**Why this priority**: Once Story 2 is in place the deck is
defect-free, but a viewer staring at an empty or broken poll slide is
a poor experience. Story 3 makes the read-only path feel like a
deliberate "audience member with the deck open" mode rather than a
crippled presenter mode. It is P2 because the security gating, not the
read-only polish, is the core fix.

**Independent Test**: With a poll whose active question is receiving
simulated votes, open the deck in an unauthenticated browser and
navigate to that question's slide. Verify that the results
visualisation appears, that vote tallies update live within the
live-update budget, and that no presenter-only affordances are
shown. Confirm via backend inspection that no activation call was
made by this browser.

**Acceptance Scenarios**:

1. **Given** a poll slide loaded in an unauthenticated browser, **When**
   the slide renders, **Then** the slide displays the live results
   visualisation for that slide's question and the visualisation
   updates as new responses arrive, with no presenter-only controls
   visible.
2. **Given** an unauthenticated viewer is on a poll slide, **When** the
   live-update channel is temporarily lost, **Then** the slide does not
   crash the deck, surfaces an unobtrusive "live updates paused"
   indicator, and resumes updates when connectivity returns.
3. **Given** an unauthenticated viewer is on a poll slide, **When** the
   presenter (in their own signed-in deck) advances to a different
   question on the same poll, **Then** the unauthenticated viewer's
   slide remains showing its own slide's question's results — local
   navigation in the unauthenticated deck is independent of the
   presenter's stage navigation.

---

### Edge Cases

- What happens when two browsers are signed in as the presenter at the
  same time and both navigate to different poll slides within the
  live-update window? (Expected: last-write-wins on the backend, per
  existing FR-004 atomic activation; no new contention beyond what
  Feature 001 already handles.)
- What happens when the presenter signs in inside the deck, then
  closes the tab without signing out, and reopens the deck later?
  (Expected: stored auth state restores per Story 1 scenario 4 unless
  backend has revoked the credential.)
- What happens when the presenter signs out mid-talk while standing on
  a poll slide? (Expected: the deck transitions to read-only mode for
  that browser; the currently active question on the poll is not
  changed by the sign-out itself.)
- What happens when an unauthenticated viewer is on a poll slide and
  the presenter closes the question in the backoffice? (Expected: the
  viewer's results visualisation reflects the closed state via the
  same live-update channel; the auth gating is not involved.)
- What happens when the auth control receives an invalid credential?
  (Expected: control remains in the not-signed-in state and surfaces
  an authentication-failure message distinct from network failure per
  Constitution Principle VI / existing FR-017.)
- What happens when a viewer's deck loses the live-update channel
  *and* attempts to navigate poll slides? (Expected: navigation is
  unaffected since unauthenticated navigation produces no backend
  call; the only visible degradation is the "live updates paused"
  indicator from Story 3 scenario 2.)
- What happens to a presenter who opens the deck on a second device
  (e.g., laptop and tablet) and signs in on both? (Expected: both
  count as presenter; navigation on either triggers activation;
  last-write-wins as in the first edge case above.)

## Requirements *(mandatory)*

### Functional Requirements

**In-deck authentication control**

- **FR-001**: The Slidev addon MUST render an authentication control as
  part of the slide tools surface that is reachable from every slide
  in the deck.
- **FR-002**: The authentication control MUST visibly indicate the
  current presenter-auth state of the deck browser at all times — at
  minimum distinguishing "not signed in" from "signed in."
- **FR-003**: The authentication control MUST allow a presenter to
  supply a presenter credential and, on success, transition the deck
  browser to the "signed in" state.
- **FR-004**: The authentication control MUST allow a signed-in
  presenter to explicitly sign out, returning the deck browser to the
  "not signed in" state without affecting any other browser viewing
  the same deck.
- **FR-005**: The signed-in state MUST persist across slide navigation
  within the same browser session and SHOULD persist across page
  reloads in the same browser until the presenter signs out or the
  credential is rejected by the backend.

**Gating of active-question activation**

- **FR-006**: Navigation to a poll slide MUST only cause the backend's
  active-question pointer to change when the deck browser is in the
  signed-in state with a credential the backend currently accepts.
- **FR-007**: An unauthenticated deck browser MUST NOT issue any
  active-question activation call to the backend as a result of slide
  navigation, on any slide.
- **FR-008**: A previously signed-in deck browser whose credential has
  been revoked or expired MUST be treated as unauthenticated for the
  purposes of FR-006, MUST surface the auth failure to the presenter,
  and MUST revert the auth control to the not-signed-in state on the
  next rejected activation attempt.
- **FR-009**: This feature MUST NOT broaden the set of callers
  authorised to change a poll's active question beyond the
  presenter-credential model already established for the deck path
  (Feature 001 FR-019); any backend route that mutates the active
  question MUST continue to refuse anonymous and unscoped callers.

**Read-only viewer experience**

- **FR-010**: An unauthenticated deck browser on a poll slide MUST
  display the live results visualisation for that slide's question
  using only public, read-only data channels.
- **FR-011**: The live results visualisation in the unauthenticated
  state MUST update in near real time as new responses arrive, on the
  same live-update budget as the signed-in presenter view (Feature 001
  SC-003).
- **FR-012**: The unauthenticated deck MUST NOT render any control
  whose activation would attempt to mutate poll state (e.g., explicit
  "set this question active" affordances), beyond the authentication
  control itself.
- **FR-013**: Loss of the live-update channel in the unauthenticated
  deck MUST NOT crash, freeze, or block slide navigation; the slide
  MUST surface a non-blocking "live updates paused" indicator and
  resume on reconnect, mirroring Feature 001 FR-015.

**Cross-cutting**

- **FR-014**: Presenter-facing failure messages produced by the
  authentication control or by gated activation attempts MUST
  distinguish authentication failure, authorisation failure, and
  transport failure (consistent with Feature 001 FR-017 and
  Constitution Principle VI).
- **FR-015**: The presenter credential used by the in-deck auth
  control MUST be revocable from the backoffice without requiring a
  redeploy of the deck or the backend, so that a leaked credential
  can be cut off mid-talk.

### Key Entities *(include if feature involves data)*

- **Deck Auth State**: The per-browser, client-side record of whether
  this deck instance is currently acting as the presenter, including
  the credential reference used and a flag for whether the backend
  has most recently accepted or rejected it. Lives in the deck
  browser; not a backend entity.
- **Presenter Credential**: The token or session reference the deck
  browser presents to the backend to authorise active-question
  activation. Refines and reuses Feature 001's DeckToken concept;
  this feature does not introduce a parallel credential type.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a session where N unauthenticated browsers and one
  authenticated presenter browser navigate the same deck, exactly
  zero active-question changes on the backend originate from the
  unauthenticated browsers, regardless of the order or rate of their
  navigation.
- **SC-002**: A presenter can transition a freshly-opened deck from
  "not signed in" to "signed in" in under 10 seconds, including
  locating the auth control and supplying the credential, without
  leaving the deck.
- **SC-003**: After a deck reload in the same browser, the signed-in
  state is restored without re-prompting the presenter for the
  credential, in 100% of cases where the credential has not been
  revoked and storage has not been cleared.
- **SC-004**: An unauthenticated viewer on a poll slide sees the
  current live tally for that slide's question within the same time
  budget as the presenter view (Feature 001 SC-003: 2 seconds of a
  new response being accepted), with no presenter-only controls
  visible on that slide.
- **SC-005**: A revoked presenter credential ceases to authorise
  active-question activation within one activation attempt of the
  revocation taking effect on the backend, and the deck's auth
  control reflects the revocation on that same attempt.
- **SC-006**: Zero backend routes that mutate a poll's active
  question accept anonymous calls, verified by automated test across
  every such route (consistent with and extending Feature 001
  SC-005).
