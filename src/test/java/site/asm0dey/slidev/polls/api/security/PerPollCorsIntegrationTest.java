package site.asm0dey.slidev.polls.api.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration coverage for per-poll CORS preflight behaviour against the real Spring Security
 * filter chain (Testcontainers Postgres, Flyway-migrated, alice pre-seeded by V3).
 *
 * <ul>
 *   <li>@TS-A5-010 — preflight allowed when origin is in the poll's allowedOrigins
 *   <li>@TS-A5-011 — preflight denied (403) when origin is not in the allowedOrigins
 *   <li>@TS-A5-012 — deck-activation preflight honoured for per-poll allowlist
 *   <li>@TS-A5-013 — deck-login preflight allowed when origin matches any poll's allowedOrigins
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PerPollCorsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-A5-010 — preflight from a listed origin must receive the Allow-Origin echo and
  // Allow-Credentials: true so the browser permits the cross-origin SSE subscription.
  @Test
  void preflightAllowedForListedOrigin() throws Exception {
    PollFixture poll = createPoll("cors-allowed", "http://localhost:3030");
    mvc.perform(
            options("/api/polls/" + poll.slug() + "/stream")
                .header("Origin", "http://localhost:3030")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3030"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  // @TS-A5-011 — preflight from an unlisted origin must be rejected with 403 so the browser
  // blocks the follow-on request entirely (no partial-request attack surface).
  @Test
  void preflightDeniedForUnlistedOrigin() throws Exception {
    PollFixture poll = createPoll("cors-denied", "http://localhost:3030");
    mvc.perform(
            options("/api/polls/" + poll.slug() + "/stream")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  // @TS-A5-012 — the deck-activation path resolves CORS by pollId; a matching origin must be
  // echoed back so the Slidev deck can call activate cross-origin.
  @Test
  void deckActivationPreflightHonoursPollIdAllowlist() throws Exception {
    PollFixture poll = createPoll("cors-deck-activate", "https://demo.example.com");
    mvc.perform(
            options("/api/deck/polls/" + poll.pollId() + "/activate")
                .header("Origin", "https://demo.example.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Deck-Token,Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://demo.example.com"));
  }

  // @TS-A5-013 — the deck-login path resolves CORS by scanning all polls whose allowedOrigins
  // contain the requesting Origin; a poll seeded with that origin must unlock the preflight.
  @Test
  void deckLoginPreflightAllowsAnyPollOrigin() throws Exception {
    createPoll("cors-deck-login", "http://localhost:3030");
    mvc.perform(
            options("/api/deck/auth/login")
                .header("Origin", "http://localhost:3030")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3030"));
  }

  // ---------- fixtures -------------------------------------------------------

  /** Creates a poll via the admin API carrying the given allowed origin. */
  private PollFixture createPoll(String slug, String allowedOrigin) throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        String.format(
            """
            {
              "title": "CORS fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]}
              ],
              "allowedOrigins": ["%s"]
            }
            """,
            slug, allowedOrigin);
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode poll = objectMapper.readTree(created.getResponse().getContentAsString());
    UUID pollId = UUID.fromString(poll.get("id").asString());
    String pollSlug = poll.get("slug").asString();
    return new PollFixture(pollId, pollSlug);
  }

  private MockHttpSession loginAsAlice() throws Exception {
    MvcResult login =
        mvc.perform(
                post("/api/admin/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"correct-horse\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
    if (session == null) {
      throw new IllegalStateException("login did not establish a session");
    }
    return session;
  }

  private record PollFixture(UUID pollId, String slug) {}
}
