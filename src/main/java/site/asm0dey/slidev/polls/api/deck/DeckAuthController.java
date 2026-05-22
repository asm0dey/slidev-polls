package site.asm0dey.slidev.polls.api.deck;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import java.util.Comparator;
import java.util.List;
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
 *   <li>{@code GET /me} — validates a freshly-pasted / rehydrated bearer; authenticated by the
 *       deck-token mechanism (role {@code DECK}).
 *   <li>{@code POST /login} — presenter supplies username+password (BUG-002, parity with the admin
 *       UI credential flow). We authenticate via the shared {@link IdentityProviderManager}, pick
 *       the presenter's most-recent poll, mint a fresh deck token, and return its plaintext so the
 *       addon can persist it under the usual {@code slidev-polls:deck-auth} key. This endpoint is
 *       permit-all because the caller has no deck token yet.
 * </ul>
 */
@Path("/api/deck/auth")
@ApplicationScoped
@RunOnVirtualThread
public class DeckAuthController {

  private final IdentityProviderManager idm;
  private final PollService pollService;
  private final DeckTokenService deckTokenService;

  public DeckAuthController(
      IdentityProviderManager idm, PollService pollService, DeckTokenService deckTokenService) {
    this.idm = idm;
    this.pollService = pollService;
    this.deckTokenService = deckTokenService;
  }

  @GET
  @Path("/me")
  @RolesAllowed("DECK")
  public DeckPrincipalView me(@Context SecurityIdentity identity) {
    DeckPrincipal principal = (DeckPrincipal) identity.getAttribute(DeckPrincipal.ATTRIBUTE);
    return new DeckPrincipalView(principal.tokenId(), principal.pollId(), principal.label());
  }

  @POST
  @Path("/login")
  public DeckLoginResponse login(@Valid DeckLoginRequest body) {
    // Authenticate against the same admin credential store. A bad credential fails the Uni with
    // AuthenticationFailedException, which SecurityProblemMappers maps to a 401 without leaking
    // internal wording — same shape as the old BadDeckCredentials → 401 path.
    SecurityIdentity authenticated =
        idm.authenticate(
                new UsernamePasswordAuthenticationRequest(
                    body.username(), new PasswordCredential(body.password().toCharArray())))
            .await()
            .indefinitely();
    String username = authenticated.getPrincipal().getName();
    List<Poll> polls = pollService.listForOwner(username);
    if (polls.isEmpty()) {
      throw new AuthenticationFailedException();
    }
    Poll poll =
        polls.stream()
            .max(Comparator.comparing(Poll::createdAt))
            .orElseThrow(AuthenticationFailedException::new);
    DeckTokenService.Minted minted = deckTokenService.mint(poll.id(), username, "deck");
    return new DeckLoginResponse(
        minted.plaintext(), minted.token().id(), minted.token().pollId(), minted.token().label());
  }
}
