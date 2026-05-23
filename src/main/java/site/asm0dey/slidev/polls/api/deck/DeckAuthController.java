package site.asm0dey.slidev.polls.api.deck;

import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.deck.dto.DeckLoginRequest;
import site.asm0dey.slidev.polls.api.deck.dto.DeckLoginResponse;
import site.asm0dey.slidev.polls.api.deck.dto.DeckPrincipalView;
import site.asm0dey.slidev.polls.api.security.DeckPrincipal;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Deck-token self-inspection and sign-in endpoints used by the Slidev addon's in-deck auth control.
 *
 * <ul>
 *   <li>{@code GET /me} — validates a freshly-pasted / rehydrated bearer; authenticated by {@link
 *       site.asm0dey.slidev.polls.api.security.DeckTokenAuthenticationFilter}.
 *   <li>{@code POST /login} — presenter supplies username+password (BUG-002, parity with the admin
 *       UI credential flow). We authenticate via the shared {@link AuthenticationManager}, pick the
 *       presenter's most-recent poll, mint a fresh deck token, and return its plaintext so the
 *       addon can persist it under the usual {@code slidev-polls:deck-auth} key.
 * </ul>
 */
@RestController
@RequestMapping("/api/deck/auth")
public class DeckAuthController {

  private final AuthenticationManager authenticationManager;
  private final PollService pollService;
  private final DeckTokenService deckTokenService;

  public DeckAuthController(
      AuthenticationManager authenticationManager,
      PollService pollService,
      DeckTokenService deckTokenService) {
    this.authenticationManager = authenticationManager;
    this.pollService = pollService;
    this.deckTokenService = deckTokenService;
  }

  @GetMapping("/me")
  public DeckPrincipalView me(@AuthenticationPrincipal DeckPrincipal principal) {
    return new DeckPrincipalView(principal.tokenId(), principal.pollId(), principal.label());
  }

  @PostMapping("/login")
  public ResponseEntity<DeckLoginResponse> login(@Valid @RequestBody DeckLoginRequest body) {
    Authentication authenticated;
    try {
      authenticated =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  body.username(), body.password()));
    } catch (BadCredentialsException ex) {
      throw new BadDeckCredentialsException("invalid username or password", ex);
    }
    String username = authenticated.getName();
    // Owned OR collaborated polls — a collaborator who owns nothing must still be able to mint a
    // deck token for a poll shared with them (mint() authorizes editors, not just owners).
    List<Poll> polls = pollService.listVisibleTo(username);
    if (polls.isEmpty()) {
      throw new BadDeckCredentialsException("presenter has no accessible poll", null);
    }
    Poll poll =
        polls.stream()
            .max(Comparator.comparing(Poll::createdAt))
            .orElseThrow(() -> new BadDeckCredentialsException("no poll available", null));
    DeckTokenService.Minted minted = deckTokenService.mint(poll.id(), username, "deck");
    DeckLoginResponse payload =
        new DeckLoginResponse(
            minted.plaintext(),
            minted.token().id(),
            minted.token().pollId(),
            minted.token().label());
    return ResponseEntity.ok(payload);
  }

  /**
   * Wraps credential-rejection so {@code GlobalExceptionHandler}'s {@link AuthenticationException}
   * branch emits a consistent 401 without leaking Spring Security's internal "Bad credentials"
   * wording.
   */
  static final class BadDeckCredentialsException extends AuthenticationException {
    BadDeckCredentialsException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }
}
