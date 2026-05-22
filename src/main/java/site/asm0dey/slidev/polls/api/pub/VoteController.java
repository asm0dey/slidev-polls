package site.asm0dey.slidev.polls.api.pub;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.pub.dto.VoteAccepted;
import site.asm0dey.slidev.polls.api.pub.dto.VoteRequest;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.VoteService;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;

/**
 * Anonymous, auth-free write surface backing the voter SPA. {@code POST /api/polls/{slug}/votes}
 * accepts a {@link VoteRequest} (plus the {@code sp_voter} cookie) and returns a {@link
 * VoteAccepted} on success.
 *
 * <p>Voter identity is server-authoritative per the tasks.md clarification on T086: the cookie is
 * the source of truth for duplicate-vote detection. If the request arrived without a valid {@code
 * sp_voter} cookie the controller mints one (same shape as {@link PublicPollController}'s path),
 * uses that fresh value as the voter token, and attaches a {@code Set-Cookie} header to the
 * response so the next submission sees a stable identity. The {@code voterToken} in the body is
 * tolerated for OpenAPI-schema compatibility but ignored when the cookie is present.
 *
 * <p>Unknown top-level fields are silently dropped by Jackson ({@code fail-on-unknown-properties:
 * false} in {@code application.yml}) so {@code @TS-027} / {@code @TS-046} "extra fields are not
 * persisted" holds at the deserialisation boundary — by the time the request reaches the service
 * layer any {@code email}/{@code name}/other PII-shaped field has already been discarded.
 */
@Path("/api/polls")
@ApplicationScoped
@RunOnVirtualThread
public class VoteController {

  private final VoteService voteService;

  public VoteController(VoteService voteService) {
    this.voteService = voteService;
  }

  @POST
  @Path("/{slug}/votes")
  public RestResponse<VoteAccepted> submit(
      @PathParam("slug") String slug, @Valid VoteRequest body, @Context RoutingContext ctx) {
    if (!SlugValidator.isValidFormat(slug)) {
      // Match PublicPollController's rejection: unparseable slug at the edge is 404 NOT_FOUND;
      // no point hitting the service only to fail the same way.
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }

    VoterTokenCookie.Resolution voter = VoterTokenCookie.readOrIssue(ctx);
    // {@code body.optionIds()} is @NotNull-validated at the binding boundary, so a legacy
    // {"optionId": "..."} payload (which Jackson silently swallows under
    // fail-on-unknown-properties: false) has already been rejected as 400 VALIDATION_FAILED by
    // the time we get here. An empty list is an abstention; the service enforces arity bounds.
    Vote recorded = voteService.recordVote(slug, body.optionIds(), voter.token());

    RestResponse.ResponseBuilder<VoteAccepted> response =
        RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, new VoteAccepted(recorded.id(), recorded.createdAt()));
    if (voter.setCookieHeader() != null) {
      response.header(HttpHeaders.SET_COOKIE, voter.setCookieHeader());
    }
    return response.build();
  }

  @DELETE
  @Path("/{slug}/votes")
  public RestResponse<Void> retract(@PathParam("slug") String slug, @Context RoutingContext ctx) {
    if (!SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    String voterToken = VoterTokenCookie.read(ctx);
    if (voterToken == null) {
      // No cookie → no row this voter could ever own. Return 204 without touching the service.
      return RestResponse.noContent();
    }
    voteService.retractVote(slug, voterToken);
    return RestResponse.noContent();
  }
}
