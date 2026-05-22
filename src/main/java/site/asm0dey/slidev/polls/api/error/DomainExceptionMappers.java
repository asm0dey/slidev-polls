package site.asm0dey.slidev.polls.api.error;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.DeckTokenInvalidException;
import site.asm0dey.slidev.polls.core.error.DeckTokenPollMismatchException;
import site.asm0dey.slidev.polls.core.error.InvalidOriginException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;
import site.asm0dey.slidev.polls.core.error.ResourceHasVotesException;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.SlugInvalidException;
import site.asm0dey.slidev.polls.core.error.SlugReservedException;
import site.asm0dey.slidev.polls.core.error.SlugTakenException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

/**
 * JAX-RS replacement for the Spring {@code GlobalExceptionHandler}. Every
 * {@code @ServerExceptionMapper} method turns one domain exception into a {@link Problem} envelope
 * whose {@link ProblemCode} matches FR-017 and the OpenAPI schema. The {@code correlationId} is
 * read from the JBoss-logging MDC entry set by {@link CorrelationIdFilter}, so the log line written
 * alongside the failure and the body returned to the caller share the same lookup key (Principle
 * VI, @TS-042).
 *
 * <p>Authentication / authorization failures raised by the Quarkus security layer are owned by
 * {@code api.security.SecurityProblemMappers} (Phase 4b); they are intentionally not duplicated
 * here. Bean-validation and unreadable-body failures live in {@code RequestValidationMappers}.
 */
public class DomainExceptionMappers {

  private static final Logger LOG = Logger.getLogger(DomainExceptionMappers.class);

  @ServerExceptionMapper
  public RestResponse<Problem> mapNotFound(NotFoundException ex) {
    return respond(Response.Status.NOT_FOUND, ProblemCode.NOT_FOUND, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapNotOwner(NotOwnerException ex) {
    // A presenter authenticated, but the poll belongs to someone else. FORBIDDEN is the right
    // classification, same as a missing-permission case.
    return respond(Response.Status.FORBIDDEN, ProblemCode.FORBIDDEN, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapAlreadyVoted(AlreadyVotedException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.ALREADY_VOTED, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapQuestionNotActive(QuestionNotActiveException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.QUESTION_NOT_ACTIVE, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapActivationRejected(ActivationRejectedException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.ACTIVATION_REJECTED, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapResourceHasVotes(ResourceHasVotesException ex) {
    String key = ex.kind() + "." + ex.resourceId();
    return respond(
        Response.Status.CONFLICT,
        ProblemCode.RESOURCE_HAS_VOTES,
        safe(ex),
        Map.of(key, List.of(safe(ex))));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapSlugTaken(SlugTakenException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.SLUG_TAKEN, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapSetupLocked(SetupLockedException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.SETUP_LOCKED, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapUsernameTaken(UsernameTakenException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.USERNAME_TAKEN, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapSlugInvalid(SlugInvalidException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.SLUG_INVALID, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapSlugReserved(SlugReservedException ex) {
    return respond(Response.Status.CONFLICT, ProblemCode.SLUG_RESERVED, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapDeckTokenInvalid(DeckTokenInvalidException ex) {
    return respond(Response.Status.UNAUTHORIZED, ProblemCode.DECK_TOKEN_INVALID, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapDeckTokenPollMismatch(DeckTokenPollMismatchException ex) {
    return respond(Response.Status.FORBIDDEN, ProblemCode.DECK_TOKEN_POLL_MISMATCH, safe(ex));
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapInvalidOrigin(InvalidOriginException ex) {
    return respond(Response.Status.BAD_REQUEST, ProblemCode.ORIGIN_INVALID, safe(ex));
  }

  // Service-layer rejections of malformed inputs (e.g. a multi-option ballot on a single-choice
  // question, or a duplicate option in a ballot) surface here. They are semantic 400s — the body
  // parsed and bind-validated cleanly, but the values violate a domain rule the service is closer
  // to than the DTO. The message is authored in our own code, so safe() is fine; we do not key the
  // entry to a field because the violations span the whole optionIds array.
  //
  // No overlap with bean-validation: ResteasyReactiveViolationException extends
  // jakarta.validation.ConstraintViolationException, not IllegalArgumentException, so the two
  // mappers select disjoint exception hierarchies.
  @ServerExceptionMapper
  public RestResponse<Problem> mapIllegalArgument(IllegalArgumentException ex) {
    return respond(Response.Status.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, safe(ex));
  }

  // Catch-all for genuinely unexpected faults. Matches the old handler's 500 TRANSPORT_FAILURE.
  // The Spring version special-cased text/event-stream responses (an SseEmitter async fault) to
  // skip JSON serialisation — that case is dropped here: JAX-RS SSE owns its own error path and a
  // dropped subscriber never reaches a @ServerExceptionMapper on the resource method.
  //
  // This does NOT blindly swallow security/JAX-RS exceptions: more specific @ServerExceptionMapper
  // methods (here, in SecurityProblemMappers, and the framework's WebApplicationException handling)
  // win because resteasy-reactive resolves the most specific declared type first.
  @ServerExceptionMapper
  public RestResponse<Problem> mapUnexpected(Exception ex) {
    LOG.error("unexpected server fault", ex);
    return respond(
        Response.Status.INTERNAL_SERVER_ERROR,
        ProblemCode.TRANSPORT_FAILURE,
        "an unexpected error occurred");
  }

  static RestResponse<Problem> respond(Response.Status status, ProblemCode code, String msg) {
    return respond(status, code, msg, null);
  }

  static RestResponse<Problem> respond(
      Response.Status status, ProblemCode code, String msg, Map<String, List<String>> errors) {
    Object correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    Problem body =
        new Problem(code, msg, correlationId == null ? null : correlationId.toString(), errors);
    return RestResponse.ResponseBuilder.create(status, body)
        .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
        .build();
  }

  /**
   * Exception messages authored in our own domain code are safe to surface; this falls back to a
   * neutral phrase when the exception carries no message.
   */
  static String safe(Exception ex) {
    String msg = ex.getMessage();
    return (msg == null || msg.isBlank()) ? "request could not be completed" : msg;
  }
}
