package site.asm0dey.slidev.polls.api.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * End-to-end coverage for the voter retract path — {@code DELETE /api/polls/{slug}/votes}.
 *
 * <p>Mirrors {@link VoteSubmissionIT}: real admin login → create poll → open question, then
 * exercise the public DELETE endpoint with the {@code sp_voter} cookie minted by the GET path.
 *
 * <p>Scenarios:
 *
 * <ul>
 *   <li>Happy retract while the question is active — 204 and the row is gone.
 *   <li>Idempotent retract with no prior vote — 204 (no row, no error).
 *   <li>Retract without any {@code sp_voter} cookie — 204 (nothing to retract).
 *   <li>Retract after the presenter closed the active question — 409 with {@code
 *       QUESTION_NOT_ACTIVE}, and the original row stays put.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VoteRetractionIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private VoteRepository voteRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // Happy path: cast then retract while the question is still active — 204 and the votes row
  // disappears.
  @Test
  void voter_can_retract_vote_while_question_active() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("retract-happy");
    Cookie sessionCookie = mintVoterCookie("retract-happy");

    String body = String.format("{\"optionId\":\"%s\"}", poll.optionAId());
    mvc.perform(
            post("/api/polls/retract-happy/votes")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    mvc.perform(delete("/api/polls/retract-happy/votes").cookie(sessionCookie))
        .andExpect(status().isNoContent());

    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), sessionCookie.getValue()))
        .as("row deleted after retract")
        .isFalse();
  }

  // Idempotency: a DELETE from a freshly minted voter that never cast a vote is a silent 204.
  @Test
  void retract_without_prior_vote_is_silent_204() throws Exception {
    createPollWithActiveQuestion("retract-noop");
    Cookie sessionCookie = mintVoterCookie("retract-noop");

    mvc.perform(delete("/api/polls/retract-noop/votes").cookie(sessionCookie))
        .andExpect(status().isNoContent());
  }

  // No sp_voter cookie at all: still 204 — there is nothing to retract and we do not punish a
  // client that arrives without the cookie.
  @Test
  void retract_without_cookie_is_204() throws Exception {
    createPollWithActiveQuestion("retract-no-cookie");

    mvc.perform(delete("/api/polls/retract-no-cookie/votes")).andExpect(status().isNoContent());
  }

  // Retract after the presenter closed the active question — 409 QUESTION_NOT_ACTIVE, and the
  // original row stays put (no silent purge after the window closed).
  @Test
  void retract_after_close_is_rejected_with_question_not_active() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("retract-closed");
    Cookie sessionCookie = mintVoterCookie("retract-closed");

    // Cast.
    String body = String.format("{\"optionId\":\"%s\"}", poll.optionAId());
    mvc.perform(
            post("/api/polls/retract-closed/votes")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    // Presenter closes the active question.
    MockHttpSession session = loginAsAlice();
    mvc.perform(post("/api/admin/polls/" + poll.pollId() + "/close").session(session).with(csrf()))
        .andExpect(status().isOk());

    // Retract now must fail.
    mvc.perform(delete("/api/polls/retract-closed/votes").cookie(sessionCookie))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QUESTION_NOT_ACTIVE"));

    // Row stays.
    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), sessionCookie.getValue()))
        .as("vote row preserved when retract is rejected post-close")
        .isTrue();
  }

  // ---------- fixtures (copied from VoteSubmissionIT) ---------------------

  private Cookie mintVoterCookie(String slug) throws Exception {
    MvcResult seen =
        mvc.perform(get("/api/polls/by-slug/" + slug)).andExpect(status().isOk()).andReturn();
    return seen.getResponse().getCookie("sp_voter");
  }

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
