# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-001 @P1
Feature: Memorable slug addressing for polls
  Polls are addressable by a human-memorable slug that forms their
  public URL "/{slug}". Slugs are server-derived from the poll title on
  creation, validated, editable by the owning presenter, and prevented
  from colliding with reserved routes.

  @TS-010 @FR-005 @acceptance
  Scenario: Slug is derived from the poll title on creation when none is supplied
    Given presenter "alice" is signed in
    When she creates a poll titled "Quickstart demo" without specifying a slug
    Then the created poll has slug "quickstart-demo"
    And the public URL resolves the voter SPA

  @TS-011 @FR-005 @validation
  Scenario Outline: Invalid slug formats are rejected
    Given presenter "alice" is signed in
    When she attempts to create a poll with slug "<slug>"
    Then the response status is 409
    And the response body has code "<code>"

    Examples:
      | slug                 | code         |
      | Ab                   | SLUG_INVALID |
      | ab                   | SLUG_INVALID |
      | -leading             | SLUG_INVALID |
      | trailing-            | SLUG_INVALID |
      | double--dash         | SLUG_INVALID |
      | UPPER                | SLUG_INVALID |
      | has space            | SLUG_INVALID |
      | way-too-long-slug-exceeding-forty-chars-limit | SLUG_INVALID |

  @TS-012 @FR-005 @validation
  Scenario Outline: Reserved slugs cannot be allocated
    Given presenter "alice" is signed in
    When she attempts to create a poll with slug "<slug>"
    Then the response status is 409
    And the response body has code "SLUG_RESERVED"

    Examples:
      | slug    |
      | admin   |
      | api     |
      | assets  |
      | static  |
      | j       |
      | login   |
      | logout  |

  @TS-013 @FR-005 @validation
  Scenario: Slug collision is reported distinctly from invalid format
    Given a poll owned by "alice" already uses slug "my-talk"
    When "alice" attempts to create another poll with slug "my-talk"
    Then the response status is 409
    And the response body has code "SLUG_TAKEN"

  @TS-014 @FR-005 @validation
  Scenario: Slug uniqueness is case-insensitive
    Given a poll owned by "alice" already uses slug "my-talk"
    When "alice" attempts to create another poll with slug "My-Talk"
    Then the response status is 409
    And the response body has code "SLUG_TAKEN"

  @TS-015 @FR-005 @acceptance
  Scenario: Presenter renames the slug of an existing poll
    Given presenter "alice" owns a poll with slug "old-slug"
    When she renames the slug to "new-slug"
    Then the public URL "/new-slug" resolves the voter SPA
    And the public URL "/old-slug" returns 404
    And the QR endpoint for the poll encodes "/new-slug"
