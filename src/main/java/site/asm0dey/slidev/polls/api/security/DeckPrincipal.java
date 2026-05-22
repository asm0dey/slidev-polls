package site.asm0dey.slidev.polls.api.security;

import java.util.UUID;

/**
 * Principal attached by {@link DeckTokenMechanism} once it matches an unrevoked {@code deck_tokens}
 * row for the incoming {@code X-Deck-Token} header. Carries the {@code pollId} the token was minted
 * against so {@code DeckActivationController} can enforce the poll-scope invariant
 * ({@code @TS-054}).
 *
 * <p>The role {@code DECK} is deliberately narrow — the {@code role-deck} HTTP permission policy in
 * {@code application.properties} only permits it on {@code /api/deck/*}, so the token cannot unlock
 * any other admin surface ({@code @TS-057}). Stored as a {@code SecurityIdentity} attribute under
 * {@code "deckPrincipal"} by the mechanism; this is a plain value type with no framework coupling.
 */
public record DeckPrincipal(UUID tokenId, UUID pollId, String label) {

  /**
   * SecurityIdentity role granted by a valid deck token; matches {@code role-deck.roles-allowed}.
   */
  public static final String ROLE = "DECK";

  /**
   * Attribute key under which a {@link DeckPrincipal} is stored on the {@code SecurityIdentity}.
   */
  public static final String ATTRIBUTE = "deckPrincipal";
}
