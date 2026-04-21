package site.asm0dey.slidev.polls.api.deck.dto;

import java.util.UUID;

/**
 * Response body of {@code GET /api/deck/auth/me}. Fields mirror {@code
 * specs/002-presenter-auth-gating/data-model.md §DeckPrincipalView}.
 */
public record DeckPrincipalView(UUID tokenId, UUID pollId, String label) {}
