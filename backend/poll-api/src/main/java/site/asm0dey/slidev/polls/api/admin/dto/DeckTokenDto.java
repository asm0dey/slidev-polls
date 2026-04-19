package site.asm0dey.slidev.polls.api.admin.dto;

import java.time.Instant;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.DeckToken;

/**
 * List-projection view of a deck token. Matches {@code DeckToken} in {@code openapi.yaml} — note
 * the plaintext is intentionally absent ({@code @TS-056}); only mint returns it, via {@link
 * DeckTokenMintedDto}.
 */
public record DeckTokenDto(
    UUID id, UUID pollId, String label, Instant createdAt, Instant revokedAt) {

  public static DeckTokenDto from(DeckToken token) {
    return new DeckTokenDto(
        token.id(), token.pollId(), token.label(), token.createdAt(), token.revokedAt());
  }
}
