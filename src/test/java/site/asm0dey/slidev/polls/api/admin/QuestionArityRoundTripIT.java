package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
import site.asm0dey.slidev.polls.persistence.jooq.Tables;

/**
 * Round-trip coverage for per-question arity on the admin DTOs, ported to {@code @QuarkusTest} +
 * RestAssured. The request accepts {@code minSelections}/{@code maxSelections} (defaulting to
 * {@code (1, 1)} when omitted) and the response exposes them alongside a zero {@code voteCount}.
 */
@QuarkusTest
class QuestionArityRoundTripIT {

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
    dsl.deleteFrom(Tables.POLLS).execute();
    AdminUserTestFixtures.ensureAdmin(dsl, hasher, "alice", "correct-horse");
  }

  @Test
  void createPersistsAndReturnsArityAndZeroVoteCount() {
    Session admin = loginAsAlice();
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
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201)
        .body("questions[0].minSelections", equalTo(1))
        .body("questions[0].maxSelections", equalTo(1))
        .body("questions[0].voteCount", equalTo(0))
        .body("questions[1].minSelections", equalTo(0))
        .body("questions[1].maxSelections", equalTo(3))
        .body("questions[1].voteCount", equalTo(0));
  }

  @Test
  void omittingArityDefaultsToOneOne() {
    Session admin = loginAsAlice();
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
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201)
        .body("questions[0].minSelections", equalTo(1))
        .body("questions[0].maxSelections", equalTo(1));
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

  private record Session(String sessionCookie, String xsrfCookie) {
    RequestSpecification requestWithCsrf() {
      return given()
          .cookie("SP_SESSION", sessionCookie)
          .cookie("XSRF-TOKEN", xsrfCookie)
          .header("X-XSRF-TOKEN", xsrfCookie);
    }
  }
}
