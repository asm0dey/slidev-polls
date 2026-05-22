package site.asm0dey.slidev.polls.api.pub;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

/**
 * Anonymous read surface for the voter SPA — {@code GET /api/polls/by-slug/{slug}}. Ported to
 * {@code @QuarkusTest} + RestAssured; the authoring flow (login → create → open) runs through the
 * real admin endpoints so the fixture mirrors what a presenter sets up, and the voter-facing
 * assertions are made over fresh requests with no session attached.
 *
 * <ul>
 *   <li>@TS-020 — anonymous visitor opens a poll with an ACTIVE question: 200, state=ACTIVE, active
 *       question + options present, no auth challenge, no PII fields.
 *   <li>@TS-021 — a poll whose questions are all DRAFT: state=WAITING, activeQuestion absent.
 *   <li>@TS-045 — slug in an invalid format (upper-case): 404 NOT_FOUND.
 *   <li>@TS-046 — the first-touch response sets an {@code sp_voter} cookie that is HttpOnly,
 *       SameSite=Lax, and whose value is a bare UUID.
 * </ul>
 */
@QuarkusTest
class PublicPollViewIT {

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

  // @TS-020 — anonymous GET on a poll with an ACTIVE question returns the live question inline with
  // state=ACTIVE; no auth required and no PII fields leak.
  @Test
  void anonymous_visitor_sees_active_question_and_options() {
    PollFixture poll = createPollWithActiveQuestion("my-talk");

    given()
        .when()
        .get("/api/polls/by-slug/my-talk")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .header("WWW-Authenticate", nullValue())
        .body("pollId", equalTo(poll.pollId().toString()))
        .body("slug", equalTo("my-talk"))
        .body("title", notNullValue())
        .body("state", equalTo("ACTIVE"))
        .body("activeQuestion.id", equalTo(poll.activeQuestionId().toString()))
        .body("activeQuestion.options.size()", equalTo(2))
        // @TS-020 / @TS-046 — no PII fields.
        .body("owner", nullValue())
        .body("ownerUsername", nullValue())
        .body("email", nullValue())
        .body("name", nullValue());
  }

  // @TS-046 — first-touch anonymous GET sets an sp_voter cookie that is HttpOnly, SameSite=Lax,
  // with a UUID-shaped value (no PII by construction).
  @Test
  void first_anonymous_visit_sets_sp_voter_cookie() {
    createPollWithActiveQuestion("cookie-talk");

    Response response =
        given()
            .when()
            .get("/api/polls/by-slug/cookie-talk")
            .then()
            .statusCode(200)
            .extract()
            .response();

    var cookie = response.getDetailedCookie("sp_voter");
    assertThat(cookie).as("sp_voter cookie on first-touch GET").isNotNull();
    assertThat(cookie.isHttpOnly()).as("sp_voter is HttpOnly").isTrue();
    assertThat(cookie.getSameSite()).as("sp_voter is SameSite=Lax").isEqualToIgnoringCase("Lax");
    // @TS-046 — the value is a bare UUID; no device fingerprint, no personal-shaped data.
    assertThat(UUID.fromString(cookie.getValue())).isNotNull();
  }

  // @TS-021 — a poll with no active question returns state=WAITING without an activeQuestion field.
  @Test
  void anonymous_visitor_sees_waiting_state_when_no_question_is_active() {
    createPollWithDraftQuestion("waiting-talk");

    given()
        .when()
        .get("/api/polls/by-slug/waiting-talk")
        .then()
        .statusCode(200)
        .body("state", equalTo("WAITING"))
        .body("activeQuestion", nullValue());
  }

  // @TS-045 — a slug whose format does not match kebab-case is rejected with NOT_FOUND.
  @Test
  void invalid_slug_format_returns_not_found() {
    given()
        .when()
        .get("/api/polls/by-slug/UPPER")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  // Companion: a well-formed slug that resolves to no poll is also 404 with the same code.
  @Test
  void unknown_slug_returns_not_found() {
    given()
        .when()
        .get("/api/polls/by-slug/nope-not-here")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  // Per-question snapshot endpoint serves a CLOSED question's tally + options outside the SSE
  // stream (which only carries the active question).
  @Test
  void question_snapshot_returns_prompt_and_options_for_closed_question() {
    PollFixture poll = createPollWithActiveQuestion("hist-talk");
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .when()
        .post("/api/admin/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/api/polls/hist-talk/questions/" + poll.activeQuestionId() + "/snapshot")
        .then()
        .statusCode(200)
        .body("pollId", equalTo(poll.pollId().toString()))
        .body("slug", equalTo("hist-talk"))
        .body("activeQuestion.id", equalTo(poll.activeQuestionId().toString()))
        .body("activeQuestion.options.size()", equalTo(2))
        .body("tally.size()", equalTo(2));
  }

  @Test
  void question_snapshot_returns_not_found_for_unknown_question() {
    createPollWithActiveQuestion("hist-unknown");
    UUID phantom = UUID.randomUUID();
    given()
        .when()
        .get("/api/polls/hist-unknown/questions/" + phantom + "/snapshot")
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
              "title": "Public fixture %s",
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

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + questionId + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);
    return new PollFixture(pollId, questionId);
  }

  private void createPollWithDraftQuestion(String slug) {
    Session admin = loginAsAlice();
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
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201);
    // Intentionally skip /open — the question stays DRAFT, so the voter sees WAITING.
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

  private record PollFixture(UUID pollId, UUID activeQuestionId) {}
}
