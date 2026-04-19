# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-002 @P2
Feature: Audience votes anonymously via link or QR
  An audience member follows a slug URL or scans a QR code, sees the
  currently active question on an unauthenticated page, submits exactly
  one response, and receives confirmation. The path MUST require no
  authentication and no PII.

  @TS-020 @FR-007 @FR-008 @SC-001 @SC-007 @acceptance
  Scenario: Anonymous visitor opens a poll with an active question
    Given a poll with slug "my-talk" has an ACTIVE question with two options
    When an anonymous visitor fetches "/api/polls/by-slug/my-talk"
    Then the response status is 200
    And the response state is "ACTIVE"
    And the active question and its options are present
    And no authentication challenge is issued
    And no PII field is required or present in the response

  @TS-021 @FR-008 @acceptance
  Scenario: Anonymous visitor opens a poll with no active question
    Given a poll with slug "my-talk" has no active question
    When an anonymous visitor fetches "/api/polls/by-slug/my-talk"
    Then the response state is "WAITING"
    And no active question is returned
    And the response is not an error

  @TS-022 @FR-009 @SC-002 @acceptance
  Scenario: Visitor submits a valid vote and is acknowledged
    Given a poll with slug "my-talk" has an ACTIVE question with options "A" and "B"
    And the visitor has a fresh voter_token "v-123"
    When the visitor submits option "A" for slug "my-talk" with voter_token "v-123"
    Then the response status is 201
    And the acknowledgement contains a voteId and recordedAt timestamp
    And a row exists in votes with (question_id, voter_token) = (the active question, "v-123")

  @TS-023 @FR-009 @validation
  Scenario: Duplicate submission from the same voter_token is rejected as ALREADY_VOTED
    Given a poll with slug "my-talk" has an ACTIVE question
    And the visitor has already voted with voter_token "v-123"
    When the visitor submits another option for slug "my-talk" with voter_token "v-123"
    Then the response status is 409
    And the response body has code "ALREADY_VOTED"
    And no second row is inserted into votes

  @TS-024 @FR-009 @validation
  Scenario: Concurrent duplicate submissions from the same voter_token yield exactly one recorded vote
    Given a poll with slug "my-talk" has an ACTIVE question
    When two submissions with the same voter_token "v-123" arrive concurrently
    Then exactly one submission receives 201
    And the other receives 409 with code "ALREADY_VOTED"
    And exactly one row exists in votes for (question, "v-123")

  @TS-025 @FR-010 @acceptance
  Scenario: Vote arriving after the active question has been closed is rejected with non-technical copy
    Given a poll's active question was just closed
    When a visitor submits a vote for slug of that poll
    Then the response status is 409
    And the response body has code "QUESTION_NOT_ACTIVE"
    And the response message is user-facing and contains no stack trace or SQL fragment

  @TS-026 @FR-005 @acceptance
  Scenario: QR image for the poll decodes to the slug's public URL
    Given presenter "alice" owns a poll with slug "my-talk"
    When the admin requests the poll's QR image
    Then the response content-type is "image/png"
    And decoding the PNG yields a URL ending in "/my-talk"

  @TS-027 @FR-011 @SC-007 @validation
  Scenario: The vote API does not accept or record any PII
    When a client submits a vote body with extra fields "email" and "name"
    Then the unrecognised fields are ignored and not persisted
    And the votes row contains no column for PII
