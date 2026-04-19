# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-003 @P2
Feature: Slidev deck drives active-question selection
  When the presenter navigates to a Slidev slide that embeds the
  poll-results component for a specific question, the backend's
  active-question pointer MUST update to match — without the presenter
  touching the backoffice. Authorisation is a presenter-minted,
  poll-scoped deck token (distinct from the backoffice session cookie).

  @TS-050 @FR-018 @SC-008 @acceptance
  Scenario: Mounting the poll-results component activates its declared question
    Given presenter "alice" owns a poll with questions Q1 and Q2, both in status "DRAFT"
    And "alice" has minted a deck token "dtk-1" for that poll
    When a Slidev deck mounts <PollResults slug="my-talk" questionId="Q1" deckToken="dtk-1"/>
    Then within 1 second, Q1 is "ACTIVE"
    And the poll's active_question pointer references Q1

  @TS-051 @FR-018 @FR-004 @acceptance
  Scenario: Navigating to a second poll slide swaps the active question
    Given Q1 is currently ACTIVE on the poll
    When the deck unmounts the Q1 component and mounts <PollResults slug="my-talk" questionId="Q2" deckToken="dtk-1"/>
    Then within 1 second, Q2 is "ACTIVE"
    And Q1 is "CLOSED"

  @TS-052 @FR-018 @acceptance
  Scenario: Activation is idempotent — re-mounting the current slide does not reopen the question
    Given Q1 is currently ACTIVE on the poll
    And Q1's activated_at timestamp is recorded as T1
    When the same <PollResults slug="my-talk" questionId="Q1" deckToken="dtk-1"/> is unmounted and remounted
    Then Q1 remains "ACTIVE"
    And Q1's activated_at timestamp is still T1
    And no "question-closed" event is emitted on the poll's stream

  @TS-053 @FR-019 @validation
  Scenario: Anonymous caller cannot activate a question via the deck endpoint
    Given no deck token and no session cookie
    When a POST to "/api/deck/polls/{pollId}/activate" is sent with a question body
    Then the response status is 401
    And the response body has code "DECK_TOKEN_INVALID"
    And the poll's active_question pointer is unchanged

  @TS-054 @FR-019 @validation
  Scenario: Deck token scoped to poll A cannot activate a question on poll B
    Given "alice" has minted a deck token "dtk-A" for poll A
    And a different poll B exists
    When a POST to "/api/deck/polls/{pollIdOfB}/activate" is sent with header "X-Deck-Token: dtk-A"
    Then the response status is 403
    And the response body has code "DECK_TOKEN_POLL_MISMATCH"
    And poll B's active_question pointer is unchanged

  @TS-055 @FR-019 @validation
  Scenario: Revoked deck token no longer authorises activation
    Given "alice" minted deck token "dtk-1" and has revoked it
    When a POST to "/api/deck/polls/{pollId}/activate" is sent with header "X-Deck-Token: dtk-1"
    Then the response status is 401
    And the response body has code "DECK_TOKEN_INVALID"

  @TS-056 @FR-019 @acceptance
  Scenario: Minted token is shown exactly once; only its hash is persisted
    Given presenter "alice" is signed in
    When she mints a deck token for her poll
    Then the response contains a non-empty "plaintext" field
    And the database row persists only a SHA-256 hash of that plaintext
    And fetching the deck-tokens list afterwards returns rows with no plaintext field

  @TS-057 @FR-019 @validation
  Scenario: Deck token does not grant access to any other backoffice resource
    Given "alice" has minted a deck token "dtk-1"
    When "dtk-1" is sent on any non-deck-activate admin endpoint
    Then the response status is 401
    And the response body has code "AUTH_REQUIRED"
