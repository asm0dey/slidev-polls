# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@P2
Feature: Cross-cutting API behaviour
  Authorisation scoping, Problem code discipline (FR-017), SPA routing,
  and single-origin deployment assertions that apply across the
  backoffice, public, and stream surfaces.

  @TS-040 @FR-016 @SC-005 @acceptance
  Scenario Outline: Anonymous traffic cannot read or modify any backoffice resource
    Given no authenticated session
    When the client sends "<method>" to "<path>"
    Then the response status is 401
    And the response body has code "AUTH_REQUIRED"

    Examples:
      | method | path                                       |
      | GET    | /api/admin/polls                           |
      | POST   | /api/admin/polls                           |
      | GET    | /api/admin/polls/00000000-0000-0000-0000-000000000000 |
      | PATCH  | /api/admin/polls/00000000-0000-0000-0000-000000000000 |
      | DELETE | /api/admin/polls/00000000-0000-0000-0000-000000000000 |
      | POST   | /api/admin/polls/00000000-0000-0000-0000-000000000000/open  |
      | POST   | /api/admin/polls/00000000-0000-0000-0000-000000000000/close |
      | PUT    | /api/admin/polls/00000000-0000-0000-0000-000000000000/style |
      | GET    | /api/admin/polls/00000000-0000-0000-0000-000000000000/qr.png |

  @TS-041 @FR-002 @FR-016 @acceptance
  Scenario: A signed-in presenter cannot read or modify another presenter's poll
    Given presenters "alice" and "bob" exist
    And a poll is owned by "bob"
    When "alice" is signed in and requests bob's poll detail
    Then the response status is 403
    And the response body has code "FORBIDDEN"

  @TS-042 @FR-017 @validation
  Scenario Outline: Problem codes distinguish failure categories
    When a request fails with category "<category>"
    Then the response body has code "<code>"
    And the response body contains a correlationId

    Examples:
      | category                   | code                  |
      | missing authentication     | AUTH_REQUIRED         |
      | authenticated but not owner| FORBIDDEN             |
      | resource does not exist    | NOT_FOUND             |
      | body failed validation     | VALIDATION_FAILED     |
      | duplicate vote             | ALREADY_VOTED         |
      | vote after close           | QUESTION_NOT_ACTIVE   |
      | activate with <2 options   | ACTIVATION_REJECTED   |
      | slug already in use        | SLUG_TAKEN            |
      | slug format invalid        | SLUG_INVALID          |
      | slug on reserved list      | SLUG_RESERVED         |

  @TS-043 @acceptance
  Scenario: SPA catch-all forwards /{slug} to the voter SPA without swallowing API routes
    Given the service is running
    When an unauthenticated client GETs "/{slug}" where slug is a valid non-reserved slug
    Then the response content-type is "text/html"
    And the response body contains the voter SPA shell
    When the same client GETs "/api/polls/by-slug/{slug}"
    Then the response content-type is "application/json"
    And the response body is NOT the SPA shell

  @TS-044 @acceptance
  Scenario: Backoffice SPA shell is served at /admin/ but data routes remain gated
    When an unauthenticated client GETs "/admin/"
    Then the response content-type is "text/html"
    And the response body contains the backoffice SPA shell
    When the same client GETs "/api/admin/polls"
    Then the response status is 401

  @TS-045 @acceptance
  Scenario: Slug format in the path parameter is validated by the server
    When a client GETs "/api/polls/by-slug/UPPER"
    Then the response status is either 400 or 404
    And the response body is a Problem with a recognised code

  @TS-046 @FR-011 @SC-007 @validation
  Scenario: Voter token cookie is HttpOnly, Secure, SameSite=Lax and carries no PII
    When an anonymous visitor first hits "/api/polls/by-slug/my-talk"
    Then the response sets a cookie "sp_voter"
    And the cookie is HttpOnly
    And the cookie is SameSite=Lax
    And the cookie value is a UUID and contains no personal information
