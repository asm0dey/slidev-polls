# DO NOT MODIFY SCENARIOS
# Derived from requirements. Fix code to pass tests; re-run /iikit-04-testify if requirements change.

@US-003 @P3
Feature: Slidev slide shows live results
  A Slidev deck embeds <PollResults slug="..."/>; it MUST display the
  aggregate of the poll's currently active question, update on each
  accepted vote within the live-update budget, swap when the active
  question changes, and NEVER crash the deck when updates are lost.

  @TS-030 @FR-012 @FR-013 @SC-003 @acceptance
  Scenario: Subscriber receives a snapshot on connect and a tally on each vote
    Given a poll with slug "my-talk" has an ACTIVE question with options "A" and "B" and zero votes
    When a client subscribes to "/api/polls/my-talk/stream"
    Then the first event the client receives is event "snapshot"
    And the snapshot contains the active question with both options
    And the snapshot tally reports count 0 for both options
    When a vote for option "A" is accepted
    Then the client receives an event "tally" within 2 seconds
    And the tally carries optionId = "A" and count = 1

  @TS-031 @FR-014 @acceptance
  Scenario: Active question change causes a fresh snapshot to be emitted
    Given a subscriber is connected to a poll's stream receiving question Q1 snapshots
    When the presenter activates Q2
    Then the subscriber receives a new "snapshot" event within 2 seconds
    And the snapshot's active_question id equals Q2's id
    And the snapshot's tally contains every option of Q2 with count 0

  @TS-032 @FR-013 @validation
  Scenario: A stray tally whose questionId does not match the current snapshot is ignored by the client
    Given a subscriber has the latest snapshot for question Q2
    When a tally event carrying questionId = Q1 arrives
    Then the client does not mutate the Q2 view
    And the client waits for the next snapshot

  @TS-033 @FR-015 @SC-006 @acceptance
  Scenario: Backend loss surfaces as a paused indicator without crashing the deck
    Given a Slidev deck embeds <PollResults slug="my-talk"/>
    And the client has received at least one snapshot
    When the backend becomes unreachable
    Then the PollResults component renders a visible "live updates paused" indicator
    And the component does not throw or unmount
    And deck navigation is not blocked

  @TS-034 @FR-015 @SC-006 @acceptance
  Scenario: Reconnect after backend returns delivers a fresh snapshot within 10 seconds
    Given the backend was unreachable and the "paused" indicator is showing
    When the backend becomes reachable again
    Then within 10 seconds the client receives a "snapshot" event
    And the "paused" indicator is cleared
    And the tally shown is consistent with the backend's current state

# DEPRECATED: TS-035 removed per clarification 2026-04-19 — load testing deferred out of scope for this feature. SC-004 acceptance is marked deferred in spec.md and will be re-scenarioed when load testing returns.
#  @TS-035 @SC-004 @acceptance
#  Scenario: A single backend instance sustains 200 concurrent subscribers on one question
#    Given 200 clients are subscribed to "/api/polls/my-talk/stream"
#    When 200 votes are accepted on the active question over 10 seconds
#    Then every subscriber receives all 200 "tally" events
#    And the 95th-percentile broadcast latency from vote accept to client delivery is under 500 milliseconds
