@BUG-002
Feature: Bug fix for BUG-002 — compose.dev.yml exposes neither frontend

  Scenario: Backoffice SPA shell is served under /admin/
    Given the compose.dev.yml stack is running and the backend has bound to :8080
    When an HTTP GET is made to http://localhost:8080/admin/
    Then the response status is 200
    And the response Content-Type is "text/html;charset=UTF-8"
    And the response body contains the backoffice SPA shell markup loaded from static/admin/index.html
    And the backend logs do not contain "StackOverflowError" for the dispatcher-servlet

  Scenario: Backoffice assets load under /admin/
    Given the compose.dev.yml stack is running and the backoffice shell has been served at /admin/
    When an HTTP GET is made to the first JS asset referenced by the shell under /admin/assets/
    Then the response status is 200
    And the Content-Type begins with "application/javascript" or "text/javascript"

  Scenario: Voter SPA renders something at the root URL
    Given the compose.dev.yml stack is running and the backend has bound to :8080
    When an HTTP GET is made to http://localhost:8080/
    Then the response status is 200
    And the response body references the voter SPA's entry script from /assets/
    And a browser hydrating this document shows a non-empty voter UI (either a landing view or a clear "no poll selected" state), not a blank <div id="app"/>
