package site.asm0dey.slidev.polls.api.deck.dto;

import java.util.UUID;

/**
 * Response body of {@code POST /api/deck/auth/login}. Carries the minted deck-token plaintext once
 * (Principle II — plaintext is surfaced only at mint) plus the scope the addon's composable needs
 * to persist to localStorage under {@code slidev-polls:deck-auth}.
 */
public record DeckLoginResponse(String token, UUID tokenId, UUID pollId, String label) {}
