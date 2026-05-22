package site.asm0dey.slidev.polls.api.pub;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
 * End-to-end coverage for the voter write path — {@code POST /api/polls/{slug}/votes} — ported to
 * {@code @QuarkusTest} + RestAssured. Fixtures are seeded via the real admin flow (login → create →
 * activate) so the test exercises the full stack including the SP_SESSION + XSRF double-submit
 * CSRF.
 *
 * <ul>
 *   <li>@TS-022 — valid vote is accepted with 201, body carries voteId + recordedAt, a row lands.
 *   <li>@TS-023 — duplicate submission from the same {@code sp_voter} cookie → 409 ALREADY_VOTED.
 *   <li>@TS-025 — vote after the active question closes → 409 QUESTION_NOT_ACTIVE, user-facing
 *       copy.
 *   <li>@TS-027/@TS-046 — unknown body fields are dropped by Jackson and never persisted.
 *   <li>unknown slug with a valid body → 404 NOT_FOUND with a Problem envelope.
 * </ul>
 */
@QuarkusTest
class VoteSubmissionIT {

  @Inject VoteRepository voteRepository;
  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher hasher;

  @BeforeAll
  static void noCharsetOnJson() {
    // Quarkus REST's JSON body reader rejects "application/json; charset=..." on resources with no
    // explicit @Consumes, yielding a bare empty-body 400. RestAssured appends the charset by
    // default; disable that so the Content-Type stays "application/json".
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

  // @TS-022 — happy path.
  @Test
  void anonymous_visitor_submits_valid_vote_and_is_acknowledged() {
    PollFixture poll = createPollWithActiveQuestion("vote-happy");

    Response seen =
        given()
            .when()
            .get("/api/polls/by-slug/vote-happy")
            .then()
            .statusCode(200)
            .extract()
            .response();
    String voterToken = seen.getCookie("sp_voter");
    assertThat(voterToken).isNotBlank();

    String body =
        String.format("{\"optionIds\":[\"%s\"],\"voterToken\":\"ignored\"}", poll.optionAId());
    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/polls/vote-happy/votes")
        .then()
        .statusCode(201)
        .body("voteId", notNullValue())
        .body("recordedAt", notNullValue());

    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), voterToken))
        .as("votes row present after successful POST")
        .isTrue();
  }

  // @TS-023 — duplicate submission from the same sp_voter cookie → 409 ALREADY_VOTED.
  @Test
  void duplicate_submission_is_rejected_as_already_voted() {
    PollFixture poll = createPollWithActiveQuestion("vote-dup");
    String voterToken =
        given()
            .when()
            .get("/api/polls/by-slug/vote-dup")
            .then()
            .statusCode(200)
            .extract()
            .cookie("sp_voter");

    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId()))
        .when()
        .post("/api/polls/vote-dup/votes")
        .then()
        .statusCode(201);

    // Second submit with the same cookie — the (question_id, voter_token) unique index refuses it.
    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionBId()))
        .when()
        .post("/api/polls/vote-dup/votes")
        .then()
        .statusCode(409)
        .body("code", equalTo("ALREADY_VOTED"));

    long rows =
        voteRepository.tally(poll.activeQuestionId()).values().stream()
            .mapToLong(Long::longValue)
            .sum();
    assertThat(rows).as("exactly one recorded vote after a duplicate submit").isEqualTo(1L);
  }

  // @TS-025 — vote after the presenter closed the active question → 409 QUESTION_NOT_ACTIVE.
  @Test
  void vote_after_close_is_rejected_with_non_technical_copy() {
    PollFixture poll = createPollWithActiveQuestion("vote-closed");
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .when()
        .post("/api/admin/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200);

    String voterToken =
        given()
            .when()
            .get("/api/polls/by-slug/vote-closed")
            .then()
            .statusCode(200)
            .extract()
            .cookie("sp_voter");

    String message =
        given()
            .cookie("sp_voter", voterToken)
            .contentType(ContentType.JSON)
            .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId()))
            .when()
            .post("/api/polls/vote-closed/votes")
            .then()
            .statusCode(409)
            .body("code", equalTo("QUESTION_NOT_ACTIVE"))
            .extract()
            .path("message");

    assertThat(message.toLowerCase()).doesNotContain("sqlexception");
    assertThat(message.toLowerCase()).doesNotContain("stacktrace");
    assertThat(message.toLowerCase()).doesNotContain("postgres");
    assertThat(message).isNotBlank();
  }

  // @TS-027 / @TS-046 — unknown top-level fields (email, name) are dropped by Jackson, never
  // stored.
  @Test
  void unknown_body_fields_are_ignored_and_not_persisted() {
    PollFixture poll = createPollWithActiveQuestion("vote-extra");
    String voterToken =
        given()
            .when()
            .get("/api/polls/by-slug/vote-extra")
            .then()
            .statusCode(200)
            .extract()
            .cookie("sp_voter");

    String body =
        String.format(
            "{\"optionIds\":[\"%s\"],\"email\":\"alice@example.com\",\"name\":\"Alice\"}",
            poll.optionAId());
    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/polls/vote-extra/votes")
        .then()
        .statusCode(201);

    assertThat(voteRepository.alreadyVoted(poll.activeQuestionId(), voterToken)).isTrue();
  }

  // Vote on an unknown slug with a VALID body → 404 NOT_FOUND with a Problem envelope. (The bare
  // 400 a boot smoke saw came from an empty/invalid body failing @NotNull bean-validation BEFORE
  // the slug lookup; with a well-formed body the domain NotFoundException surfaces as 404.)
  @Test
  void vote_on_unknown_slug_with_valid_body_returns_404_not_found() {
    given()
        .contentType(ContentType.JSON)
        .body(String.format("{\"optionIds\":[\"%s\"]}", UUID.randomUUID()))
        .when()
        .post("/api/polls/no-such-poll/votes")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  // ---------- fixtures -----------------------------------------------------

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

  /**
   * Captured admin cookies; {@link #requestWithCsrf} replays them with the double-submit header.
   */
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
