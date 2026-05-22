package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.service.VoteRepository;

/**
 * Full-stack coverage for the FR-013 RESOURCE_HAS_VOTES lock on {@code PATCH
 * /api/admin/polls/{id}}, ported to {@code @QuarkusTest} + RestAssured. Seeds a poll, activates a
 * question, inserts a vote via {@link VoteRepository}, then PATCHes a destructive payload (option
 * delete) and asserts the 409 {@code RESOURCE_HAS_VOTES} response shape.
 */
@QuarkusTest
class QuestionLockIT {

  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher hasher;
  @Inject VoteRepository voteRepository;

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

  @Test
  void destructive_edit_against_voted_question_returns_409_resource_has_votes() {
    Session admin = loginAsAlice();
    Response detail = createTwoOptionPoll(admin, "Lock demo", "lock-demo");
    UUID pollId = UUID.fromString(detail.path("id"));
    UUID qid = UUID.fromString(detail.path("questions[0].id"));
    UUID oA = UUID.fromString(detail.path("questions[0].options[0].id"));
    UUID oB = UUID.fromString(detail.path("questions[0].options[1].id"));

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + qid + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);

    voteRepository.insert(
        new Vote(UUID.randomUUID(), pollId, qid, List.of(oA), "voter-token-1", Instant.now()));

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

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(patchBody)
        .when()
        .patch("/api/admin/polls/" + pollId)
        .then()
        .statusCode(409)
        .body("code", equalTo("RESOURCE_HAS_VOTES"))
        .body("errors.'OPTION." + oA + "'", instanceOf(List.class));
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

  private Response createTwoOptionPoll(Session admin, String title, String slug) {
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
