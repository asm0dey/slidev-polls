package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Presenter-minted, poll-scoped bearer used by Slidev decks to auto-activate a specific question
 * via {@code POST /api/deck/polls/{pollId}/activate}. Only the SHA-256 hash of the plaintext is
 * persisted (the {@code token_hash} column); the plaintext is surfaced exactly once at mint time
 * and never stored.
 *
 * <p>{@code revokedAt} is {@code null} while the token is live; a non-null value short-circuits the
 * authentication filter ({@code @TS-055}).
 */
public record DeckToken(
    UUID id,
    UUID pollId,
    String tokenHash,
    String label,
    Instant createdAt,
    Instant revokedAt,
    String mintedBy) {}
