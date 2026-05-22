package site.asm0dey.slidev.polls.api.deck;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @TS-125 / @TS-126 — every backend route that mutates a poll's active-question pointer MUST reject
 * anonymous callers with one of {@code AUTH_REQUIRED} / {@code DECK_TOKEN_INVALID} / {@code
 * FORBIDDEN}, and feature 002 MUST NOT introduce a new such route.
 *
 * <p>Ported to {@code @QuarkusTest} + RestAssured. The audit stays path-driven: the active-question
 * mutators today are {@code POST /api/deck/polls/{pollId}/activate} (deck-token-guarded) and {@code
 * POST /api/admin/polls/{pollId}/open} (session-guarded). Adding a third requires extending {@link
 * #EXPECTED_ROUTES} deliberately — that is how TS-126 is enforced in source rather than docs.
 */
@QuarkusTest
class ActiveQuestionMutationRouteAuditTest {

  private record Route(String method, String pathTemplate, List<String> acceptableCodes) {}

  private static final List<Route> EXPECTED_ROUTES =
      List.of(
          new Route("POST", "/api/deck/polls/{pollId}/activate", List.of("DECK_TOKEN_INVALID")),
          new Route(
              "POST", "/api/admin/polls/{pollId}/open", List.of("AUTH_REQUIRED", "FORBIDDEN")));

  @BeforeAll
  static void noCharsetOnJson() {
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  @Test
  void every_active_question_mutator_rejects_anonymous_callers() {
    UUID pollId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    String body = "{\"questionId\":\"" + questionId + "\"}";

    for (Route r : EXPECTED_ROUTES) {
      String path = r.pathTemplate().replace("{pollId}", pollId.toString());
      Response result =
          given()
              .contentType(ContentType.JSON)
              .body(body)
              .when()
              .post(path)
              .then()
              .extract()
              .response();
      int status = result.statusCode();
      assertThat(status).as("status for anonymous %s %s", r.method(), path).isIn(401, 403);

      String code = result.path("code");
      assertThat(code)
          .as("problem code for anonymous %s %s", r.method(), path)
          .isIn(r.acceptableCodes());
    }
  }

  // @TS-126 — feature 002 MUST NOT introduce an additional active-question-mutating route.
  @Test
  void active_question_mutator_set_is_exactly_two_routes() {
    assertThat(EXPECTED_ROUTES).hasSize(2);
  }
}
