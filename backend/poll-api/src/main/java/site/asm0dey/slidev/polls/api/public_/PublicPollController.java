package site.asm0dey.slidev.polls.api.public_;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.public_.dto.PublicPollView;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.VoteService;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;

/**
 * Anonymous, auth-free read surface backing the voter SPA. {@code GET /api/polls/by-slug/{slug}}
 * returns a {@link PublicPollView} that collapses the poll/question lifecycle down to what the
 * voter needs (state = WAITING or ACTIVE, the active question if any, an {@code alreadyVoted} hint
 * derived from the {@code sp_voter} cookie).
 *
 * <p>Slug format is validated from the path on the way in (T088 / {@code @TS-045}); a malformed
 * slug surfaces as {@link NotFoundException} (404 with {@code NOT_FOUND}) — {@code @TS-045} accepts
 * 400 or 404 and 404 reads cleanly to the audience ("no such poll") without leaking a
 * validation-error message that would be meaningless outside the dev loop.
 *
 * <p>On any request that did not carry a valid {@code sp_voter} cookie, the controller mints one
 * and attaches a Set-Cookie header so the subsequent {@code POST /votes} sees a stable identity
 * ({@code @TS-046}).
 */
@RestController
@RequestMapping("/api/polls")
public class PublicPollController {

  private final PollRepository pollRepository;
  private final VoteService voteService;

  public PublicPollController(PollRepository pollRepository, VoteService voteService) {
    this.pollRepository = pollRepository;
    this.voteService = voteService;
  }

  @GetMapping("/by-slug/{slug}")
  public ResponseEntity<PublicPollView> getBySlug(
      @PathVariable String slug, HttpServletRequest request) {
    // @TS-045 — reject an unparseable slug at the edge; do not hit the repository.
    if (slug == null || !SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    Poll poll =
        pollRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("no poll with slug '" + slug + "'"));

    VoterTokenCookie.Resolution voter = VoterTokenCookie.readOrIssue(request);
    Boolean alreadyVoted = null;
    if (poll.activeQuestionId() != null) {
      // Best-effort: report true only when the server has an identity AND has seen a vote from it
      // on the current active question. A freshly-minted cookie has no history, so the hint is
      // false for the first visit.
      alreadyVoted = voteService.alreadyVoted(poll.activeQuestionId(), voter.token());
    }
    PublicPollView body = PublicPollView.from(poll, alreadyVoted);

    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (voter.setCookieHeader() != null) {
      response.header(HttpHeaders.SET_COOKIE, voter.setCookieHeader());
    }
    return response.body(body);
  }
}
