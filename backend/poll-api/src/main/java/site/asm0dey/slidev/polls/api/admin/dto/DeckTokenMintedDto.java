package site.asm0dey.slidev.polls.api.admin.dto;

import java.time.Instant;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Mint response body. The {@code plaintext} field is present exactly once — on mint — and MUST
 * never land on a list projection ({@code @TS-056}).
 */
public record DeckTokenMintedDto(
    UUID id,
    UUID pollId,
    String label,
    String plaintext,
    Instant createdAt,
    Instant revokedAt,
    boolean revoked) {

  public static DeckTokenMintedDto from(DeckTokenService.Minted minted) {
    return new DeckTokenMintedDto(
        minted.token().id(),
        minted.token().pollId(),
        minted.token().label(),
        minted.plaintext(),
        minted.token().createdAt(),
        minted.token().revokedAt(),
        minted.token().revokedAt() != null);
  }
}
