package site.asm0dey.slidev.polls.api.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.service.VoteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack coverage for the FR-013 RESOURCE_HAS_VOTES lock surfaced on {@code PATCH
 * /api/admin/polls/{pollId}}. Seeds a poll, activates a question, and inserts a vote against it via
 * the {@link VoteRepository}; then drives the admin PATCH endpoint with a destructive payload
 * (option-delete) and asserts the 409 + {@code RESOURCE_HAS_VOTES} response shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QuestionLockIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private VoteRepository voteRepository;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  @Test
  void destructive_edit_against_voted_question_returns_409_resource_has_votes() throws Exception {
    MockHttpSession session = loginAsAlice();
    JsonNode detail = createTwoOptionPoll(session, "Lock demo", "lock-demo");
    UUID pollId = UUID.fromString(detail.get("id").asString());
    JsonNode q = detail.get("questions").get(0);
    UUID qid = UUID.fromString(q.get("id").asString());
    UUID oA = UUID.fromString(q.get("options").get(0).get("id").asString());
    UUID oB = UUID.fromString(q.get("options").get(1).get("id").asString());

    // Activate Q1 so the votes are accepted by the storage layer's status check.
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + qid + "\"}"))
        .andExpect(status().isOk());

    // Seed a single recorded vote against option A.
    voteRepository.insert(
        new Vote(UUID.randomUUID(), pollId, qid, List.of(oA), "voter-token-1", Instant.now()));

    // Destructive edit: drop option A from the payload. Question still references option B and a
    // brand-new option C so the @Size(min=2) constraint passes; the lock fires server-side.
    String patchBody =
        String.format(
            """
            {
              "questions": [
                {
                  "id": "%s",
                  "prompt": "Which JVM?",
                  "options": [
                    { "id": "%s", "label": "GraalVM" },
                    { "label": "Substrate" }
                  ]
                }
              ]
            }
            """,
            qid, oB);

    mvc.perform(
            patch("/api/admin/polls/" + pollId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESOURCE_HAS_VOTES"))
        .andExpect(jsonPath("$.errors['OPTION." + oA + "']").isArray());
  }

  // ---------- fixtures -----------------------------------------------------

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

  private JsonNode createTwoOptionPoll(MockHttpSession session, String title, String slug)
      throws Exception {
    String body =
        String.format(
            """
            {
              "title": "%s",
              "slug": "%s",
              "questions": [
                { "prompt": "Which JVM?", "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] }
              ]
            }
            """,
            title, slug);
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
