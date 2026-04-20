@BUG-005
Feature: Bug fix for BUG-005 — voter page for a created poll renders empty

  Scenario: Opening the voter URL advertised by the admin polls view shows the poll
    Given the compose.dev.yml stack is running and the backend has bound to :8080
    And an admin has created a poll whose voter URL is surfaced in the backoffice polls list
    When an anonymous visitor opens the voter URL for that poll (e.g. http://localhost:8080/<slug>)
    Then the voter SPA renders poll content for that slug
    And the page is not an empty shell without poll content
    And the visitor sees the poll's question and at least one option

  Scenario: The voter page backend contract resolves the poll by its slug
    Given an admin has created a poll with slug "bug-004-regression-e2e"
    When the voter SPA requests the poll data for slug "bug-004-regression-e2e" from the backend
    Then the backend returns the poll payload with HTTP 200
    And the response contains the poll's question and options sufficient for the voter SPA to render
