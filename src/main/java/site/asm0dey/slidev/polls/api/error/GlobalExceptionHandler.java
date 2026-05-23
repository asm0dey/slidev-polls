package site.asm0dey.slidev.polls.api.error;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.AdminRequiredException;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.CannotBlockException;
import site.asm0dey.slidev.polls.core.error.CannotShareWithOwnerException;
import site.asm0dey.slidev.polls.core.error.CollaboratorExistsException;
import site.asm0dey.slidev.polls.core.error.CurrentPasswordMismatchException;
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
 * Single source of truth for how domain exceptions become HTTP responses. Every handler emits a
 * {@link Problem} whose {@link ProblemCode} matches FR-017 and the OpenAPI schema. The {@code
 * correlationId} is read from the MDC entry set by {@link CorrelationIdFilter}, so the log line
 * written alongside the failure and the body returned to the caller share the same lookup key
 * (Principle VI, @TS-042).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<Problem> handleAuth() {
    return respond(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_REQUIRED, "authentication required");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Problem> handleForbidden() {
    return respond(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN, "access denied");
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Problem> handleNotFound(NotFoundException ex) {
    return respond(HttpStatus.NOT_FOUND, ProblemCode.NOT_FOUND, safe(ex));
  }

  @ExceptionHandler(NotOwnerException.class)
  ResponseEntity<Problem> handleNotOwner(NotOwnerException ex) {
    // A presenter authenticated, but the poll belongs to someone else. Same HTTP
    // status as a missing-permission case; FORBIDDEN is the right classification.
    return respond(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN, safe(ex));
  }

  @ExceptionHandler(AdminRequiredException.class)
  ResponseEntity<Problem> handleAdminRequired(AdminRequiredException ex) {
    return respond(HttpStatus.FORBIDDEN, ProblemCode.ADMIN_REQUIRED, safe(ex));
  }

  @ExceptionHandler(CurrentPasswordMismatchException.class)
  ResponseEntity<Problem> handleCurrentPasswordMismatch(CurrentPasswordMismatchException ex) {
    return respond(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN, safe(ex));
  }

  @ExceptionHandler(AlreadyVotedException.class)
  ResponseEntity<Problem> handleAlreadyVoted(AlreadyVotedException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.ALREADY_VOTED, safe(ex));
  }

  @ExceptionHandler(QuestionNotActiveException.class)
  ResponseEntity<Problem> handleQuestionNotActive(QuestionNotActiveException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.QUESTION_NOT_ACTIVE, safe(ex));
  }

  @ExceptionHandler(ActivationRejectedException.class)
  ResponseEntity<Problem> handleActivationRejected(ActivationRejectedException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.ACTIVATION_REJECTED, safe(ex));
  }

  @ExceptionHandler(ResourceHasVotesException.class)
  ResponseEntity<Problem> handleResourceHasVotes(ResourceHasVotesException ex) {
    String key = ex.kind() + "." + ex.resourceId();
    return respond(
        HttpStatus.CONFLICT,
        ProblemCode.RESOURCE_HAS_VOTES,
        safe(ex),
        Map.of(key, List.of(safe(ex))));
  }

  @ExceptionHandler(CollaboratorExistsException.class)
  ResponseEntity<Problem> handleCollaboratorExists(CollaboratorExistsException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.COLLABORATOR_EXISTS, safe(ex));
  }

  @ExceptionHandler(CannotShareWithOwnerException.class)
  ResponseEntity<Problem> handleCannotShareWithOwner(CannotShareWithOwnerException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.CANNOT_SHARE_WITH_OWNER, safe(ex));
  }

  @ExceptionHandler(CannotBlockException.class)
  ResponseEntity<Problem> handleCannotBlock(CannotBlockException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.USER_BLOCKED, safe(ex));
  }

  @ExceptionHandler(SlugTakenException.class)
  ResponseEntity<Problem> handleSlugTaken(SlugTakenException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.SLUG_TAKEN, safe(ex));
  }

  @ExceptionHandler(SetupLockedException.class)
  ResponseEntity<Problem> handleSetupLocked(SetupLockedException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.SETUP_LOCKED, safe(ex));
  }

  @ExceptionHandler(UsernameTakenException.class)
  ResponseEntity<Problem> handleUsernameTaken(UsernameTakenException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.USERNAME_TAKEN, safe(ex));
  }

  @ExceptionHandler(SlugInvalidException.class)
  ResponseEntity<Problem> handleSlugInvalid(SlugInvalidException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.SLUG_INVALID, safe(ex));
  }

  @ExceptionHandler(SlugReservedException.class)
  ResponseEntity<Problem> handleSlugReserved(SlugReservedException ex) {
    return respond(HttpStatus.CONFLICT, ProblemCode.SLUG_RESERVED, safe(ex));
  }

  @ExceptionHandler(DeckTokenInvalidException.class)
  ResponseEntity<Problem> handleDeckTokenInvalid(DeckTokenInvalidException ex) {
    return respond(HttpStatus.UNAUTHORIZED, ProblemCode.DECK_TOKEN_INVALID, safe(ex));
  }

  @ExceptionHandler(DeckTokenPollMismatchException.class)
  ResponseEntity<Problem> handleDeckTokenPollMismatch(DeckTokenPollMismatchException ex) {
    return respond(HttpStatus.FORBIDDEN, ProblemCode.DECK_TOKEN_POLL_MISMATCH, safe(ex));
  }

  @ExceptionHandler(InvalidOriginException.class)
  ResponseEntity<Problem> handleInvalidOrigin(InvalidOriginException ex) {
    return respond(HttpStatus.BAD_REQUEST, ProblemCode.ORIGIN_INVALID, safe(ex));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException ex) {
    // Group field-level violations so the SPA can highlight the offending field. Each entry
    // maps the JSON property name to the list of constraint messages on it.
    Map<String, List<String>> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.groupingBy(
                    FieldError::getField,
                    Collectors.mapping(
                        fe ->
                            fe.getDefaultMessage() == null
                                ? "invalid value"
                                : fe.getDefaultMessage(),
                        Collectors.toList())));
    return respond(
        HttpStatus.BAD_REQUEST,
        ProblemCode.VALIDATION_FAILED,
        "request body is invalid",
        fieldErrors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Problem> handleUnreadableBody() {
    return respond(
        HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, "request body is invalid");
  }

  // Service-layer rejections of malformed inputs (e.g. a multi-option ballot on a single-choice
  // question, or a duplicate option in a ballot) surface here. They are semantic 400s — the
  // request body parsed and bind-validated cleanly, but the values violate a domain rule that the
  // service is closer to than the DTO. The message is authored in our own code, so {@link #safe}
  // is safe; we do not key the entry to a specific field because the violations span the whole
  // {@code optionIds} array.
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Problem> handleIllegalArgument(IllegalArgumentException ex) {
    return respond(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, safe(ex));
  }

  // A missing static resource (e.g. /admin/assets/foo.js when no such file was packaged) is a
  // 404, not a transport fault. Without this handler the catch-all {@link Exception} branch
  // below maps it to 500 and the browser sees a JSON Problem envelope in place of the asset.
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Problem> handleResourceMissing() {
    return respond(HttpStatus.NOT_FOUND, ProblemCode.NOT_FOUND, "resource not found");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> handleUnexpected(Exception ex, HttpServletResponse response) {
    LOG.error("unexpected server fault", ex);
    // SseEmitter pins the response Content-Type to text/event-stream before the body
    // is streamed. Once an async fault surfaces (typically a ClientAbortException
    // when the subscriber drops or nginx times the connection out), Spring routes
    // here and tries to serialise Problem as JSON — but the converter cannot honour
    // text/event-stream and throws HttpMessageNotWritableException, masking the
    // actual root cause. The HTTP response is already lost to the client at this
    // point; just return an empty body so the dispatch completes cleanly.
    if (MediaType.TEXT_EVENT_STREAM_VALUE.equals(response.getContentType())) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ProblemCode.TRANSPORT_FAILURE,
        "an unexpected error occurred");
  }

  private static ResponseEntity<Problem> respond(HttpStatus status, ProblemCode code, String msg) {
    return respond(status, code, msg, null);
  }

  private static ResponseEntity<Problem> respond(
      HttpStatus status, ProblemCode code, String msg, Map<String, List<String>> errors) {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    return ResponseEntity.status(status).body(new Problem(code, msg, correlationId, errors));
  }

  /**
   * A defensive helper: exception messages authored in our own code are safe to surface, but Spring
   * Security and validation frameworks sometimes embed internal details. This keeps the user-facing
   * wording on our domain exceptions while falling back to a neutral phrase when the exception
   * carries no message.
   */
  private static String safe(Exception ex) {
    String msg = ex.getMessage();
    return (msg == null || msg.isBlank()) ? "request could not be completed" : msg;
  }
}
