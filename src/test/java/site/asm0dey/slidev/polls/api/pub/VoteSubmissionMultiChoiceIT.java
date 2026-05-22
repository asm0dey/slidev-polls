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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * End-to-end coverage for the multi-choice ballot shape on {@code POST /api/polls/{slug}/votes},
 * ported to {@code @QuarkusTest} + RestAssured. The voter SPA carries {@code optionIds: UUID[]};
 * the four boundary cases:
 *
 * <ul>
 *   <li>a multi-option ballot is accepted within arity bounds (201),
 *   <li>an abstention is accepted when {@code minSelections == 0} (201),
 *   <li>a multi-option payload aimed at a single-choice question is rejected as 400 {@code
 *       VALIDATION_FAILED},
 *   <li>a legacy {@code optionId} payload is rejected because Jackson drops the unknown field and
 *       the {@code @NotNull} on {@code optionIds} fires (400).
 * </ul>
 */
@QuarkusTest
class VoteSubmissionMultiChoiceIT {

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

  // Three options, min=1, max=3 → "pick anywhere from 1 to 3". Two options on the ballot is the
  // multi-choice case.
  @Test
  void multiOptionBallotAccepted() {
    PollFixture p = seedActivePoll("multi-1", 3, 1, 3);
    String cookie = visitorCookie("multi-1");
    String body = "{\"optionIds\":[\"" + p.options().get(0) + "\",\"" + p.options().get(2) + "\"]}";

    given()
        .cookie("sp_voter", cookie)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/polls/multi-1/votes")
        .then()
        .statusCode(201);
  }

  // min=0 lets the voter submit nothing — an explicit "no opinion". The empty array round-trips to
  // 201.
  @Test
  void abstainAcceptedWhenMinZero() {
    seedActivePoll("abstain-1", 3, 0, 3);
    String cookie = visitorCookie("abstain-1");

    given()
        .cookie("sp_voter", cookie)
        .contentType(ContentType.JSON)
        .body("{\"optionIds\":[]}")
        .when()
        .post("/api/polls/abstain-1/votes")
        .then()
        .statusCode(201);
  }

  // Single-choice question = (min=1, max=1). A two-element payload violates the arity bound and
  // VoteService throws IllegalArgumentException → 400 VALIDATION_FAILED.
  @Test
  void singleChoiceRejectsMultiOption() {
    PollFixture p = seedActivePoll("single-1", 3, 1, 1);
    String cookie = visitorCookie("single-1");
    String body = "{\"optionIds\":[\"" + p.options().get(0) + "\",\"" + p.options().get(1) + "\"]}";

    given()
        .cookie("sp_voter", cookie)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/polls/single-1/votes")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }

  // Legacy clients sending {"optionId": "..."} get a 400: Jackson drops the unknown property
  // leaving optionIds null, which trips the @NotNull on the request DTO.
  @Test
  void legacyOptionIdRejected() {
    seedActivePoll("legacy-1", 2, 1, 1);
    String cookie = visitorCookie("legacy-1");

    given()
        .cookie("sp_voter", cookie)
        .contentType(ContentType.JSON)
        .body("{\"optionId\":\"" + UUID.randomUUID() + "\"}")
        .when()
        .post("/api/polls/legacy-1/votes")
        .then()
        .statusCode(400);
  }

  // ---------- fixtures -----------------------------------------------------

  private PollFixture seedActivePoll(String slug, int optionCount, int min, int max) {
    Session admin = loginAsAlice();
    StringBuilder options = new StringBuilder();
    for (int i = 0; i < optionCount; i++) {
      if (i > 0) {
        options.append(",");
      }
      options.append("{ \"label\": \"opt-").append(i).append("\" }");
    }
    String createBody =
        String.format(
            """
            {
              "title": "Fixture %s",
              "slug": "%s",
              "questions": [
                {
                  "prompt": "Pick %d (min=%d max=%d)",
                  "minSelections": %d,
                  "maxSelections": %d,
                  "options": [ %s ]
                }
              ]
            }
            """,
            slug, slug, optionCount, min, max, min, max, options);
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
    List<UUID> optionIds = new ArrayList<>();
    for (int i = 0; i < optionCount; i++) {
      optionIds.add(UUID.fromString(created.path("questions[0].options[" + i + "].id")));
    }

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + questionId + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);
    return new PollFixture(pollId, questionId, List.copyOf(optionIds));
  }

  private String visitorCookie(String slug) {
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

  private record PollFixture(UUID pollId, UUID activeQuestionId, List<UUID> options) {}
}
