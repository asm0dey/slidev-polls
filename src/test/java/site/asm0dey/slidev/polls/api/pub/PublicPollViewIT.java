package site.asm0dey.slidev.polls.api.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Anonymous read surface for the voter SPA — {@code GET /api/polls/by-slug/{slug}}. The authoring
 * flow (login → create → activate) comes in through the real admin endpoints so the fixture mirrors
 * what a presenter would actually set up; the voter-facing assertions are then made over a fresh
 * {@link MockMvc} request with no session attached.
 *
 * <p>Scenarios exercised:
 *
 * <ul>
 *   <li>{@code @TS-020} — anonymous visitor opens a poll with an ACTIVE question: 200 status,
 *       state=ACTIVE, active question and options present, no auth challenge, no PII fields.
 *   <li>{@code @TS-021} — anonymous visitor opens a poll whose questions are all DRAFT:
 *       state=WAITING, activeQuestion absent, no error.
 *   <li>{@code @TS-045} — slug path-variable in an invalid format (upper-case): 404 with a
 *       recognised Problem code (NOT_FOUND).
 *   <li>{@code @TS-046} — the first-touch response sets an {@code sp_voter} cookie that is
 *       HttpOnly, SameSite=Lax, and whose value is a bare UUID (no PII).
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublicPollViewIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse", "Alice Presenter");
  }

  // @TS-020 — anonymous GET on a poll with an ACTIVE question returns the live question inline
  // with state=ACTIVE; no auth is required and no PII fields leak into the response.
  @Test
  void anonymous_visitor_sees_active_question_and_options() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("my-talk");

    MvcResult result =
        mvc.perform(get("/api/polls/by-slug/my-talk"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(jsonPath("$.pollId").value(poll.pollId().toString()))
            .andExpect(jsonPath("$.slug").value("my-talk"))
            .andExpect(jsonPath("$.title").exists())
            .andExpect(jsonPath("$.state").value("ACTIVE"))
            .andExpect(jsonPath("$.activeQuestion.id").value(poll.activeQuestionId().toString()))
            .andExpect(jsonPath("$.activeQuestion.options.length()").value(2))
            // @TS-020 / @TS-046 — no PII: the response must not carry owner, email, name, or
            // any similarly-shaped column. Assert on the absent fields explicitly so the intent
            // survives future DTO edits.
            .andExpect(jsonPath("$.owner").doesNotExist())
            .andExpect(jsonPath("$.ownerUsername").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.name").doesNotExist())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.has("activeQuestion")).isTrue();
    assertThat(body.get("activeQuestion").get("options").size()).isEqualTo(2);
  }

  // @TS-046 — first-touch anonymous GET must set an sp_voter cookie that is HttpOnly and
  // SameSite=Lax, with a UUID-shaped value (no PII by construction).
  @Test
  void first_anonymous_visit_sets_sp_voter_cookie() throws Exception {
    createPollWithActiveQuestion("cookie-talk");

    MvcResult result =
        mvc.perform(get("/api/polls/by-slug/cookie-talk"))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("sp_voter"))
            .andExpect(cookie().httpOnly("sp_voter", true))
            .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).as("Set-Cookie header on first-touch GET").isNotNull();
    assertThat(setCookie).contains("SameSite=Lax");
    assertThat(setCookie).contains("HttpOnly");
    String cookieValue = result.getResponse().getCookie("sp_voter").getValue();
    // @TS-046 — the value is a bare UUID; no device fingerprint, no personal-shaped data.
    assertThat(UUID.fromString(cookieValue)).isNotNull();
  }

  // @TS-021 — a poll with no active question returns state=WAITING without an activeQuestion
  // field; the response is 200 rather than an error.
  @Test
  void anonymous_visitor_sees_waiting_state_when_no_question_is_active() throws Exception {
    createPollWithDraftQuestion("waiting-talk");

    mvc.perform(get("/api/polls/by-slug/waiting-talk"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"))
        .andExpect(jsonPath("$.activeQuestion").doesNotExist());
  }

  // @TS-045 — a slug whose format does not match the kebab-case pattern is rejected at the edge
  // with a recognised Problem code. 404 is accepted (per TS-045: 400 or 404) and reads more
  // cleanly to the audience than a validation-error message.
  @Test
  void invalid_slug_format_returns_not_found() throws Exception {
    mvc.perform(get("/api/polls/by-slug/UPPER"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // Companion assertion: a well-formed slug that does not resolve to any poll is also 404. This
  // pins the distinction between "invalid shape" and "valid shape, no such poll"; both surface
  // the same code, which is the consistent behaviour for TS-045's Problem-envelope contract.
  @Test
  void unknown_slug_returns_not_found() throws Exception {
    mvc.perform(get("/api/polls/by-slug/nope-not-here"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---------- fixtures -----------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) throws Exception {
    MockHttpSession session = loginAsAlice();
    String createBody =
        String.format(
            """
            {
              "title": "Public fixture %s",
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
    String pollId = poll.get("id").asText();
    String questionId = poll.get("questions").get(0).get("id").asText();

    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + questionId + "\"}"))
        .andExpect(status().isOk());
    return new PollFixture(UUID.fromString(pollId), UUID.fromString(questionId));
  }

  private void createPollWithDraftQuestion(String slug) throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        String.format(
            """
            {
              "title": "Waiting fixture %s",
              "slug": "%s",
              "questions": [
                { "prompt": "Hold", "options": [ { "label": "A" }, { "label": "B" } ] }
              ]
            }
            """,
            slug, slug);
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    // Intentionally skip the /open call — the question stays DRAFT, so the voter sees WAITING.
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

  private record PollFixture(UUID pollId, UUID activeQuestionId) {}
}
