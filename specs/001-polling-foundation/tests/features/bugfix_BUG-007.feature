@BUG-007
Feature: Bug fix for BUG-007 — go-task test:e2e:slidev fails with 403 on admin poll creation

  Scenario: slidev e2e spec seeds a poll through the CSRF-protected admin surface
    Given the compose.dev.yml backend is running on PW_BASE_URL
    And the slidev-component Playwright spec has logged in as alice
    When the spec calls POST /api/admin/polls from its beforeAll hook
    Then the request attaches the XSRF-TOKEN cookie as an X-XSRF-TOKEN header
    And the response status is 201
    And the full spec (snapshot + tally SSE assertions) passes end-to-end via `go-task test:e2e:slidev`
