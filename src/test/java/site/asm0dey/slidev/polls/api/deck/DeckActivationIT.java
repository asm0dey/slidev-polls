package site.asm0dey.slidev.polls.api.deck;

import static org.assertj.core.api.Assertions.assertThat;
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
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage for the deck-token mint / activate surface.
 *
 * <ul>
 *   <li>{@code @TS-050} — mount triggers activation (Q1 → ACTIVE)
 *   <li>{@code @TS-051} — navigation swaps active (Q1 → CLOSED, Q2 → ACTIVE)
 *   <li>{@code @TS-052} — remounting the current slide is idempotent ({@code activated_at}
 *       unchanged)
 *   <li>{@code @TS-053} — anonymous caller → 401 {@code DECK_TOKEN_INVALID}
 *   <li>{@code @TS-054} — cross-poll token → 403 {@code DECK_TOKEN_POLL_MISMATCH}
 *   <li>{@code @TS-055} — revoked token → 401 {@code DECK_TOKEN_INVALID}
 *   <li>{@code @TS-056} — mint returns plaintext exactly once; list rows carry no plaintext
 *   <li>{@code @TS-057} — deck token on admin endpoint → 401 {@code AUTH_REQUIRED}
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeckActivationIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PollRepository pollRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-050 — mounting activates the declared question.
  @Test
  void mounting_activates_the_declared_question() throws Exception {
    PollFixture poll = createPoll("deck-mount");
    String plaintext = mintToken(poll);

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/activate")
                .header("X-Deck-Token", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + poll.q1() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(poll.q1().toString()));

    assertThat(pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId())
        .isEqualTo(poll.q1());
  }

  // @TS-051 — navigating the deck to a second slide swaps the active question.
  @Test
  void navigation_swaps_active_question() throws Exception {
    PollFixture poll = createPoll("deck-nav");
    String plaintext = mintToken(poll);

    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    activateViaDeck(poll.pollId(), plaintext, poll.q2());

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isEqualTo(poll.q2());
    var q1 =
        after.questions().stream().filter(q -> q.id().equals(poll.q1())).findFirst().orElseThrow();
    assertThat(q1.status()).isEqualTo(QuestionStatus.CLOSED);
  }

  // @TS-052 — remounting the already-active slide is idempotent. We rely on the
  // PollService.activateQuestion short-circuit (no repository write) to keep activated_at stable.
  @Test
  void remount_of_active_question_is_idempotent() throws Exception {
    PollFixture poll = createPoll("deck-idem");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var firstActivatedAt =
        pollRepository.findById(poll.pollId()).orElseThrow().questions().stream()
            .filter(q -> q.id().equals(poll.q1()))
            .findFirst()
            .orElseThrow()
            .activatedAt();

    // Second mount of the same slide — MUST NOT rotate activated_at.
    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var afterSecond =
        pollRepository.findById(poll.pollId()).orElseThrow().questions().stream()
            .filter(q -> q.id().equals(poll.q1()))
            .findFirst()
            .orElseThrow()
            .activatedAt();
    assertThat(afterSecond).isEqualTo(firstActivatedAt);
  }

  // @TS-053 — anonymous caller cannot activate.
  @Test
  void anonymous_caller_is_rejected_with_deck_token_invalid() throws Exception {
    PollFixture poll = createPoll("deck-anon");
    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + poll.q1() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));
  }

  // @TS-124 — anonymous POST /api/deck/polls/{pollId}/activate returns 401 with code
  // DECK_TOKEN_INVALID and leaves the poll's active-question pointer unchanged. Covers
  // the FR-007 backend half of US2's independent test.
  @Test
  void anonymousPostReturns401DeckTokenInvalid() throws Exception {
    PollFixture poll = createPoll("deck-ts124");
    // Seed the poll's active pointer to q1 via an authenticated route so we can assert the
    // pointer is NOT changed by the anonymous attempt.
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var before = pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId();

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + poll.q2() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));

    var after = pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId();
    assertThat(after).isEqualTo(before);
  }

  // @TS-054 — a token minted for poll A can't activate on poll B.
  @Test
  void cross_poll_token_is_rejected_with_poll_mismatch() throws Exception {
    PollFixture pollA = createPoll("deck-cross-a");
    PollFixture pollB = createPoll("deck-cross-b");
    String plaintextForA = mintToken(pollA);

    mvc.perform(
            post("/api/deck/polls/" + pollB.pollId() + "/activate")
                .header("X-Deck-Token", plaintextForA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + pollB.q1() + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_POLL_MISMATCH"));
  }

  // @TS-055 — a revoked token is rejected.
  @Test
  void revoked_token_is_rejected_with_deck_token_invalid() throws Exception {
    PollFixture poll = createPoll("deck-revoked");
    JsonNode minted = mintTokenRaw(poll);
    UUID tokenId = UUID.fromString(minted.get("id").asString());
    String plaintext = minted.get("plaintext").asString();

    MockHttpSession session = loginAsAlice();
    mvc.perform(
            delete("/api/admin/polls/" + poll.pollId() + "/deck-tokens/" + tokenId)
                .session(session)
                .with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/activate")
                .header("X-Deck-Token", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + poll.q1() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_INVALID"));
  }

  // @TS-056 — minted plaintext appears exactly once; list never returns it.
  @Test
  void minted_plaintext_visible_exactly_once() throws Exception {
    PollFixture poll = createPoll("deck-mint-plain");
    MockHttpSession session = loginAsAlice();
    MvcResult mint =
        mvc.perform(
                post("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"label\":\"laptop\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode minted = objectMapper.readTree(mint.getResponse().getContentAsString());
    assertThat(minted.get("plaintext").asString()).as("plaintext surfaced on mint").isNotBlank();

    MvcResult listing =
        mvc.perform(get("/api/admin/polls/" + poll.pollId() + "/deck-tokens").session(session))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode rows = objectMapper.readTree(listing.getResponse().getContentAsString());
    assertThat(rows.isArray()).isTrue();
    for (JsonNode row : rows) {
      assertThat(row.has("plaintext")).as("list rows must not carry plaintext (@TS-056)").isFalse();
    }
  }

  // @TS-057 — the deck token must not unlock any other admin surface.
  @Test
  void deck_token_on_admin_endpoint_is_rejected_with_auth_required() throws Exception {
    PollFixture poll = createPoll("deck-scope");
    String plaintext = mintToken(poll);

    mvc.perform(get("/api/admin/polls").header("X-Deck-Token", plaintext))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  // @TS-060 — POSTing /close after activation returns 200 with null activeQuestionId and sets the
  // question to CLOSED in the database.
  @Test
  void close_after_activate_sets_question_closed() throws Exception {
    PollFixture poll = createPoll("deck-close-happy");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/close").header("X-Deck-Token", plaintext))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()))
        .andExpect(jsonPath("$.activeQuestionId").doesNotExist());

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isNull();
    var q1 =
        after.questions().stream().filter(q -> q.id().equals(poll.q1())).findFirst().orElseThrow();
    assertThat(q1.status()).isEqualTo(QuestionStatus.CLOSED);
  }

  // @TS-061 — /close when no question is active is idempotent (200, null activeQuestionId).
  @Test
  void close_when_nothing_active_is_idempotent() throws Exception {
    PollFixture poll = createPoll("deck-close-idem");
    String plaintext = mintToken(poll);

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/close").header("X-Deck-Token", plaintext))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").doesNotExist());
  }

  // Close request that scopes to a specific questionId no-ops when a different question is
  // currently active. Prevents a slow slide-leave close from racing past the next slide's
  // activate and clobbering the just-opened question.
  @Test
  void close_with_mismatched_question_id_is_no_op() throws Exception {
    PollFixture poll = createPoll("deck-close-scoped");
    String plaintext = mintToken(poll);
    // Q2 is the currently-active question; the caller asks to close Q1 (stale).
    activateViaDeck(poll.pollId(), plaintext, poll.q2());

    mvc.perform(
            post("/api/deck/polls/" + poll.pollId() + "/close")
                .header("X-Deck-Token", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + poll.q1() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(poll.q2().toString()));

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isEqualTo(poll.q2());
  }

  // @TS-062 — a token minted for poll A can't close on poll B (mirrors @TS-054 for /activate).
  @Test
  void cross_poll_token_rejected_on_close() throws Exception {
    PollFixture pollA = createPoll("deck-close-cross-a");
    PollFixture pollB = createPoll("deck-close-cross-b");
    String plaintextForA = mintToken(pollA);

    mvc.perform(
            post("/api/deck/polls/" + pollB.pollId() + "/close")
                .header("X-Deck-Token", plaintextForA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DECK_TOKEN_POLL_MISMATCH"));
  }

  // ---------- fixtures -----------------------------------------------------

  private void activateViaDeck(UUID pollId, String plaintext, UUID questionId) throws Exception {
    mvc.perform(
            post("/api/deck/polls/" + pollId + "/activate")
                .header("X-Deck-Token", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + questionId + "\"}"))
        .andExpect(status().isOk());
  }

  private String mintToken(PollFixture poll) throws Exception {
    return mintTokenRaw(poll).get("plaintext").asString();
  }

  private JsonNode mintTokenRaw(PollFixture poll) throws Exception {
    MockHttpSession session = loginAsAlice();
    MvcResult mint =
        mvc.perform(
                post("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
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
              "title": "Deck fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]},
                {"prompt":"Q2?","options":[{"label":"X"},{"label":"Y"}]}
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
    UUID q2 = UUID.fromString(poll.get("questions").get(1).get("id").asString());
    return new PollFixture(pollId, q1, q2);
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

  private record PollFixture(UUID pollId, UUID q1, UUID q2) {}
}
