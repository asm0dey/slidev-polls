package site.asm0dey.slidev.polls.api.admin;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Full-stack coverage for the presenter question-lifecycle transitions authored against {@code
 * /api/admin/polls/{pollId}/open} and {@code /close}. Boots the real application context against a
 * Testcontainers Postgres, runs Flyway so "alice" is seeded, and drives the HTTP surface the SPA
 * will use.
 *
 * <p>Scenarios exercised:
 *
 * <ul>
 *   <li>@TS-003 — activating a second question atomically closes the first. After {@code open(Q2)},
 *       Q1 is {@code CLOSED}, Q2 is {@code ACTIVE}, and the poll's {@code activeQuestionId} points
 *       at Q2.
 *   <li>@TS-005 — closing the active question clears {@code activeQuestionId} and marks the
 *       question {@code CLOSED}. The downstream "vote against a closed question is rejected with
 *       {@code QUESTION_NOT_ACTIVE}" half of TS-005 is exercised by {@code VoteSubmissionIT} in US2
 *       (T071), where the vote endpoint lives; this IT stops at the state transition because the
 *       vote endpoint does not yet exist in US1.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QuestionLifecycleIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-003 — opening Q2 while Q1 is ACTIVE atomically closes Q1. The poll's active_question
  // pointer flips from Q1 to Q2 in the same transaction.
  @Test
  void activating_a_second_question_atomically_closes_the_first() throws Exception {
    MockHttpSession session = loginAsAlice();
    JsonNode detail = createTwoQuestionPoll(session, "Lifecycle demo one");
    String pollId = detail.get("id").asString();
    String q1Id = detail.get("questions").get(0).get("id").asString();
    String q2Id = detail.get("questions").get(1).get("id").asString();

    // Open Q1 first so the "atomic close" path has something to close.
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + q1Id + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(q1Id))
        .andExpect(jsonPath("$.questions[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.questions[1].status").value("DRAFT"));

    // Now open Q2 — this is the assertion @TS-003 cares about. Q1 must flip to CLOSED in the
    // same response, Q2 becomes ACTIVE, activeQuestionId points at Q2.
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + q2Id + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(q2Id))
        .andExpect(jsonPath("$.questions[0].status").value("CLOSED"))
        .andExpect(jsonPath("$.questions[1].status").value("ACTIVE"));
  }

  // @TS-005 — closing the active question clears activeQuestionId on the poll and flips the
  // question's status to CLOSED. The vote-rejection tail of TS-005 belongs to US2.
  @Test
  void closing_the_active_question_clears_the_pointer_and_marks_it_closed() throws Exception {
    MockHttpSession session = loginAsAlice();
    JsonNode detail = createTwoQuestionPoll(session, "Lifecycle demo two");
    String pollId = detail.get("id").asString();
    String q1Id = detail.get("questions").get(0).get("id").asString();

    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + q1Id + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(q1Id));

    mvc.perform(post("/api/admin/polls/" + pollId + "/close").session(session).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").value(nullValue()))
        .andExpect(jsonPath("$.questions[0].status").value("CLOSED"));
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

  // A poll with two DRAFT questions (each carrying two options so activation's ≥2-options
  // precondition is satisfied) and a unique slug, so the tests in this class don't race on the
  // slug uniqueness index when they share a container.
  private JsonNode createTwoQuestionPoll(MockHttpSession session, String title) throws Exception {
    String body =
        """
        {
          "title": "%s",
          "questions": [
            { "prompt": "Q1?", "options": [ { "label": "A" }, { "label": "B" } ] },
            { "prompt": "Q2?", "options": [ { "label": "A" }, { "label": "B" } ] }
          ]
        }
        """
            .formatted(title);
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(created.getResponse().getContentAsString());
  }
}
