package site.asm0dey.slidev.polls.api.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Principal attached by {@link DeckTokenAuthenticationFilter} once it matches an unrevoked {@code
 * deck_tokens} row for the incoming {@code X-Deck-Token} header. Carries the {@code pollId} the
 * token was minted against so {@code DeckActivationController} can enforce the poll-scope invariant
 * ({@code @TS-054}).
 *
 * <p>The authority {@code ROLE_DECK} is deliberately narrow — {@code SecurityConfig} only permits
 * it on {@code /api/deck/**}, so the token cannot unlock any other admin surface ({@code @TS-057}).
 */
public record DeckPrincipal(UUID tokenId, UUID pollId) {

  public static final String ROLE = "ROLE_DECK";

  public Collection<GrantedAuthority> authorities() {
    return List.of(new SimpleGrantedAuthority(ROLE));
  }
}
