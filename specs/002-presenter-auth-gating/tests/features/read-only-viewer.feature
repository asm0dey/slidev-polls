# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-003 @P2
Feature: Unauthenticated viewers see live results in read-only mode
  A viewer who opened the deck without signing in still sees the live
  results visualisation on every poll slide, updates within the same
  live-update budget as the presenter view, and sees no
  presenter-only affordances. Loss of the live-update channel MUST
  degrade visibly — never crash the deck or block navigation.

  Background:
    Given a poll "my-talk" with questions Q1 and Q2
    And the active question on the poll is Q1

  @TS-140 @FR-010 @FR-012 @SC-004 @acceptance
  Scenario: Unauthenticated viewer sees the live results visualisation without presenter controls
    Given a deck loaded in a browser with no presenter auth state
    When the browser renders the slide whose questionId is Q1
    Then the slide displays the live results visualisation for Q1
    And no "set this question active" affordance is rendered
    And no request is issued to "/api/deck/polls/{pollId}/activate"

  @TS-141 @FR-011 @SC-004 @acceptance
  Scenario: Live tally updates within the live-update budget on the read-only path
    Given an unauthenticated browser is rendering the slide for Q1
    And an SSE subscription is open for the poll
    When a new response for Q1 is accepted by the backend
    Then the slide's tally for Q1 reflects the new response within 2 seconds

  @TS-142 @FR-013 @acceptance
  Scenario: Loss of the live-update channel surfaces a "live updates paused" indicator and does not crash
    Given an unauthenticated browser is rendering the slide for Q1
    When the SSE connection is dropped
    Then the slide renders an unobtrusive "live updates paused" indicator
    And slide navigation remains responsive
    And the deck does not crash or freeze

  @TS-143 @FR-013 @acceptance
  Scenario: SSE reconnects and the visualisation resumes updating
    Given the slide for Q1 is showing the "live updates paused" indicator
    When the SSE connection is re-established
    Then the "live updates paused" indicator is removed
    And subsequent responses update the tally within 2 seconds

  @TS-144 @FR-010 @acceptance
  Scenario: Unauthenticated viewer's slide shows its own question's results, independent of presenter stage
    Given the signed-in presenter browser has activated Q2 on the poll
    And an unauthenticated viewer is on the slide whose questionId is Q1
    When new responses for Q1 arrive
    Then the viewer's slide continues to show Q1's results and tallies
    And the viewer's slide does not switch to Q2's results
