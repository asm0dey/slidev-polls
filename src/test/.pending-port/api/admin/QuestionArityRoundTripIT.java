package site.asm0dey.slidev.polls.api.admin;

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

/**
 * Round-trip coverage for per-question arity on the admin DTOs: the request accepts {@code
 * minSelections}/{@code maxSelections} (defaulting to {@code (1, 1)} when both omitted) and the
 * response exposes them alongside a {@code voteCount} field that is zero on a freshly-created poll.
 * Pinning these here keeps the SPA contract honest before the voter path picks up multi-select.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QuestionArityRoundTripIT {

  @Autowired private MockMvc mvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS).execute();
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  @Test
  void createPersistsAndReturnsArityAndZeroVoteCount() throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        """
        {
          "title": "Multi demo",
          "slug": "multi-demo",
          "questions": [
            {"prompt": "single?", "minSelections": 1, "maxSelections": 1,
             "options": [{"label": "a"}, {"label": "b"}]},
            {"prompt": "multi?",  "minSelections": 0, "maxSelections": 3,
             "options": [{"label": "x"}, {"label": "y"}, {"label": "z"}]}
          ]
        }
        """;

    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.questions[0].minSelections").value(1))
        .andExpect(jsonPath("$.questions[0].maxSelections").value(1))
        .andExpect(jsonPath("$.questions[0].voteCount").value(0))
        .andExpect(jsonPath("$.questions[1].minSelections").value(0))
        .andExpect(jsonPath("$.questions[1].maxSelections").value(3))
        .andExpect(jsonPath("$.questions[1].voteCount").value(0));
  }

  @Test
  void omittingArityDefaultsToOneOne() throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        """
        {
          "title": "Legacy",
          "slug": "legacy-poll",
          "questions": [
            {"prompt": "old?", "options": [{"label": "yes"}, {"label": "no"}]}
          ]
        }
        """;

    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.questions[0].minSelections").value(1))
        .andExpect(jsonPath("$.questions[0].maxSelections").value(1));
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
}
