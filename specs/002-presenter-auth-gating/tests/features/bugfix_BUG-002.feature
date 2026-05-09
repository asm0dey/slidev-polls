@BUG-002
Feature: Bug fix for BUG-002 — Deck auth uses a single-token paste field instead of a login+password form matching the admin UI.
  Scenario: Deck auth popover requires login and password matching the admin UI credential model
    Given a Slidev deck is running with the slidev-component addon registered
    When the presenter opens the deck auth popover from the nav bar while anonymous
    Then the form renders a login (username) input and a password input
    And submitting valid login+password credentials authenticates the deck via the same credential flow used by the admin UI
    And no single opaque "deck token" input is rendered in the popover
