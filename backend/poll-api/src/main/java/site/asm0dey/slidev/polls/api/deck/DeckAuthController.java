package site.asm0dey.slidev.polls.api.deck;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.deck.dto.DeckPrincipalView;
import site.asm0dey.slidev.polls.api.security.DeckPrincipal;

/**
 * Deck-token self-inspection endpoint used by the Slidev addon's in-deck auth control to validate a
 * freshly-pasted token and to re-validate on page reload (feature 002, FR-003, FR-005).
 *
 * <p>Authentication is provided by {@link
 * site.asm0dey.slidev.polls.api.security.DeckTokenAuthenticationFilter}; the controller is reached
 * only once a {@link DeckPrincipal} is on the security context. A missing / unknown / revoked
 * bearer never gets here — the filter leaves the context anonymous and {@code
 * ProblemAuthenticationEntryPoint} emits {@code DECK_TOKEN_INVALID}. The endpoint is
 * side-effect-free (idempotent GET) so it can be used as a probe.
 */
@RestController
@RequestMapping("/api/deck/auth")
public class DeckAuthController {

  @GetMapping("/me")
  public DeckPrincipalView me(@AuthenticationPrincipal DeckPrincipal principal) {
    return new DeckPrincipalView(principal.tokenId(), principal.pollId(), principal.label());
  }
}
