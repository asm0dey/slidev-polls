@BUG-001
Feature: Bug fix for BUG-001 — Flyway V3 migration fails resolving gen_salt('bf', 10)

  Scenario: Backend boots cleanly against a fresh compose.dev.yml Postgres
    Given the repository is checked out at a clean working tree
    And no slidev-polls-postgres volume exists on the host
    When `docker compose -f compose.dev.yml up -d` is executed
    And the slidev-polls-backend container completes startup
    Then Flyway applies migration V3 "admin user" without error
    And the backend logs contain "Started PollApiApplication"
    And the container remains running with exit code 0 not observed
    And an HTTP GET to http://localhost:8080/actuator/health returns 200

  Scenario: Seeded presenter account is usable after migration
    Given the backend has started successfully per the previous scenario
    When the backoffice login flow authenticates user "alice" with password "correct-horse"
    Then authentication succeeds
    And the bcrypt_hash column for "alice" is a valid BCrypt hash verifiable by Spring Security's BCryptPasswordEncoder
