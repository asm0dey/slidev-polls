@BUG-003
Feature: Bug fix for BUG-003 — Deck auth popover is clipped below the viewport and unreachable.
  Scenario: Deck auth popover opens upward from the nav bar trigger and stays within the viewport
    Given a Slidev deck is running with the slidev-component addon registered
    And the Slidev nav bar is visible at the bottom of the viewport
    When the presenter clicks the "sign in" button in the nav bar
    Then the deck auth popover opens upward from the trigger
    And the popover is fully rendered within the viewport bounds
    And no popover content is clipped below the bottom edge of the window
