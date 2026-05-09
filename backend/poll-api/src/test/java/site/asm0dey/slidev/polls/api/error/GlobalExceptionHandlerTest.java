package site.asm0dey.slidev.polls.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Mirrors @TS-042 (cross-cutting.feature): every Problem code enumerated in the OpenAPI Problem
 * schema must come out of the exception-handling chain with the right HTTP status, the right {@code
 * code}, and a {@code correlationId} populated from the request-scoped MDC entry set by {@link
 * CorrelationIdFilter}. The test mounts a tiny controller that throws each exception on demand so
 * the slice exercises the full filter + advice path.
 */
@WebMvcTest(
    controllers = GlobalExceptionHandlerTest.ProblemProvokingController.class,
    properties = "spring.main.web-application-type=servlet")
@Import({
  GlobalExceptionHandler.class,
  CorrelationIdFilter.class,
  GlobalExceptionHandlerTest.ProblemProvokingController.class,
  GlobalExceptionHandlerTest.PermitAllTestSecurityConfig.class
})
class GlobalExceptionHandlerTest {

  // The @WebMvcTest slice auto-configures Spring Security with its default
  // "deny everything unauthenticated" chain, which would short-circuit our
  // handlers and turn every test endpoint into a 401. Replace it with a
  // permit-all chain so the advice path is what gets exercised. Production
  // security lives in SecurityConfig (arrives in T055).
  @org.springframework.boot.test.context.TestConfiguration
  static class PermitAllTestSecurityConfig {
    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf(csrf -> csrf.disable());
      return http.build();
    }
  }

  @Autowired private MockMvc mvc;

  // The @WebMvcTest slice still scans @Component-annotated security beans including
  // DeckTokenAuthenticationFilter; its constructor needs a DeckTokenService that the slice
  // otherwise wouldn't supply. Mock it away so the handler chain exercise stays focused.
  @MockitoBean private DeckTokenService deckTokenService;

  // PerPollCorsConfigurationSource is also a @Component reachable through SecurityConfig and
  // requires PollRepository; mocked here for the same reason as DeckTokenService.
  @MockitoBean
  private site.asm0dey.slidev.polls.core.service.PollRepository pollRepository;

  // @TS-042 — a representative request for each Problem code must surface the
  // matching HTTP status and code, with a correlationId accompanying every
  // response so logs and the user-visible error share a lookup key.
  @ParameterizedTest
  @CsvSource({
    "/__problem__/auth-required,    401, AUTH_REQUIRED",
    "/__problem__/forbidden,        403, FORBIDDEN",
    "/__problem__/not-found,        404, NOT_FOUND",
    "/__problem__/already-voted,    409, ALREADY_VOTED",
    "/__problem__/not-active,       409, QUESTION_NOT_ACTIVE",
    "/__problem__/activate-reject,  409, ACTIVATION_REJECTED",
    "/__problem__/slug-taken,       409, SLUG_TAKEN",
    "/__problem__/slug-invalid,     409, SLUG_INVALID",
    "/__problem__/slug-reserved,    409, SLUG_RESERVED",
    "/__problem__/deck-invalid,     401, DECK_TOKEN_INVALID",
    "/__problem__/deck-mismatch,    403, DECK_TOKEN_POLL_MISMATCH",
    "/__problem__/setup-locked,     409, SETUP_LOCKED",
    "/__problem__/username-taken,   409, USERNAME_TAKEN",
    "/__problem__/transport,        500, TRANSPORT_FAILURE",
  })
  void maps_each_exception_to_its_problem_code(String path, int status, String code)
      throws Exception {
    mvc.perform(get(path))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  // @TS-042 — body-level validation (@Valid) surfaces VALIDATION_FAILED with a
  // 400, and the response still carries a correlationId.
  @org.junit.jupiter.api.Test
  void invalid_body_surfaces_validation_failed() throws Exception {
    mvc.perform(
            post("/__problem__/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  // @TS-042 — X-Correlation-Id from the caller is honoured and round-trips on
  // both the response header and the Problem body. Also asserts the default
  // UUID shape when no header is supplied, so the generated id is obviously
  // a UUID rather than an arbitrary string.
  @org.junit.jupiter.api.Test
  void honours_incoming_correlation_id_and_mirrors_it_on_response() throws Exception {
    mvc.perform(get("/__problem__/not-found").header(CorrelationIdFilter.HEADER, "corr-abc-123"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.correlationId").value("corr-abc-123"))
        .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-abc-123"));

    mvc.perform(get("/__problem__/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.correlationId")
                .value(
                    matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
  }

  @org.junit.jupiter.api.Test
  void response_body_is_a_problem_record() {
    // Sanity-check on the type itself so a future rewrite that changes the JSON
    // shape fails at compile time, not just at every integration test.
    Problem problem = new Problem(ProblemCode.NOT_FOUND, "poll does not exist", "corr-1");
    assertThat(problem.code()).isEqualTo(ProblemCode.NOT_FOUND);
    assertThat(problem.message()).isEqualTo("poll does not exist");
    assertThat(problem.correlationId()).isEqualTo("corr-1");
  }

  @RestController
  @RequestMapping("/__problem__")
  static class ProblemProvokingController {
    @GetMapping("/auth-required")
    void authRequired() {
      throw new TestAuthenticationException();
    }

    @GetMapping("/forbidden")
    void forbidden() {
      throw new AccessDeniedException("not yours");
    }

    @GetMapping("/not-found")
    void notFound() {
      throw new NotFoundException("poll does not exist");
    }

    @GetMapping("/already-voted")
    void alreadyVoted() {
      throw new AlreadyVotedException("voter has already voted on this question");
    }

    @GetMapping("/not-active")
    void notActive() {
      throw new QuestionNotActiveException("question is CLOSED");
    }

    @GetMapping("/activate-reject")
    void activateReject() {
      throw new ActivationRejectedException("question needs at least two options");
    }

    @GetMapping("/slug-taken")
    void slugTaken() {
      throw new SlugTakenException("my-talk");
    }

    @GetMapping("/slug-invalid")
    void slugInvalid() {
      throw new SlugInvalidException("Ab");
    }

    @GetMapping("/slug-reserved")
    void slugReserved() {
      throw new SlugReservedException("admin");
    }

    @GetMapping("/not-owner")
    void notOwner() {
      throw new NotOwnerException("not your poll");
    }

    @GetMapping("/deck-invalid")
    void deckInvalid() {
      throw new DeckTokenInvalidException("token not found or revoked");
    }

    @GetMapping("/deck-mismatch")
    void deckMismatch() {
      throw new DeckTokenPollMismatchException("token scoped to a different poll");
    }

    @GetMapping("/setup-locked")
    void setupLocked() {
      throw new SetupLockedException("setup already complete");
    }

    @GetMapping("/username-taken")
    void usernameTaken() {
      throw new UsernameTakenException("alice");
    }

    @GetMapping("/transport")
    void transport() {
      throw new IllegalStateException("unexpected server fault");
    }

    @PostMapping("/validate")
    void validate(@RequestBody @jakarta.validation.Valid Body body) {}

    record Body(@NotBlank String label) {}
  }

  static class TestAuthenticationException extends AuthenticationException {
    TestAuthenticationException() {
      super("no session");
    }
  }
}
