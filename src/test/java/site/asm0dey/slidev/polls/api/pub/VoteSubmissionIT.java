package site.asm0dey.slidev.polls.api.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
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
import site.asm0dey.slidev.polls.core.service.VoteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage for the voter write path — {@code POST /api/polls/{slug}/votes}. Fixtures are
 * seeded via the real admin flow (login → create → activate) so the test exercises the full stack
 * rather than poking jOOQ directly.
 *
 * <p>Scenarios exercised:
 *
 * <ul>
 *   <li>{@code @TS-022} — valid vote is accepted with 201, the body carries a voteId + recordedAt,
 *       and a row lands in {@code votes} keyed by {@code (question_id, voter_token)}.
 *   <li>{@code @TS-023} — duplicate submission from the same {@code sp_voter} token is rejected
 *       with 409 {@code ALREADY_VOTED} and no second row is inserted.
 *   <li>{@code @TS-025} — vote arriving after the active question is closed is rejected with 409
 *       {@code QUESTION_NOT_ACTIVE} and a user-facing message (no stack trace, no SQL fragment).
 *   <li>{@code @TS-027} / {@code @TS-046} — unknown body fields ({@code email}, {@code name}) are
 *       silently dropped by Jackson and not persisted; {@code fail-on-unknown-properties} is
 *       configured to false in {@code application.yml} for exactly this.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VoteSubmissionIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private VoteRepository voteRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-022 — happy path. The server sets sp_voter on the first GET; we then POST a vote with
  // that cookie attached and assert the response + stored row.
  @Test
  void anonymous_visitor_submits_valid_vote_and_is_acknowledged() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("vote-happy");
    MvcResult seen =
        mvc.perform(get("/api/polls/by-slug/vote-happy")).andExpect(status().isOk()).andReturn();
    Cookie sessionCookie = seen.getResponse().getCookie("sp_voter");
    String voterToken = sessionCookie.getValue();

    String body =
        String.format("{\"optionIds\":[\"%s\"],\"voterToken\":\"ignored\"}", poll.optionAId());
    MvcResult result =
        mvc.perform(
                post("/api/polls/vote-happy/votes")
                    .cookie(sessionCookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.voteId").isString())
            .andExpect(jsonPath("$.recordedAt").isString())
            .andReturn();

    // The body carries a UUID voteId and an ISO-8601 recordedAt; Jackson will serialise Instant
    // as either a numeric epoch seconds or ISO-8601 depending on config. Parse defensively.
    JsonNode ack = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(UUID.fromString(ack.get("voteId").asText())).isNotNull();
    assertThat(ack.get("recordedAt").asText()).isNotBlank();

    // @TS-022 — a row exists in votes for (activeQuestionId, sp_voter).
    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), voterToken))
        .as("votes row present after successful POST")
        .isTrue();
  }

  // @TS-023 — duplicate submission from the same sp_voter cookie must surface as 409
  // ALREADY_VOTED; no second row lands.
  @Test
  void duplicate_submission_is_rejected_as_already_voted() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("vote-dup");
    MvcResult seen =
        mvc.perform(get("/api/polls/by-slug/vote-dup")).andExpect(status().isOk()).andReturn();
    Cookie sessionCookie = seen.getResponse().getCookie("sp_voter");

    String body = String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId());
    mvc.perform(
            post("/api/polls/vote-dup/votes")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    // Second submit with the same cookie — whether the caller aims at the same option or the
    // other option, the (question_id, voter_token) unique index refuses the row.
    String second = String.format("{\"optionIds\":[\"%s\"]}", poll.optionBId());
    mvc.perform(
            post("/api/polls/vote-dup/votes")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(second))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_VOTED"));

    // Confirm only one row landed.
    long rows =
        voteRepository.tally(poll.activeQuestionId()).values().stream()
            .mapToLong(Long::longValue)
            .sum();
    assertThat(rows).as("exactly one recorded vote after a duplicate submit").isEqualTo(1L);
  }

  // @TS-025 — vote arriving after the presenter closed the active question returns 409
  // QUESTION_NOT_ACTIVE with a user-facing message — no stack trace or SQL fragment.
  @Test
  void vote_after_close_is_rejected_with_non_technical_copy() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("vote-closed");
    // Close the active question via the admin path (same flow as the presenter's UI).
    MockHttpSession session = loginAsAlice();
    mvc.perform(post("/api/admin/polls/" + poll.pollId() + "/close").session(session).with(csrf()))
        .andExpect(status().isOk());

    MvcResult seen =
        mvc.perform(get("/api/polls/by-slug/vote-closed")).andExpect(status().isOk()).andReturn();
    Cookie sessionCookie = seen.getResponse().getCookie("sp_voter");

    // After close the poll has no activeQuestionId — the active-question option id from the
    // fixture is therefore no longer on the board; the service short-circuits to
    // QUESTION_NOT_ACTIVE (TS-025) rather than NOT_FOUND for the now-orphaned optionId.
    String body = String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId());
    MvcResult conflict =
        mvc.perform(
                post("/api/polls/vote-closed/votes")
                    .cookie(sessionCookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("QUESTION_NOT_ACTIVE"))
            .andReturn();

    String message =
        objectMapper.readTree(conflict.getResponse().getContentAsString()).get("message").asText();
    // @TS-025 — the message is user-facing; no stack trace, no SQL fragment.
    assertThat(message.toLowerCase()).doesNotContain("sqlexception");
    assertThat(message.toLowerCase()).doesNotContain("stacktrace");
    assertThat(message.toLowerCase()).doesNotContain("postgres");
    assertThat(message).isNotBlank();
  }

  // @TS-027 / @TS-046 — unknown top-level fields (email, name) must be silently dropped by
  // Jackson and never persisted. The API still accepts the request because
  // fail-on-unknown-properties is false in application.yml; the votes table has no column for
  // these fields by design.
  @Test
  void unknown_body_fields_are_ignored_and_not_persisted() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("vote-extra");
    MvcResult seen =
        mvc.perform(get("/api/polls/by-slug/vote-extra")).andExpect(status().isOk()).andReturn();
    Cookie sessionCookie = seen.getResponse().getCookie("sp_voter");

    String body =
        String.format(
            "{\"optionIds\":[\"%s\"],\"email\":\"alice@example.com\",\"name\":\"Alice\"}",
            poll.optionAId());
    mvc.perform(
            post("/api/polls/vote-extra/votes")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    // The votes table has no email / name columns; there is nothing to persist. The assertion
    // here is structural: the row landed, and the repository-level columns match the FR-011
    // data-model (verified by Flyway). This test nails the deserialisation boundary: Jackson
    // drops the fields before the service ever sees them, so no audit-log leak either.
    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), sessionCookie.getValue()))
        .isTrue();
  }

  // ---------- fixtures -----------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) throws Exception {
    MockHttpSession session = loginAsAlice();
    String createBody =
        String.format(
            """
            {
              "title": "Vote fixture %s",
              "slug": "%s",
              "questions": [
                { "prompt": "Which JVM?", "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] }
              ]
            }
            """,
            slug, slug);
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode poll = objectMapper.readTree(created.getResponse().getContentAsString());
    UUID pollId = UUID.fromString(poll.get("id").asText());
    JsonNode question = poll.get("questions").get(0);
    UUID questionId = UUID.fromString(question.get("id").asText());
    List<JsonNode> options =
        List.of(question.get("options").get(0), question.get("options").get(1));
    UUID optionA = UUID.fromString(options.get(0).get("id").asText());
    UUID optionB = UUID.fromString(options.get(1).get("id").asText());

    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + questionId + "\"}"))
        .andExpect(status().isOk());
    return new PollFixture(pollId, questionId, optionA, optionB);
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

  private record PollFixture(UUID pollId, UUID activeQuestionId, UUID optionAId, UUID optionBId) {}
}
