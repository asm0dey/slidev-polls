package site.asm0dey.slidev.polls.api.error;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.DeckTokenInvalidException;
import site.asm0dey.slidev.polls.core.error.DeckTokenPollMismatchException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.SlugInvalidException;
import site.asm0dey.slidev.polls.core.error.SlugReservedException;
import site.asm0dey.slidev.polls.core.error.SlugTakenException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Rewrite of the deleted Spring {@code GlobalExceptionHandlerTest} — @TS-042. The
 * exception-handling chain is now the JAX-RS {@code DomainExceptionMappers} + {@code
 * RequestValidationMappers} + {@code SecurityProblemMappers}; this {@code @QuarkusTest} drives
 * those mappers through real endpoints over RestAssured.
 *
 * <p>Every domain {@link ProblemCode} is provoked by stubbing {@link PollService#getForOwner} (the
 * gateway every {@code GET /api/admin/polls/{id}} call funnels through) to throw the matching
 * domain exception, then asserting the mapped HTTP status, {@code code}, a non-empty {@code
 * message}, and a populated {@code correlationId}. Validation and correlation-id round-trip use
 * unauthenticated public endpoints.
 *
 * <p>Codes verified elsewhere and not re-driven here, to avoid duplication:
 *
 * <ul>
 *   <li>{@code AUTH_REQUIRED} — {@code AdminAuthWebMvcTest} (unauthenticated /api/admin/**) and
 *       {@code SpaCatchAllIT}.
 *   <li>{@code FORBIDDEN} via {@code NotOwnerException} — {@code AdminAuthWebMvcTest}; also covered
 *       below through the parameterised list.
 *   <li>{@code RESOURCE_HAS_VOTES} / {@code ORIGIN_INVALID} — exercised by the admin authoring /
 *       lifecycle ITs that delete options or set bad origins.
 * </ul>
 */
@QuarkusTest
class GlobalExceptionHandlerTest {

  @InjectMock PollService pollService;

  // @TS-042 — each domain exception maps to its Problem code + HTTP status, with a non-empty
  // message and a correlationId on every response. getForOwner is the shared gateway, so stubbing
  // it to throw drives the full mapper chain for a real GET /api/admin/polls/{id}.
  @ParameterizedTest
  @CsvSource({
    "not-found,        404, NOT_FOUND",
    "already-voted,    409, ALREADY_VOTED",
    "not-active,       409, QUESTION_NOT_ACTIVE",
    "activate-reject,  409, ACTIVATION_REJECTED",
    "not-owner,        403, FORBIDDEN",
    "slug-taken,       409, SLUG_TAKEN",
    "slug-invalid,     409, SLUG_INVALID",
    "slug-reserved,    409, SLUG_RESERVED",
    "deck-invalid,     401, DECK_TOKEN_INVALID",
    "deck-mismatch,    403, DECK_TOKEN_POLL_MISMATCH",
    "setup-locked,     409, SETUP_LOCKED",
    "username-taken,   409, USERNAME_TAKEN",
    "transport,        500, TRANSPORT_FAILURE",
  })
  @TestSecurity(user = "alice", roles = "ADMIN")
  void maps_each_exception_to_its_problem_code(String kind, int status, String code) {
    UUID pollId = UUID.randomUUID();
    when(pollService.getForOwner(eq(pollId), eq("alice"))).thenThrow(exceptionFor(kind));

    given()
        .when()
        .get("/api/admin/polls/" + pollId)
        .then()
        .statusCode(status)
        .body("code", equalTo(code))
        .body("message", notNullValue())
        .body("message", not(equalTo("")))
        .body("correlationId", notNullValue());
  }

  // @TS-042 — body-level bean-validation surfaces VALIDATION_FAILED (400) with a correlationId.
  // The login DTO requires username/password; an empty object trips @NotBlank.
  @Test
  void invalid_body_surfaces_validation_failed() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/admin/login")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"))
        .body("message", notNullValue())
        .body("correlationId", notNullValue());
  }

  // @TS-042 — an incoming X-Correlation-Id is honoured and round-trips on both the response header
  // and the Problem body. Uses the public by-slug 404 so no auth is needed.
  @Test
  void honours_incoming_correlation_id_and_mirrors_it_on_response() {
    given()
        .header(CorrelationIdFilter.HEADER, "corr-abc-123")
        .when()
        .get("/api/polls/by-slug/no-such-poll")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"))
        .body("correlationId", equalTo("corr-abc-123"))
        .header(CorrelationIdFilter.HEADER, equalTo("corr-abc-123"));
  }

  // @TS-042 — when no correlation id is supplied, a fresh UUID is minted and surfaced on the body.
  @Test
  void mints_a_uuid_correlation_id_when_none_supplied() {
    given()
        .when()
        .get("/api/polls/by-slug/no-such-poll")
        .then()
        .statusCode(404)
        .body(
            "correlationId",
            matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
  }

  // Sanity-check on the Problem record itself so a future JSON-shape rewrite fails at compile time.
  @Test
  void problem_record_carries_code_message_and_correlation_id() {
    Problem problem = new Problem(ProblemCode.NOT_FOUND, "poll does not exist", "corr-1");
    assertThat(problem.code()).isEqualTo(ProblemCode.NOT_FOUND);
    assertThat(problem.message()).isEqualTo("poll does not exist");
    assertThat(problem.correlationId()).isEqualTo("corr-1");
  }

  private static RuntimeException exceptionFor(String kind) {
    return switch (kind) {
      case "not-found" -> new NotFoundException("poll does not exist");
      case "already-voted" -> new AlreadyVotedException("voter has already voted on this question");
      case "not-active" -> new QuestionNotActiveException("question is CLOSED");
      case "activate-reject" ->
          new ActivationRejectedException("question needs at least two options");
      case "not-owner" -> new NotOwnerException("not your poll");
      case "slug-taken" -> new SlugTakenException("my-talk");
      case "slug-invalid" -> new SlugInvalidException("Ab");
      case "slug-reserved" -> new SlugReservedException("admin");
      case "deck-invalid" -> new DeckTokenInvalidException("token not found or revoked");
      case "deck-mismatch" ->
          new DeckTokenPollMismatchException("token scoped to a different poll");
      case "setup-locked" -> new SetupLockedException("setup already complete");
      case "username-taken" -> new UsernameTakenException("alice");
      case "transport" -> new IllegalStateException("unexpected server fault");
      default -> throw new IllegalArgumentException("unknown kind: " + kind);
    };
  }
}
