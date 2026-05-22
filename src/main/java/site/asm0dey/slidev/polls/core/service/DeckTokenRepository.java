package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.DeckToken;

/**
 * Persistence contract for the {@code deck_tokens} table. Lives in {@code poll-core} so {@link
 * DeckTokenService} can be unit-tested without Spring or jOOQ.
 */
public interface DeckTokenRepository {

  DeckToken insert(DeckToken token);

  List<DeckToken> findByPoll(UUID pollId);

  Optional<DeckToken> findById(UUID tokenId);

  /** Used by the deck-auth filter — returns {@link Optional#empty()} when revoked or absent. */
  Optional<DeckToken> findLiveByHash(String tokenHash);

  DeckToken revoke(UUID tokenId);

  /** Soft-revoke (set revoked_at) all live tokens on {@code pollId} minted by {@code username}. */
  void revokeByPollAndMinter(java.util.UUID pollId, String username);

  /** Soft-revoke all live tokens minted by {@code username}, across every poll. */
  void revokeAllByMinter(String username);
}
