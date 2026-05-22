package site.asm0dey.slidev.polls.api.pub;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import site.asm0dey.slidev.polls.core.service.VoteRepository;

/**
 * End-to-end coverage for the voter retract path — {@code DELETE /api/polls/{slug}/votes}. Ported
 * to {@code @QuarkusTest} + RestAssured, mirroring {@code VoteSubmissionIT}.
 *
 * <ul>
 *   <li>Happy retract while the question is active — 204 and the row is gone.
 *   <li>Idempotent retract with no prior vote — 204 (no row, no error).
 *   <li>Retract without any {@code sp_voter} cookie — 204 (nothing to retract).
 *   <li>Retract after the presenter closed the active question — 409 {@code QUESTION_NOT_ACTIVE},
 *       and the original row stays put.
 * </ul>
 */
@QuarkusTest
class VoteRetractionIT {

  @Inject VoteRepository voteRepository;
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

  // Happy path: cast then retract while the question is active — 204 and the votes row disappears.
  @Test
  void voter_can_retract_vote_while_question_active() {
    PollFixture poll = createPollWithActiveQuestion("retract-happy");
    String voterToken = mintVoterCookie("retract-happy");

    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId()))
        .when()
        .post("/api/polls/retract-happy/votes")
        .then()
        .statusCode(201);

    given()
        .cookie("sp_voter", voterToken)
        .when()
        .delete("/api/polls/retract-happy/votes")
        .then()
        .statusCode(204);

    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), voterToken))
        .as("row deleted after retract")
        .isFalse();
  }

  // Idempotency: a DELETE from a freshly minted voter that never cast a vote is a silent 204.
  @Test
  void retract_without_prior_vote_is_silent_204() {
    createPollWithActiveQuestion("retract-noop");
    String voterToken = mintVoterCookie("retract-noop");

    given()
        .cookie("sp_voter", voterToken)
        .when()
        .delete("/api/polls/retract-noop/votes")
        .then()
        .statusCode(204);
  }

  // No sp_voter cookie at all: still 204 — nothing to retract, no punishment for a missing cookie.
  @Test
  void retract_without_cookie_is_204() {
    createPollWithActiveQuestion("retract-no-cookie");

    given().when().delete("/api/polls/retract-no-cookie/votes").then().statusCode(204);
  }

  // Retract after the presenter closed the active question — 409 QUESTION_NOT_ACTIVE, and the
  // original row stays put (no silent purge after the window closed).
  @Test
  void retract_after_close_is_rejected_with_question_not_active() {
    PollFixture poll = createPollWithActiveQuestion("retract-closed");
    String voterToken = mintVoterCookie("retract-closed");

    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId()))
        .when()
        .post("/api/polls/retract-closed/votes")
        .then()
        .statusCode(201);

    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .when()
        .post("/api/admin/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200);

    given()
        .cookie("sp_voter", voterToken)
        .when()
        .delete("/api/polls/retract-closed/votes")
        .then()
        .statusCode(409)
        .body("code", equalTo("QUESTION_NOT_ACTIVE"));

    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), voterToken))
        .as("vote row preserved when retract is rejected post-close")
        .isTrue();
  }

  // ---------- fixtures -----------------------------------------------------

  private String mintVoterCookie(String slug) {
    String token =
        given()
            .when()
            .get("/api/polls/by-slug/" + slug)
            .then()
            .statusCode(200)
            .extract()
            .cookie("sp_voter");
    assertThat(token).isNotBlank();
    return token;
  }

  private PollFixture createPollWithActiveQuestion(String slug) {
    Session admin = loginAsAlice();
    String createBody =
        String.format(
            """
            {
              "title": "Vote fixture %s",
              "slug": "%s",
              "questions": [
                { "prompt": "Which JVM?", "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] }
              ]
            }
            """,
            slug, slug);
    Response created =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(createBody)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .response();

    UUID pollId = UUID.fromString(created.path("id"));
    UUID questionId = UUID.fromString(created.path("questions[0].id"));
    UUID optionA = UUID.fromString(created.path("questions[0].options[0].id"));
    UUID optionB = UUID.fromString(created.path("questions[0].options[1].id"));

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + questionId + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);
    return new PollFixture(pollId, questionId, optionA, optionB);
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
    assertThat(session).as("login mints SP_SESSION").isNotBlank();
    assertThat(xsrf).as("login mints XSRF-TOKEN").isNotBlank();
    return new Session(session, xsrf);
  }

  private record Session(String sessionCookie, String xsrfCookie) {
    io.restassured.specification.RequestSpecification requestWithCsrf() {
      return given()
          .cookie("SP_SESSION", sessionCookie)
          .cookie("XSRF-TOKEN", xsrfCookie)
          .header("X-XSRF-TOKEN", xsrfCookie);
    }
  }

  private record PollFixture(UUID pollId, UUID activeQuestionId, UUID optionAId, UUID optionBId) {}
}
