package site.asm0dey.slidev.polls.api.pub;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage for the multi-choice ballot shape on {@code POST /api/polls/{slug}/votes}.
 * The voter SPA now carries {@code optionIds: UUID[]} (Task 12); this IT pins the four boundary
 * cases plan-§Task-12 calls out: a multi-option ballot is accepted within arity bounds, an
 * abstention is accepted when {@code minSelections == 0}, a multi-option payload aimed at a
 * single-choice question is rejected as 400 {@code VALIDATION_FAILED}, and a legacy {@code
 * optionId} payload is rejected because Jackson swallows the unknown field under {@code
 * fail-on-unknown-properties: false} and the {@code @NotNull} constraint on {@code optionIds}
 * fires.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VoteSubmissionMultiChoiceIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // Three options, min=1, max=3 → "pick anywhere from 1 to 3". Two options on the ballot is
  // exactly the multi-choice case Task 12 added support for.
  @Test
  void multiOptionBallotAccepted() throws Exception {
    PollFixture p = seedActivePoll("multi-1", 3, /* min */ 1, /* max */ 3);
    Cookie cookie = visitorCookie("multi-1");
    String body = "{\"optionIds\":[\"" + p.options().get(0) + "\",\"" + p.options().get(2) + "\"]}";

    mockMvc
        .perform(
            post("/api/polls/multi-1/votes")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
  }

  // min=0 lets the voter submit nothing — the question is on the board, the voter saw it, and the
  // ballot is an explicit "no opinion". The empty array must round-trip cleanly to 201.
  @Test
  void abstainAcceptedWhenMinZero() throws Exception {
    seedActivePoll("abstain-1", 3, 0, 3);
    Cookie cookie = visitorCookie("abstain-1");

    mockMvc
        .perform(
            post("/api/polls/abstain-1/votes")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionIds\":[]}"))
        .andExpect(status().isCreated());
  }

  // Single-choice question = (min=1, max=1). A two-element payload violates the arity bound and
  // VoteService throws IllegalArgumentException, which the global handler maps to 400
  // VALIDATION_FAILED.
  @Test
  void singleChoiceRejectsMultiOption() throws Exception {
    PollFixture p = seedActivePoll("single-1", 3, 1, 1);
    Cookie cookie = visitorCookie("single-1");
    String body = "{\"optionIds\":[\"" + p.options().get(0) + "\",\"" + p.options().get(1) + "\"]}";

    mockMvc
        .perform(
            post("/api/polls/single-1/votes")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // Legacy clients that still send {"optionId": "..."} get a 400: Jackson silently drops the
  // unknown property (fail-on-unknown-properties: false) leaving optionIds null, which trips the
  // @NotNull on VoteRequest.
  @Test
  void legacyOptionIdRejected() throws Exception {
    seedActivePoll("legacy-1", 2, 1, 1);
    Cookie cookie = visitorCookie("legacy-1");

    mockMvc
        .perform(
            post("/api/polls/legacy-1/votes")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---------- fixtures -----------------------------------------------------

  private PollFixture seedActivePoll(String slug, int optionCount, int min, int max)
      throws Exception {
    MockHttpSession session = loginAsAlice();
    StringBuilder options = new StringBuilder();
    for (int i = 0; i < optionCount; i++) {
      if (i > 0) {
        options.append(",");
      }
      options.append("{ \"label\": \"opt-").append(i).append("\" }");
    }
    String createBody =
        String.format(
            """
            {
              "title": "Fixture %s",
              "slug": "%s",
              "questions": [
                {
                  "prompt": "Pick %d (min=%d max=%d)",
                  "minSelections": %d,
                  "maxSelections": %d,
                  "options": [ %s ]
                }
              ]
            }
            """,
            slug, slug, optionCount, min, max, min, max, options);
    MvcResult created =
        mockMvc
            .perform(
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
    List<UUID> optionIds = new ArrayList<>();
    for (JsonNode o : question.get("options")) {
      optionIds.add(UUID.fromString(o.get("id").asText()));
    }

    mockMvc
        .perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + questionId + "\"}"))
        .andExpect(status().isOk());
    return new PollFixture(pollId, questionId, List.copyOf(optionIds));
  }

  private Cookie visitorCookie(String slug) throws Exception {
    MvcResult seen =
        mockMvc.perform(get("/api/polls/by-slug/" + slug)).andExpect(status().isOk()).andReturn();
    return seen.getResponse().getCookie("sp_voter");
  }

  private MockHttpSession loginAsAlice() throws Exception {
    MvcResult login =
        mockMvc
            .perform(
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

  private record PollFixture(UUID pollId, UUID activeQuestionId, List<UUID> options) {}
}
