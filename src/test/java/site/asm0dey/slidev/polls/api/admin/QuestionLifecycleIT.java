package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * Question-lifecycle transitions ({@code /open}, {@code /close}) ported to {@code @QuarkusTest} +
 * RestAssured. State-changing admin calls use the real login + XSRF double-submit flow.
 *
 * <ul>
 *   <li>@TS-003 — activating a second question atomically closes the first.
 *   <li>@TS-005 — closing the active question clears {@code activeQuestionId} and marks it CLOSED.
 * </ul>
 */
@QuarkusTest
class QuestionLifecycleIT {

  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher hasher;

  @BeforeAll
  static void noCharsetOnJson() {
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, hasher, "alice", "correct-horse");
  }

  // @TS-003 — opening Q2 while Q1 is ACTIVE atomically closes Q1.
  @Test
  void activating_a_second_question_atomically_closes_the_first() {
    Session admin = loginAsAlice();
    Response detail = createTwoQuestionPoll(admin, "Lifecycle demo one");
    String pollId = detail.path("id");
    String q1Id = detail.path("questions[0].id");
    String q2Id = detail.path("questions[1].id");

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + q1Id + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200)
        .body("activeQuestionId", equalTo(q1Id))
        .body("questions[0].status", equalTo("ACTIVE"))
        .body("questions[1].status", equalTo("DRAFT"));

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + q2Id + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200)
        .body("activeQuestionId", equalTo(q2Id))
        .body("questions[0].status", equalTo("CLOSED"))
        .body("questions[1].status", equalTo("ACTIVE"));
  }

  // @TS-005 — closing the active question clears activeQuestionId and flips status to CLOSED.
  @Test
  void closing_the_active_question_clears_the_pointer_and_marks_it_closed() {
    Session admin = loginAsAlice();
    Response detail = createTwoQuestionPoll(admin, "Lifecycle demo two");
    String pollId = detail.path("id");
    String q1Id = detail.path("questions[0].id");

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + q1Id + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200)
        .body("activeQuestionId", equalTo(q1Id));

    admin
        .requestWithCsrf()
        .when()
        .post("/api/admin/polls/" + pollId + "/close")
        .then()
        .statusCode(200)
        .body("activeQuestionId", nullValue())
        .body("questions[0].status", equalTo("CLOSED"));
  }

  private Session loginAsAlice() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"alice\",\"password\":\"correct-horse\"}")
            .when()
            .post("/api/admin/login")
            .then()
            .statusCode(204)
            .extract()
            .response();
    String session = login.getCookie("SP_SESSION");
    String xsrf = login.getCookie("XSRF-TOKEN");
    assertThat(session).isNotBlank();
    assertThat(xsrf).isNotBlank();
    return new Session(session, xsrf);
  }

  private Response createTwoQuestionPoll(Session admin, String title) {
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
    return admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201)
        .extract()
        .response();
  }

  private record Session(String sessionCookie, String xsrfCookie) {
    RequestSpecification requestWithCsrf() {
      return given()
          .cookie("SP_SESSION", sessionCookie)
          .cookie("XSRF-TOKEN", xsrfCookie)
          .header("X-XSRF-TOKEN", xsrfCookie);
    }
  }
}
