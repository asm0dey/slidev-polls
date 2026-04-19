# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-001 @P1
Feature: Presenter authors and controls a poll
  A presenter signs in to a private backoffice and manages polls:
  creating polls with questions, assigning memorable slugs, activating
  and closing questions, and obtaining join link / QR artefacts. The
  backoffice is reachable only after authentication; anonymous traffic
  MUST be refused.

  Background:
    Given the system has a seeded presenter account "alice" with password "correct-horse"

  @TS-001 @FR-001 @FR-016 @SC-005 @acceptance
  Scenario: Unauthenticated visitor cannot reach any backoffice resource
    Given no authenticated session
    When the visitor requests any "/api/admin/**" endpoint
    Then the response status is 401
    And the response body has code "AUTH_REQUIRED"
    And no backoffice data is included in the response

  @TS-002 @FR-001 @FR-002 @FR-003 @FR-005 @acceptance
  Scenario: Authenticated presenter creates a poll with two questions
    Given presenter "alice" is signed in
    When she creates a poll titled "Quickstart demo" with questions:
      | prompt              | options                |
      | Which JVM?          | OpenJDK, GraalVM       |
      | Favourite IDE?      | IntelliJ, VS Code      |
    Then the poll is persisted and owned by "alice"
    And the poll appears in her poll list
    And the poll exposes a join link of the form "http://.+/[a-z0-9-]{3,40}"
    And a QR image is retrievable from the poll detail

  @TS-003 @FR-004 @acceptance
  Scenario: Activating a question atomically closes any previously active question
    Given presenter "alice" owns a poll with questions Q1 and Q2 both in status "DRAFT"
    And she has activated Q1
    When she activates Q2
    Then Q2 is "ACTIVE"
    And Q1 is "CLOSED"
    And the poll's active_question pointer references Q2

  @TS-004 @FR-004 @validation
  Scenario: The storage layer rejects a second ACTIVE question on the same poll
    Given a poll has an ACTIVE question
    When a concurrent transaction attempts to mark a second question ACTIVE
    Then exactly one of the two transactions succeeds
    And the other fails with a unique-constraint violation on the partial index

  @TS-005 @FR-006 @FR-010 @acceptance
  Scenario: Presenter closes the active question and subsequent votes are rejected
    Given presenter "alice" owns a poll with an ACTIVE question
    When she closes the active question
    Then the poll has no active question
    And a vote submission against that question is rejected with code "QUESTION_NOT_ACTIVE"

  @TS-006 @FR-002 @acceptance
  Scenario: Presenter deletes a poll they own
    Given presenter "alice" owns a poll
    When she deletes the poll
    Then the poll no longer appears in her poll list
    And fetching it returns 404
