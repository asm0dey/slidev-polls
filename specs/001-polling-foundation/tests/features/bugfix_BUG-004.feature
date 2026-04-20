@BUG-004
Feature: Bug fix for BUG-004 — "You don't own this poll." on poll creation

  Scenario: Authenticated admin creates a new poll and sees the editor, not a FORBIDDEN error
    Given the compose.dev.yml stack is running and the backend has bound to :8080
    And the admin SPA has an active authenticated session for user alice
    And the admin has no pre-existing polls for this session
    When the admin opens the "new poll" form and submits a valid poll with a title and one question with at least two options
    Then the backend accepts the request and creates the poll under alice's ownership
    And the SPA does not display the error "You don't own this poll."
    And the SPA navigates to the editor or detail view for the newly created poll

  Scenario: No FORBIDDEN response is produced by the create-poll round trip
    Given the admin SPA has an active authenticated session for user alice
    When the SPA submits POST /api/admin/polls with a valid CreatePollRequest payload
    Then the backend returns HTTP 201 with the created poll's details
    And any follow-up admin request the SPA issues as part of the create flow returns a non-403 status for the authenticated creator
