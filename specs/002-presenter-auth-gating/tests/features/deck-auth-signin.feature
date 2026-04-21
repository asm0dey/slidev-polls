# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-001 @P1
Feature: Presenter authenticates inside the Slidev deck
  The Slidev addon renders an in-deck auth control reachable from every
  slide. Activating it, pasting a presenter credential (the deck token
  minted in feature 001), and succeeding MUST transition the browser
  into the "signed in" state. The state persists across navigation and
  across page reloads until the presenter signs out or the backend
  rejects the credential.

  Background:
    Given a poll "my-talk" exists with presenter "alice" and questions Q1, Q2
    And "alice" has minted a deck token "dtk-1" for that poll with label "Laptop"
    And the backend exposes "GET /api/deck/auth/me" authenticated by "X-Deck-Token"

  @TS-100 @FR-001 @FR-002 @acceptance
  Scenario: Auth control is present on every slide with "not signed in" as default
    Given a deck loaded in a browser with no prior auth state
    When the presenter opens any slide of the deck
    Then the Slidev global-top overlay renders an authentication control
    And the control's visible state reads "not signed in"

  @TS-101 @FR-003 @SC-002 @acceptance
  Scenario: Valid credential transitions the control to "signed in"
    Given the auth control is in the "not signed in" state
    When the presenter activates the control and submits deck token "dtk-1"
    And the backend responds 200 to "GET /api/deck/auth/me" with tokenId, pollId, and label "Laptop"
    Then the control transitions to the "signed in" state within 10 seconds
    And the control renders the label "Laptop"
    And the composable "useDeckAuth" exposes status "signed-in"

  @TS-102 @FR-005 @acceptance
  Scenario: Signed-in state persists across slide navigation
    Given the control is in the "signed in" state for token "dtk-1"
    When the presenter navigates from slide 1 to slide 5 in the same browser session
    Then the control remains in the "signed in" state on every slide
    And no re-prompt for a credential is shown

  @TS-103 @FR-005 @SC-003 @acceptance
  Scenario: Signed-in state is restored after a page reload without re-prompting
    Given the control is in the "signed in" state for token "dtk-1"
    And the token is present in localStorage under key "slidev-polls:deck-auth"
    When the presenter reloads the page
    And the backend responds 200 to the reload-time "GET /api/deck/auth/me" call
    Then the control re-enters the "signed in" state without prompting for the credential
    And the composable status is "signed-in"

  @TS-104 @FR-004 @acceptance
  Scenario: Sign-out is local-only and clears persisted state
    Given the control is in the "signed in" state for token "dtk-1"
    When the presenter activates the sign-out affordance
    Then the control transitions to the "not signed in" state
    And localStorage key "slidev-polls:deck-auth" is cleared
    And no request is made to revoke the token on the backend

  @TS-105 @FR-001 @FR-003 @validation
  Scenario: Invalid credential leaves the control in "not signed in" with an authentication-failure message
    Given the auth control is in the "not signed in" state
    When the presenter submits a garbage deck token
    And the backend responds 401 to "GET /api/deck/auth/me" with code "DECK_TOKEN_INVALID"
    Then the control remains in the "not signed in" state
    And the control surfaces the message "credential not recognised"
    And the composable status is "anonymous"

  @TS-106 @FR-014 @validation
  Scenario: Transport failure on verify surfaces a distinct transport message
    Given the auth control is in the "not signed in" state
    When the presenter submits deck token "dtk-1"
    And the call to "GET /api/deck/auth/me" fails with a network error
    Then the control remains in the "not signed in" state
    And the control surfaces the message "couldn't reach server"
    And the message is distinct from the "credential not recognised" message

  @TS-107 @FR-003 @contract
  Scenario: GET /api/deck/auth/me returns the token's scope on 200
    Given deck token "dtk-1" is live for poll "my-talk"
    When a GET to "/api/deck/auth/me" is sent with header "X-Deck-Token: dtk-1"
    Then the response status is 200
    And the response body contains fields "tokenId", "pollId", "label"
    And the response body field "label" equals "Laptop"

  @TS-108 @FR-003 @contract
  Scenario: GET /api/deck/auth/me rejects a missing bearer with DECK_TOKEN_INVALID
    Given no "X-Deck-Token" header is sent
    When a GET to "/api/deck/auth/me" is received
    Then the response status is 401
    And the response body has code "DECK_TOKEN_INVALID"

  @TS-109 @FR-003 @contract
  Scenario: GET /api/deck/auth/me rejects an unknown bearer with DECK_TOKEN_INVALID
    Given no deck token matching bearer "bogus" exists in the database
    When a GET to "/api/deck/auth/me" is sent with header "X-Deck-Token: bogus"
    Then the response status is 401
    And the response body has code "DECK_TOKEN_INVALID"
