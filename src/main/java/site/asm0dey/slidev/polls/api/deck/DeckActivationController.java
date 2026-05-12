package site.asm0dey.slidev.polls.api.deck;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.security.DeckPrincipal;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.DeckTokenPollMismatchException;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Deck-driven activation — the Slidev addon POSTs here when a slide embedding {@code
 * <PollResults/>} mounts with both {@code questionId} and {@code deckToken}. The caller is
 * authenticated by {@link site.asm0dey.slidev.polls.api.security.DeckTokenAuthenticationFilter} and
 * the resulting {@link DeckPrincipal} carries the {@code pollId} the token was minted against, so
 * we can reject a cross-poll token before the service is touched ({@code @TS-054}).
 *
 * <p>Activation is idempotent at the service level — re-mounting the same slide does not rotate
 * {@code activated_at} or refire a snapshot ({@code @TS-052}).
 */
@RestController
@RequestMapping("/api/deck/polls/{pollId}")
public class DeckActivationController {

  private final PollService pollService;

  public DeckActivationController(PollService pollService) {
    this.pollService = pollService;
  }

  @PostMapping("/activate")
  public DeckActivatedResponse activate(
      @PathVariable UUID pollId,
      @RequestBody ActivateRequest body,
      @AuthenticationPrincipal DeckPrincipal principal) {
    if (!principal.pollId().equals(pollId)) {
      throw new DeckTokenPollMismatchException(
          "deck token " + principal.tokenId() + " is not scoped to poll " + pollId);
    }
    Poll after = pollService.activateQuestion(pollId, body.questionId());
    return new DeckActivatedResponse(pollId, after.activeQuestionId());
  }

  @PostMapping("/close")
  public DeckActivatedResponse close(
      @PathVariable UUID pollId, @AuthenticationPrincipal DeckPrincipal principal) {
    if (!principal.pollId().equals(pollId)) {
      throw new DeckTokenPollMismatchException(
          "deck token " + principal.tokenId() + " is not scoped to poll " + pollId);
    }
    Poll after = pollService.closeActiveQuestion(pollId);
    return new DeckActivatedResponse(pollId, after.activeQuestionId());
  }

  public record ActivateRequest(UUID questionId) {}

  public record DeckActivatedResponse(UUID pollId, UUID activeQuestionId) {}
}
