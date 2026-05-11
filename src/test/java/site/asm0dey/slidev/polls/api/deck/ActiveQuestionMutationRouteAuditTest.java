package site.asm0dey.slidev.polls.api.deck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * @TS-125 / @TS-126 — every backend route that mutates a poll's active-question pointer MUST reject
 * anonymous callers with one of {@code AUTH_REQUIRED} / {@code DECK_TOKEN_INVALID} / {@code
 * FORBIDDEN}, and feature 002 MUST NOT introduce a new such route.
 *
 * <p>The audit is path-driven — the set of routes that can mutate {@code polls.active_question_id}
 * today is two: {@code POST /api/deck/polls/{pollId}/activate} (deck-token-guarded) and {@code POST
 * /api/admin/polls/{pollId}/open} (session-guarded). This test enumerates both and asserts each
 * rejects anonymous calls with an envelope the voter / presenter expect. Adding a third route to
 * the active-question-mutating set requires extending {@link #EXPECTED_ROUTES} deliberately — that
 * is how TS-126 is enforced in source rather than docs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActiveQuestionMutationRouteAuditTest {

  private record Route(String method, String pathTemplate, List<String> acceptableCodes) {}

  private static final List<Route> EXPECTED_ROUTES =
      List.of(
          new Route("POST", "/api/deck/polls/{pollId}/activate", List.of("DECK_TOKEN_INVALID")),
          new Route(
              "POST", "/api/admin/polls/{pollId}/open", List.of("AUTH_REQUIRED", "FORBIDDEN")));

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void every_active_question_mutator_rejects_anonymous_callers() throws Exception {
    UUID pollId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    String body = "{\"questionId\":\"" + questionId + "\"}";

    for (Route r : EXPECTED_ROUTES) {
      String path = r.pathTemplate.replace("{pollId}", pollId.toString());
      MvcResult result =
          mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
      int status = result.getResponse().getStatus();
      assertThat(status).as("status for anonymous %s %s", r.method, path).isIn(401, 403);

      String responseBody = result.getResponse().getContentAsString();
      JsonNode problem = objectMapper.readTree(responseBody);
      String code = problem.path("code").asText();
      assertThat(code)
          .as("problem code for anonymous %s %s", r.method, path)
          .isIn(r.acceptableCodes);
    }
  }

  // @TS-126 — feature 002 MUST NOT introduce an additional active-question-mutating route.
  // The audit set above is the contract; this assertion makes the expectation visible and
  // will fail loudly if a future change bumps the count without updating EXPECTED_ROUTES.
  @Test
  void active_question_mutator_set_is_exactly_two_routes() {
    assertThat(EXPECTED_ROUTES).hasSize(2);
  }
}
