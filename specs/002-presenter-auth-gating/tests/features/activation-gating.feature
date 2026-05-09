# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-002 @P1
Feature: Unauthenticated viewers cannot hijack the active question
  An unauthenticated deck browser MUST NOT cause the backend's
  active-question pointer to change as a result of slide navigation —
  on any slide, at any rate. A signed-in browser whose credential has
  been revoked MUST be treated as unauthenticated on the next activation
  attempt and MUST have its in-deck control reverted to "not signed in"
  with a clear reason. The deck-token credential model established in
  feature 001 is not broadened by this feature.

  Background:
    Given a poll "my-talk" exists with questions Q1 and Q2
    And "alice" has minted a deck token "dtk-1" for that poll

  @TS-120 @FR-006 @FR-007 @SC-001 @acceptance
  Scenario: Anonymous deck navigation does not change the active question
    Given a deck loaded in a browser with no presenter auth state
    And Q1 is currently the active question on the poll
    When the browser navigates to the slide whose questionId is Q2
    Then no POST is issued to "/api/deck/polls/{pollId}/activate" from that browser
    And the poll's active question remains Q1

  @TS-121 @FR-007 @SC-001 @acceptance
  Scenario: Rapid anonymous navigation across poll slides produces zero activation calls
    Given a deck loaded in a browser with no presenter auth state
    When the browser navigates back and forth across every poll slide in the deck for 60 seconds
    Then the count of POST calls from this browser to "/api/deck/polls/{pollId}/activate" is exactly 0

  @TS-122 @FR-006 @acceptance
  Scenario: Signed-in deck navigation activates the slide's question
    Given a deck loaded in a browser signed in with token "dtk-1"
    And Q1 is currently the active question on the poll
    When the browser navigates to the slide whose questionId is Q2
    Then a POST to "/api/deck/polls/{pollId}/activate" is issued exactly once with header "X-Deck-Token: dtk-1" and body question Q2
    And within the live-update budget Q2 is "ACTIVE"

  @TS-123 @FR-008 @SC-005 @acceptance
  Scenario: Revoked credential is rejected on the next activation attempt and reverts the control
    Given the control is in the "signed in" state for token "dtk-1"
    And "alice" revokes "dtk-1" on the backoffice
    When the browser navigates to a poll slide whose question is not currently active
    And the backend responds 401 to the activation POST with code "DECK_TOKEN_INVALID"
    Then the composable status transitions to "revoked"
    And the control reverts to the "not signed in" visual with the message "credential not recognised"
    And the poll's active question is unchanged by this navigation

  @TS-124 @FR-007 @validation
  Scenario: Anonymous POST to /api/deck/polls/{pollId}/activate is refused
    Given no deck token and no session cookie
    When a POST to "/api/deck/polls/{pollId}/activate" is sent with a question body
    Then the response status is 401
    And the response body has code "DECK_TOKEN_INVALID"
    And the poll's active question pointer is unchanged

  @TS-125 @FR-009 @SC-006 @validation
  Scenario Outline: Every backend route that mutates the active question refuses anonymous callers
    Given no deck token and no session cookie
    When a "<method>" to "<route>" is sent
    Then the response status is in {401, 403}
    And the response body has code in {"AUTH_REQUIRED", "DECK_TOKEN_INVALID", "FORBIDDEN"}
    And no poll's active question pointer is changed by the call

    Examples:
      | method | route                                              |
      | POST   | /api/deck/polls/{pollId}/activate                  |
      | POST   | /api/backoffice/polls/{pollId}/questions/{qId}/activate |

  @TS-126 @FR-009 @validation
  Scenario: The feature does not introduce any new route that mutates the active question
    Given the set of backend routes that mutate a poll's active question before feature 002
    When the backend is built after feature 002
    Then the set of such routes is unchanged
    And no route added by feature 002 accepts a request body naming a question as active
