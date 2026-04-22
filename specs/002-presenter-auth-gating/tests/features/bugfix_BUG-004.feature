@BUG-004
Feature: Bug fix for BUG-004 — A table-of-contents-style list is rendered on top of the slide content in the slidev demo deck.
  Scenario: The slide canvas renders no TOC overlay in the slidev demo deck
    Given the slidev demo deck is running with the slidev-component addon registered
    When the presenter loads any slide in SPA or presenter mode without explicitly opening the Slidev menu
    Then no table-of-contents list is painted on top of the slide canvas
    And only the authored slide content is visible on the slide surface
