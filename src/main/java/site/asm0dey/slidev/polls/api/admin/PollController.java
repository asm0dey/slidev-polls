package site.asm0dey.slidev.polls.api.admin;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.admin.dto.ActivateQuestionRequest;
import site.asm0dey.slidev.polls.api.admin.dto.CreatePollRequest;
import site.asm0dey.slidev.polls.api.admin.dto.PollDetailDto;
import site.asm0dey.slidev.polls.api.admin.dto.PollDto;
import site.asm0dey.slidev.polls.api.admin.dto.UpdatePollRequest;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Presenter-authored poll lifecycle as exposed over HTTP. Every endpoint delegates straight through
 * to {@link PollService} — the controller is a thin boundary concerned only with JAX-RS wiring, DTO
 * mapping, and pulling the authenticated presenter's username from the {@link SecurityIdentity}.
 *
 * <p>FR-001/ @TS-040/ @TS-041 ownership enforcement lives on the service's {@code ForOwner}
 * methods; {@code NotOwnerException} comes back through {@code DomainExceptionMappers} as 403
 * {@code FORBIDDEN}. Slug validation is similarly delegated — {@code SLUG_INVALID}, {@code
 * SLUG_RESERVED}, {@code SLUG_TAKEN} come out of the service as typed exceptions and land on the
 * caller with the right code, matching the OpenAPI 409 responses.
 *
 * <p>{@code publicUrlBase} is assembled from the request at response time via {@link
 * PublicUrlBase#of(RoutingContext)} so the join link always reflects the reverse-proxy host the
 * presenter actually hit; no config property is threaded through the call.
 *
 * <p>Per-question {@code voteCount} is fetched out-of-band via {@link
 * PollRepository#voteCountByQuestion(UUID)} on every detail response so the backoffice can lock
 * structural edits once any ballot has been cast. The freshly-created path skips the query (counts
 * are zero by construction).
 */
@Path("/api/admin/polls")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed("ADMIN")
public class PollController {

  private final PollService pollService;
  private final PollRepository pollRepository;

  public PollController(PollService pollService, PollRepository pollRepository) {
    this.pollService = pollService;
    this.pollRepository = pollRepository;
  }

  @GET
  public List<PollDto> list(@Context SecurityIdentity identity, @Context RoutingContext request) {
    String owner = owner(identity);
    String base = PublicUrlBase.of(request);
    return pollService.listForOwner(owner).stream().map(p -> PollDto.from(p, base)).toList();
  }

  @POST
  public RestResponse<PollDetailDto> create(
      @Valid CreatePollRequest body,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll created = pollService.create(owner(identity), body.toCommand());
    // Freshly-created poll: no votes yet, so skip the lookup and let PollDetailDto default to 0.
    return RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, PollDetailDto.from(created, PublicUrlBase.of(request)))
        .build();
  }

  @GET
  @Path("/{pollId}")
  public PollDetailDto get(
      @PathParam("pollId") UUID pollId,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll poll = pollService.getForOwner(pollId, owner(identity));
    return PollDetailDto.from(poll, PublicUrlBase.of(request), counts(pollId));
  }

  @PATCH
  @Path("/{pollId}")
  public PollDetailDto update(
      @PathParam("pollId") UUID pollId,
      @Valid UpdatePollRequest body,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll updated = pollService.updateForOwner(pollId, owner(identity), body.toCommand());
    return PollDetailDto.from(updated, PublicUrlBase.of(request), counts(pollId));
  }

  @POST
  @Path("/{pollId}/clone")
  public RestResponse<PollDetailDto> clone(
      @PathParam("pollId") UUID pollId,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll cloned = pollService.cloneForOwner(pollId, owner(identity));
    // Clone produces a brand-new poll with no votes.
    return RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, PollDetailDto.from(cloned, PublicUrlBase.of(request)))
        .build();
  }

  @POST
  @Path("/{pollId}/votes:clear")
  public PollDetailDto clearVotes(
      @PathParam("pollId") UUID pollId,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll after = pollService.clearVotesForOwner(pollId, owner(identity));
    // votes:clear empties the votes table for this poll; counts are zero by construction.
    return PollDetailDto.from(after, PublicUrlBase.of(request));
  }

  @DELETE
  @Path("/{pollId}")
  public RestResponse<Void> delete(
      @PathParam("pollId") UUID pollId, @Context SecurityIdentity identity) {
    pollService.deleteForOwner(pollId, owner(identity));
    return RestResponse.noContent();
  }

  @POST
  @Path("/{pollId}/open")
  public PollDetailDto open(
      @PathParam("pollId") UUID pollId,
      @Valid ActivateQuestionRequest body,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll after = pollService.activateQuestionForOwner(pollId, owner(identity), body.questionId());
    return PollDetailDto.from(after, PublicUrlBase.of(request), counts(pollId));
  }

  @POST
  @Path("/{pollId}/close")
  public PollDetailDto close(
      @PathParam("pollId") UUID pollId,
      @Context SecurityIdentity identity,
      @Context RoutingContext request) {
    Poll after = pollService.closeActiveQuestionForOwner(pollId, owner(identity));
    return PollDetailDto.from(after, PublicUrlBase.of(request), counts(pollId));
  }

  private Map<UUID, Long> counts(UUID pollId) {
    return pollRepository.voteCountByQuestion(pollId);
  }

  private static String owner(SecurityIdentity identity) {
    if (identity == null
        || identity.getPrincipal() == null
        || identity.getPrincipal().getName() == null) {
      // Security config should have rejected an unauthenticated request before this point; if we
      // ever get here it means the permission policy drifted — surface loudly rather than silently
      // mutate.
      throw new IllegalStateException("no authenticated presenter on the request");
    }
    return identity.getPrincipal().getName();
  }
}
