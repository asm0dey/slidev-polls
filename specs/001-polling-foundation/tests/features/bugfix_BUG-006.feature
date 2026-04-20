@BUG-006
Feature: Bug fix for BUG-006 — go-task test:e2e:voter fails because no backend is started

  Scenario: Running the voter e2e task from a clean host provisions its own backend
    Given the developer's host has no process listening on the PW_BASE_URL port (default :8080)
    And the compose.dev.yml stack is not already up
    When the developer runs "go-task test:e2e:voter" from the repository root
    Then the task starts (or waits for) a backend reachable at PW_BASE_URL before Playwright begins
    And the voter happy-path spec at frontends/voter/e2e/voter-happy-path.spec.ts runs to completion against that backend
    And the task exits with status 0 and does not surface ECONNREFUSED from apiRequestContext.post
    And any backend process the task started is torn down after the spec finishes
