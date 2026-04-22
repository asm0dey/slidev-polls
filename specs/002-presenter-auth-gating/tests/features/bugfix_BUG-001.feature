@BUG-001
Feature: Bug fix for BUG-001 — Deck auth control renders as floating overlay on the slide surface instead of integrating into the Slidev toolbar.
  Scenario: Auth control is rendered inside the Slidev toolbar, not on the slide canvas
    Given a Slidev deck is running with the slidev-component addon registered
    When the presenter opens the deck in presenter mode or single-page mode
    Then the deck auth control is rendered inside Slidev's built-in toolbar / nav-controls slot
    And no auth input is painted on top of the slide canvas via the global-top slot
