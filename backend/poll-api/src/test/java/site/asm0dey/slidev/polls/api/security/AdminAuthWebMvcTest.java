package site.asm0dey.slidev.polls.api.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.admin.AdminAuthController;
import site.asm0dey.slidev.polls.api.admin.PollController;
import site.asm0dey.slidev.polls.api.admin.QrController;
import site.asm0dey.slidev.polls.api.error.GlobalExceptionHandler;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Security-slice coverage for the admin surface. Pins three assertions the production filter chain
 * has to honour:
 *
 * <ul>
 *   <li>@TS-001 — every {@code /api/admin/**} endpoint returns a 401 {@code AUTH_REQUIRED} Problem
 *       when no session is present.
 *   <li>@TS-040 — a GET on another presenter's poll returns 403 {@code FORBIDDEN} (authenticated
 *       but not the owner).
 *   <li>@TS-041 — the same for a mutation (DELETE / PATCH).
 * </ul>
 *
 * The slice imports the real {@link SecurityConfig}, {@link ProblemAuthenticationEntryPoint}, and
 * {@link ProblemAccessDeniedHandler} so the Problem-shaped envelopes we assert on come out of the
 * production code path; only the downstream {@link PollService} is mocked.
 */
@WebMvcTest(
    controllers = {PollController.class, QrController.class, AdminAuthController.class},
    properties = "spring.main.web-application-type=servlet")
@Import({
  SecurityConfig.class,
  ProblemAuthenticationEntryPoint.class,
  ProblemAccessDeniedHandler.class,
  GlobalExceptionHandler.class,
  CorrelationIdFilter.class,
  PerPollCorsConfigurationSource.class,
  AdminAuthWebMvcTest.TestUserDetails.class
})
class AdminAuthWebMvcTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private PollService pollService;

  // DeckTokenAuthenticationFilter is a @Component scanned through SecurityConfig's dependency
  // wiring. The slice itself doesn't exercise the /api/deck/** surface, but the filter's
  // constructor needs a DeckTokenService bean to satisfy autowiring — mock it away.
  @MockitoBean private DeckTokenService deckTokenService;

  // PerPollCorsConfigurationSource is a @Component pulled in through SecurityConfig and depends
  // on PollRepository. The slice does not test CORS, so mock the repo dependency away.
  @MockitoBean private site.asm0dey.slidev.polls.core.service.PollRepository pollRepository;

  // @TS-001 — GET on the list endpoint without a session yields 401 with AUTH_REQUIRED.
  @Test
  void unauthenticated_list_returns_401_auth_required() throws Exception {
    mvc.perform(get("/api/admin/polls"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  // @TS-001 — fetching a specific poll without a session also 401s.
  @Test
  void unauthenticated_get_by_id_returns_401() throws Exception {
    mvc.perform(get("/api/admin/polls/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  // @TS-001 — mutating endpoints 401 just like reads.
  @Test
  void unauthenticated_delete_returns_401() throws Exception {
    mvc.perform(delete("/api/admin/polls/" + UUID.randomUUID()).with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  // @TS-001 — the QR endpoint, like the rest of /api/admin/**, is gated.
  @Test
  void unauthenticated_qr_returns_401() throws Exception {
    mvc.perform(get("/api/admin/polls/" + UUID.randomUUID() + "/qr.png"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  // @TS-040 — authenticated "bob" fetching a poll owned by "alice" gets FORBIDDEN rather than a
  // fake 404 that would leak the existence of the poll.
  @Test
  void foreign_presenter_get_returns_403() throws Exception {
    UUID pollId = UUID.randomUUID();
    when(pollService.getForOwner(eq(pollId), eq("bob")))
        .thenThrow(new NotOwnerException("poll " + pollId + " is not owned by bob"));

    mvc.perform(get("/api/admin/polls/" + pollId).with(user("bob").roles("AUTHENTICATED")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  // @TS-041 — mutation by a non-owner also returns FORBIDDEN. The controller method runs the
  // service's ForOwner check, which throws; the security chain is not bypassed.
  @Test
  void foreign_presenter_delete_returns_403() throws Exception {
    UUID pollId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new NotOwnerException("not yours"))
        .when(pollService)
        .deleteForOwner(eq(pollId), eq("bob"));

    mvc.perform(
            delete("/api/admin/polls/" + pollId)
                .with(user("bob").roles("AUTHENTICATED"))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  // @TS-041 — PATCH body by a non-owner is blocked the same way; the slice makes sure the request
  // actually reaches the controller (the body deserialises, validation passes) before the service's
  // ownership check fires.
  @Test
  void foreign_presenter_patch_returns_403() throws Exception {
    UUID pollId = UUID.randomUUID();
    when(pollService.updateForOwner(eq(pollId), eq("bob"), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new NotOwnerException("not yours"));

    mvc.perform(
            patch("/api/admin/polls/" + pollId)
                .with(user("bob").roles("AUTHENTICATED"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  // A sanity-check that the owner can reach her own poll through the slice — otherwise the 403
  // tests above would pass trivially if every authenticated request were denied.
  @Test
  void rightful_owner_can_read_their_own_poll() throws Exception {
    UUID pollId = UUID.randomUUID();
    when(pollService.getForOwner(eq(pollId), eq("alice"))).thenReturn(fixturePoll(pollId, "alice"));

    mvc.perform(get("/api/admin/polls/" + pollId).with(user("alice").roles("AUTHENTICATED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("my-talk"));
  }

  // @TS-001 — the login endpoint stays reachable without a session (that was the whole point of
  // the permitAll carve-out in SecurityConfig). An empty body hits validation, not the auth gate,
  // so we assert on a 400-class response rather than 401.
  @Test
  void login_endpoint_is_reachable_without_session() throws Exception {
    mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().is4xxClientError())
        // Validation failure — not AUTH_REQUIRED, because the route is permitAll.
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  private static Poll fixturePoll(UUID pollId, String owner) {
    return new Poll(
        pollId,
        owner,
        "My Talk",
        "my-talk",
        PollStatus.DRAFT,
        Map.of(),
        null,
        List.of(),
        List.of(), // allowedOrigins
        Instant.now(),
        Instant.now());
  }

  /**
   * Minimal in-memory UserDetailsService so the SecurityConfig's DaoAuthenticationProvider has
   * something to wire up when the slice instantiates it. The real {@link AdminUserDetailsService}
   * needs a jOOQ {@link org.jooq.DSLContext}; the slice does not stand one up and does not need to,
   * because all authentication in these tests is simulated via Spring Security Test's {@code
   * .with(user(...))} — the provider is wired only to keep the chain instantiable.
   */
  @org.springframework.boot.test.context.TestConfiguration
  static class TestUserDetails {
    @Bean
    UserDetailsService userDetailsService() {
      return new InMemoryUserDetailsManager(
          User.withUsername("alice").password("{noop}x").authorities("ROLE_AUTHENTICATED").build(),
          User.withUsername("bob").password("{noop}x").authorities("ROLE_AUTHENTICATED").build());
    }

    @Bean
    tools.jackson.databind.ObjectMapper objectMapper() {
      // The WebMvcTest slice scopes out the JacksonAutoConfiguration JsonMapper for our Problem
      // entry-point/access-denied-handler constructors, so publish one here. Jackson 3's
      // ObjectMapper is abstract; the concrete default is JsonMapper.
      return tools.jackson.databind.json.JsonMapper.builder().build();
    }
  }
}
