package site.asm0dey.slidev.polls.api.public_;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.public_.dto.VoteAccepted;
import site.asm0dey.slidev.polls.api.public_.dto.VoteRequest;
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
@RestController
@RequestMapping("/api/polls")
public class VoteController {

  private final VoteService voteService;

  public VoteController(VoteService voteService) {
    this.voteService = voteService;
  }

  @PostMapping("/{slug}/votes")
  public ResponseEntity<VoteAccepted> submit(
      @PathVariable String slug, @Valid @RequestBody VoteRequest body, HttpServletRequest request) {
    if (slug == null || !SlugValidator.isValidFormat(slug)) {
      // Match PublicPollController's rejection: unparseable slug at the edge is 404 NOT_FOUND;
      // no point hitting the service only to fail the same way.
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }

    VoterTokenCookie.Resolution voter = VoterTokenCookie.readOrIssue(request);
    Vote recorded = voteService.recordVote(slug, body.optionId(), voter.token());

    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (voter.setCookieHeader() != null) {
      response.header(HttpHeaders.SET_COOKIE, voter.setCookieHeader());
    }
    return response.body(new VoteAccepted(recorded.id(), recorded.createdAt()));
  }
}
