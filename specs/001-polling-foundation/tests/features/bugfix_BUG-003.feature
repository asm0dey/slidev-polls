@BUG-003
Feature: Bug fix for BUG-003 — admin SPA has no login form when unauthenticated

  Scenario: Unauthenticated visitor to /admin/ is given a way to sign in
    Given the compose.dev.yml stack is running and the backend has bound to :8080
    And the browser has no valid admin session (no session cookie and no stored token)
    When the user opens http://localhost:8080/admin/
    Then the admin SPA renders a visible login form
    And the login form exposes inputs for the admin's username and password
    And the login form exposes a submit control that posts credentials to the backend authentication endpoint

  Scenario: Successful sign-in from the login form clears the unauthenticated state
    Given the admin SPA is rendering the login form because no session is present
    When the user submits valid admin credentials via the login form
    Then the SPA transitions out of the unauthenticated state
    And subsequent reloads of /admin/ while the session is valid do not show the login form
