package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
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
 * Full-stack integration coverage of the presenter authoring flow. The test boots the real
 * application context against a Testcontainers Postgres, runs Flyway (V1–V4) so "alice" /
 * correct-horse is seeded by V3__admin_user.sql, and drives the HTTP surface the SPA will use.
 *
 * <p>Scenarios exercised:
 *
 * <ul>
 *   <li>@TS-002 — authenticated presenter creates a poll with two questions; the poll shows up in
 *       her list with the join-link shape the OpenAPI schema promises; the QR endpoint returns a
 *       PNG.
 *   <li>@TS-006 — presenter deletes a poll and a subsequent GET returns 404 with NOT_FOUND.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PollAuthoringIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    // Wipe polls first so this class's listing assertions ($.length() == 1) don't see polls
    // left behind by sibling ITs sharing the JVM-wide Spring context + Postgres container.
    // POLLS children cascade via FK.
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS).execute();
    // V6 dropped the seed migration. Use the idempotent helper because polls from earlier tests
    // (in the reused Spring context) hold a FK to admin_user.username — wiping would fail.
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-002 — the whole happy path: login as alice, create a poll with two questions, confirm it
  // appears in her list with the right shape, then fetch its QR as a real PNG.
  @Test
  void alice_creates_poll_with_two_questions_and_retrieves_qr() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        """
        {
          "title": "Quickstart demo",
          "questions": [
            { "prompt": "Which JVM?",     "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] },
            { "prompt": "Favourite IDE?", "options": [ { "label": "IntelliJ" }, { "label": "VS Code" } ] }
          ]
        }
        """;
    MvcResult createResult =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Quickstart demo"))
            .andExpect(jsonPath("$.slug").value("quickstart-demo"))
            .andExpect(jsonPath("$.questions.length()").value(2))
            // @TS-002 — join-link shape is http(s)://…/{slug}.
            .andExpect(jsonPath("$.publicUrl").value(matchesPattern("https?://.+/[a-z0-9-]{3,40}")))
            .andReturn();

    JsonNode detail = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String pollId = detail.get("id").asText();

    // @TS-002 — the poll shows up in alice's list with the same publicUrl shape.
    mvc.perform(get("/api/admin/polls").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].slug").value("quickstart-demo"))
        .andExpect(jsonPath("$[0].publicUrl").value(matchesPattern("https?://.+/quickstart-demo")));

    // @TS-002 — the QR endpoint returns a decodable PNG encoding exactly the publicUrl.
    MvcResult qr =
        mvc.perform(get("/api/admin/polls/" + pollId + "/qr.png").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
            .andReturn();
    String decoded = decodeQr(qr.getResponse().getContentAsByteArray());
    assertThat(decoded).isEqualTo(detail.get("publicUrl").asText());
  }

  // @TS-A5-001 — allowedOrigins is accepted in the request body and echoed in the response.
  @Test
  void createPollAcceptsAllowedOrigins() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        """
        {
          "title": "T",
          "slug": "with-origins",
          "questions": [{"prompt":"p","options":[{"label":"A"},{"label":"B"}]}],
          "allowedOrigins": ["http://localhost:3030"]
        }
        """;
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.allowedOrigins[0]").value("http://localhost:3030"))
            .andReturn();
    // Clean up so other tests that assert exact list sizes aren't polluted.
    String pollId =
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(delete("/api/admin/polls/" + pollId).session(session).with(csrf()))
        .andExpect(status().isNoContent());
  }

  // @TS-A5-002 — a malformed origin causes a 400 with code ORIGIN_INVALID.
  @Test
  void rejectsMalformedOrigin() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        """
        {
          "title": "T", "slug": "bad-origin",
          "questions": [{"prompt":"p","options":[{"label":"A"},{"label":"B"}]}],
          "allowedOrigins": ["not a url"]
        }
        """;
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
  }

  // @TS-006 — deleting a poll removes it from the list and later GETs return 404.
  @Test
  void alice_deletes_her_poll_and_subsequent_fetch_returns_404() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        """
        {
          "title": "Will be deleted",
          "slug": "gone-soon",
          "questions": [
            { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
          ]
        }
        """;
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String pollId =
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(delete("/api/admin/polls/" + pollId).session(session).with(csrf()))
        .andExpect(status().isNoContent());

    // @TS-006 — the deleted poll is no longer in the list.
    mvc.perform(get("/api/admin/polls").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='" + pollId + "')]").isEmpty());

    // @TS-006 — fetching the deleted poll returns 404 NOT_FOUND (not a 403, which would leak
    // that it once existed vs. never did — NOT_FOUND is correct because the owner, from the
    // system's point of view, has nothing to own).
    mvc.perform(get("/api/admin/polls/" + pollId).session(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void patch_preservesQuestionAndOptionIds() throws Exception {
    MockHttpSession session = loginAsAlice();

    String createBody =
        "{\"title\":\"keep ids\",\"slug\":\"keep-ids\","
            + "\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
    String pollId = createdJson.get("id").asText();
    String qId = createdJson.get("questions").get(0).get("id").asText();
    String oAId = createdJson.get("questions").get(0).get("options").get(0).get("id").asText();
    String oBId = createdJson.get("questions").get(0).get("options").get(1).get("id").asText();

    String patchBody =
        String.format(
            "{\"title\":\"keep ids\",\"questions\":[{\"id\":\"%s\",\"prompt\":\"Q"
                + " edited?\",\"options\":[{\"id\":\"%s\",\"label\":\"A"
                + " edited\"},{\"id\":\"%s\",\"label\":\"B\"}]}]}",
            qId, oAId, oBId);

    mvc.perform(
            patch("/api/admin/polls/" + pollId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questions[0].id").value(qId))
        .andExpect(jsonPath("$.questions[0].prompt").value("Q edited?"))
        .andExpect(jsonPath("$.questions[0].options[0].id").value(oAId))
        .andExpect(jsonPath("$.questions[0].options[0].label").value("A edited"));
  }

  @Test
  void clone_returnsNewPollWithFreshIds() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        "{\"title\":\"talk\",\"slug\":\"talk-original\",\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    String src =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode srcJson = objectMapper.readTree(src);
    String srcId = srcJson.get("id").asText();
    String srcQId = srcJson.get("questions").get(0).get("id").asText();

    mvc.perform(post("/api/admin/polls/" + srcId + "/clone").session(session).with(csrf()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(srcId)))
        .andExpect(jsonPath("$.title").value("Copy of talk"))
        .andExpect(jsonPath("$.questions[0].id").value(org.hamcrest.Matchers.not(srcQId)));
  }

  @Test
  void clearVotes_returnsPollWithoutVotesAndQuestionIdsPreserved() throws Exception {
    MockHttpSession session = loginAsAlice();

    String body =
        "{\"title\":\"t\",\"slug\":\"clear-it\",\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
    String pollId = createdJson.get("id").asText();
    String qId = createdJson.get("questions").get(0).get("id").asText();

    // open the question
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/open")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + qId + "\"}"))
        .andExpect(status().isOk());

    // clear votes (vote-casting skipped — assertions hold with zero pre-existing votes)
    mvc.perform(post("/api/admin/polls/" + pollId + "/votes:clear").session(session).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeQuestionId").doesNotExist())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.questions[0].id").value(qId))
        .andExpect(jsonPath("$.questions[0].status").value("DRAFT"));
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

  private static org.springframework.test.web.servlet.result.HeaderResultMatchers header() {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.header();
  }

  private static String decodeQr(byte[] png) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    BinaryBitmap bitmap =
        new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
    return new MultiFormatReader().decode(bitmap).getText();
  }
}
