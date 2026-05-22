package site.asm0dey.slidev.polls.api.deck;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import java.util.UUID;
import site.asm0dey.slidev.polls.api.security.DeckPrincipal;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.DeckTokenPollMismatchException;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Deck-driven activation — the Slidev addon POSTs here when a slide embedding {@code
 * <PollResults/>} mounts with both {@code questionId} and {@code deckToken}. The caller is
 * authenticated by the deck-token mechanism and the resulting {@link DeckPrincipal} carries the
 * {@code pollId} the token was minted against, so we can reject a cross-poll token before the
 * service is touched ({@code @TS-054}).
 *
 * <p>Activation is idempotent at the service level — re-mounting the same slide does not rotate
 * {@code activated_at} or refire a snapshot ({@code @TS-052}).
 */
@Path("/api/deck/polls/{pollId}")
@ApplicationScoped
@RunOnVirtualThread
public class DeckActivationController {

  private final PollService pollService;

  public DeckActivationController(PollService pollService) {
    this.pollService = pollService;
  }

  @POST
  @Path("/activate")
  @RolesAllowed("DECK")
  public DeckActivatedResponse activate(
      @PathParam("pollId") UUID pollId, ActivateRequest body, @Context SecurityIdentity identity) {
    DeckPrincipal principal = deckPrincipal(identity);
    if (!principal.pollId().equals(pollId)) {
      throw new DeckTokenPollMismatchException(
          "deck token " + principal.tokenId() + " is not scoped to poll " + pollId);
    }
    Poll after = pollService.activateQuestion(pollId, body.questionId());
    return new DeckActivatedResponse(pollId, after.activeQuestionId());
  }

  @POST
  @Path("/close")
  @RolesAllowed("DECK")
  public DeckActivatedResponse close(
      @PathParam("pollId") UUID pollId, CloseRequest body, @Context SecurityIdentity identity) {
    DeckPrincipal principal = deckPrincipal(identity);
    if (!principal.pollId().equals(pollId)) {
      throw new DeckTokenPollMismatchException(
          "deck token " + principal.tokenId() + " is not scoped to poll " + pollId);
    }
    // Body carries the questionId the caller believes is active. When supplied, the
    // service no-ops if a different question has since become active — prevents a
    // slide-leave close from racing past the next slide's activate and clobbering it.
    UUID expected = body == null ? null : body.questionId();
    Poll after = pollService.closeActiveQuestion(pollId, expected);
    return new DeckActivatedResponse(pollId, after.activeQuestionId());
  }

  private static DeckPrincipal deckPrincipal(SecurityIdentity identity) {
    return (DeckPrincipal) identity.getAttribute(DeckPrincipal.ATTRIBUTE);
  }

  public record ActivateRequest(UUID questionId) {}

  public record CloseRequest(UUID questionId) {}

  public record DeckActivatedResponse(UUID pollId, UUID activeQuestionId) {}
}
