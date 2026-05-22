package site.asm0dey.slidev.polls.api.pub;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Instant;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.pub.dto.PublicPollView;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.VoteService;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotBuilder;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotPayload;

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
@Path("/api/polls")
@ApplicationScoped
@RunOnVirtualThread
public class PublicPollController {

  private final PollRepository pollRepository;
  private final VoteService voteService;
  private final SnapshotBuilder snapshots;

  public PublicPollController(
      PollRepository pollRepository, VoteService voteService, SnapshotBuilder snapshots) {
    this.pollRepository = pollRepository;
    this.voteService = voteService;
    this.snapshots = snapshots;
  }

  @GET
  @Path("/by-slug/{slug}")
  public RestResponse<PublicPollView> getBySlug(
      @PathParam("slug") String slug, @Context RoutingContext ctx) {
    // @TS-045 — reject an unparseable slug at the edge; do not hit the repository.
    if (!SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    Poll poll =
        pollRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("no poll with slug '" + slug + "'"));

    VoterTokenCookie.Resolution voter = VoterTokenCookie.readOrIssue(ctx);
    Boolean alreadyVoted = null;
    if (poll.activeQuestionId() != null) {
      // Best-effort: report true only when the server has an identity AND has seen a vote from it
      // on the current active question. A freshly-minted cookie has no history, so the hint is
      // false for the first visit.
      alreadyVoted = voteService.alreadyVoted(poll.activeQuestionId(), voter.token());
    }
    PublicPollView body = PublicPollView.from(poll, alreadyVoted);

    RestResponse.ResponseBuilder<PublicPollView> response = RestResponse.ResponseBuilder.ok(body);
    if (voter.setCookieHeader() != null) {
      response.header(HttpHeaders.SET_COOKIE, voter.setCookieHeader());
    }
    return response.build();
  }

  /**
   * Anonymous, snapshot-shaped view of a specific question regardless of its lifecycle status. The
   * SSE stream only surfaces the currently-active question, so a deck slide pinned to a CLOSED
   * question would otherwise render the "waiting" placeholder forever. This endpoint lets the panel
   * render historical results without requiring the presenter to re-activate. The {@code
   * activeQuestion} field in the response is populated from the requested question; callers
   * treating it as "active" without inspecting status would be misled, which is acceptable because
   * the panel only cares about prompt/options/tally for the requested id.
   */
  @GET
  @Path("/{slug}/questions/{questionId}/snapshot")
  public SnapshotPayload questionSnapshot(
      @PathParam("slug") String slug, @PathParam("questionId") UUID questionId) {
    if (!SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    Poll poll =
        pollRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("no poll with slug '" + slug + "'"));
    return snapshots
        .buildForQuestion(poll, questionId, Instant.now())
        .orElseThrow(
            () ->
                new NotFoundException(
                    "no question " + questionId + " in poll with slug '" + slug + "'"));
  }
}
