package site.asm0dey.slidev.polls.api.deck;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Coverage for the deck-auth self-inspection endpoint added by feature 002.
 *
 * <ul>
 *   <li>{@code @TS-107} — valid deck token → 200 with tokenId, pollId, label
 *   <li>{@code @TS-108} — missing header → 401 {@code DECK_TOKEN_INVALID}
 *   <li>{@code @TS-109} — unknown bearer → 401 {@code DECK_TOKEN_INVALID}
 *   <li>revoked bearer (cross-story prerequisite for {@code @TS-123}) → 401 {@code
 *       DECK_TOKEN_INVALID}
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeckAuthControllerTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-107 — Given a live deck token for poll "my-talk" with label "Laptop", When GET
  // /api/deck/auth/me with X-Deck-Token: dtk-1, Then response is 200 with the token's scope.
  @Test
  void returns200WithScope_whenDeckTokenValid() throws Exception {
    PollFixture poll = createPoll("deck-auth-valid");
    JsonNode minted = mintTokenRaw(poll, "Laptop");
    String plaintext = minted.get("plaintext").asString();
    UUID tokenId = UUID.fromString(minted.get("id").asString());

    mvc.perform(get("/api/deck/auth/me").header("X-Deck-Token", plaintext))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokenId").value(tokenId.toString()))
        .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()))
        .andExpect(jsonPath("$.label").value("Laptop"));
  }

  // @TS-108 — Given no X-Deck-Token header, When GET /api/deck/auth/me, Then 401
  // with code DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenHeaderMissing() throws Exception {
    mvc.perform(get("/api/deck/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));
  }

  // @TS-109 — Given no deck token matching bearer "bogus" exists, When GET /api/deck/auth/me
  // with that header, Then 401 with code DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenBearerUnknown() throws Exception {
    mvc.perform(get("/api/deck/auth/me").header("X-Deck-Token", "bogus-plaintext-unknown"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));
  }

  // Cross-story prerequisite for @TS-123: a revoked row → 401 DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenBearerRevoked() throws Exception {
    PollFixture poll = createPoll("deck-auth-revoked");
    JsonNode minted = mintTokenRaw(poll, null);
    UUID tokenId = UUID.fromString(minted.get("id").asString());
    String plaintext = minted.get("plaintext").asString();

    MockHttpSession session = loginAsAlice();
    mvc.perform(
            delete("/api/admin/polls/" + poll.pollId() + "/deck-tokens/" + tokenId)
                .session(session)
                .with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/deck/auth/me").header("X-Deck-Token", plaintext))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));
  }

  // @BUG-002 — Given valid admin-equivalent credentials and an owned poll, When POST
  // /api/deck/auth/login with {username, password}, Then response is 200 with a minted deck
  // token plaintext plus the scope the frontend persists under slidev-polls:deck-auth.
  @Test
  void returns200WithMintedToken_whenCredentialsValid() throws Exception {
    PollFixture poll = createPoll("deck-login-ok");

    MvcResult result =
        mvc.perform(
                post("/api/deck/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"correct-horse\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()))
            .andExpect(jsonPath("$.label").value("deck"))
            .andExpect(jsonPath("$.token").isString())
            .andExpect(jsonPath("$.tokenId").isString())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    String plaintext = body.get("token").asString();

    // The returned plaintext must actually authenticate the follow-up /me probe — otherwise the
    // frontend would get stuck in signed-in-pending on the very next reload.
    mvc.perform(get("/api/deck/auth/me").header("X-Deck-Token", plaintext))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()));
  }

  // @BUG-002 — Invalid credentials → 401. Composable maps any 401 to FR-014
  // "credential not recognised".
  @Test
  void returns401_whenCredentialsInvalid() throws Exception {
    mvc.perform(
            post("/api/deck/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  // Regression: a collaborator who owns no poll (only collaborates on alice's) must be able to
  // sign into the deck and receive a token for the collaborated poll. The login previously
  // selected from owner-only polls, so a pure collaborator got AUTH_REQUIRED.
  @Test
  void returns200WithMintedToken_whenCollaboratorCredentialsValid() throws Exception {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "bob", "bobs-strong-password-12");
    PollFixture poll = createPoll("deck-login-collab");

    MockHttpSession aliceSession = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls/" + poll.pollId() + "/collaborators")
                .session(aliceSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\"}"))
        .andExpect(status().isCreated());

    MvcResult result =
        mvc.perform(
                post("/api/deck/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"bob\",\"password\":\"bobs-strong-password-12\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    mvc.perform(get("/api/deck/auth/me").header("X-Deck-Token", body.get("token").asString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()));
  }

  // ---------- fixtures (mirrored from DeckActivationIT) --------------------

  private JsonNode mintTokenRaw(PollFixture poll, String label) throws Exception {
    MockHttpSession session = loginAsAlice();
    String body = label == null ? "{}" : "{\"label\":\"" + label + "\"}";
    MvcResult mint =
        mvc.perform(
                post("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(mint.getResponse().getContentAsString());
  }

  private PollFixture createPoll(String slug) throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        String.format(
            """
            {
              "title": "Deck auth fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]}
              ]
            }
            """,
            slug);
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
    UUID q1 = UUID.fromString(poll.get("questions").get(0).get("id").asString());
    return new PollFixture(pollId, q1);
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

  private record PollFixture(UUID pollId, UUID q1) {}
}
