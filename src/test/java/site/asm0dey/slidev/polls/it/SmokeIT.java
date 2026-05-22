package site.asm0dey.slidev.polls.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Black-box end-to-end smoke against the BUILT artifact (runnable jar; native image under {@code
 * -Dnative}). Unlike the {@code @QuarkusTest} ITs this launches the packaged app as a real process
 * and drives it purely over HTTP — no CDI, no {@code @Inject}, no DB injection. The whole fixture
 * is self-seeded through the public/admin HTTP API.
 *
 * <p>The artifact launches in {@code prod} mode, so two things are forced via {@link
 * SmokeProfile#getConfigOverrides()} (Quarkus forwards a {@code @TestProfile}'s overrides to the
 * launched IT process as system properties):
 *
 * <ul>
 *   <li>{@code app.database.vendor=h2} — runs entirely on the in-memory H2 vendor (its {@code mem}
 *       url is unprofiled so it works in prod), exercising the H2 runtime path including the native
 *       H2 trigger and {@code FlywayMigrator}'s H2 baseline. No external Postgres needed.
 *   <li>{@code quarkus.http.auth.session.encryption-key} — a real 32+ char key, otherwise {@code
 *       SessionKeyGuard} aborts the prod launch because the baked-in placeholder is still in place.
 * </ul>
 *
 * <p>The single ordered test walks setup → login → author a poll → activate → public read → vote →
 * QR PNG, then two negative auth checks. The QR step (step 8) is the key native check: it drives
 * the quarkus-zxing / AWT-ImageIO PNG path through the native image.
 *
 * <p>Gated behind {@code @Tag("smoke")}; excluded from the default {@code verify} run and included
 * only via the {@code smoke} (or {@code native}) Maven profile.
 */
@QuarkusIntegrationTest
@TestProfile(SmokeIT.SmokeProfile.class)
@Tag("smoke")
class SmokeIT {

  public static class SmokeProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          // Drive the in-memory H2 vendor so no external Postgres is needed and the H2 runtime
          // path (incl. the native H2 trigger) is exercised. The H2 mem url is unprofiled, so it
          // resolves under prod too.
          "app.database.vendor",
          "h2",
          // A real key so SessionKeyGuard does not abort the prod launch on the placeholder.
          "quarkus.http.auth.session.encryption-key",
          "smoke-it-session-key-0123456789-abcdef");
    }
  }

  @BeforeAll
  static void noCharsetOnJson() {
    // Quarkus REST's JSON reader rejects "application/json; charset=..." on resources without an
    // explicit @Consumes; RestAssured appends the charset by default, so disable that.
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  // One ordered walk so setup → login → author → vote → QR share the same launched process and
  // run in sequence (a fresh H2 mem DB is empty, so setup must precede everything).
  @Test
  void full_round_trip_over_http_against_the_built_artifact() throws Exception {
    String username = "smoke-admin";
    String password = "correct-horse-battery-staple";

    // 1. Fresh H2 mem DB → setup is required.
    given()
        .when()
        .get("/api/admin/setup/status")
        .then()
        .statusCode(200)
        .body("setupRequired", equalTo(true));

    // 2. Create the first admin (public, no session/CSRF) → 201.
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when()
        .post("/api/admin/setup")
        .then()
        .statusCode(201)
        .body("username", equalTo(username));

    // 3. Login → 204; capture the SP_SESSION + XSRF-TOKEN cookies for the double-submit CSRF.
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
            .post("/api/admin/login")
            .then()
            .statusCode(204)
            .extract()
            .response();
    String session = login.getCookie("SP_SESSION");
    String xsrf = login.getCookie("XSRF-TOKEN");
    org.junit.jupiter.api.Assertions.assertNotNull(session, "login mints SP_SESSION");
    org.junit.jupiter.api.Assertions.assertNotNull(xsrf, "login mints XSRF-TOKEN");

    // 4. Author a poll (session cookie + X-XSRF-TOKEN header) → 201; capture id/slug/question.
    String slug = "smoke-poll";
    Response created =
        given()
            .cookie("SP_SESSION", session)
            .cookie("XSRF-TOKEN", xsrf)
            .header("X-XSRF-TOKEN", xsrf)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title": "Smoke poll",
                  "slug": "%s",
                  "questions": [
                    { "prompt": "Which JVM?", "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] }
                  ]
                }
                """
                    .formatted(slug))
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .body("slug", equalTo(slug))
            .extract()
            .response();

    String pollId = created.path("id");
    String questionId = created.path("questions[0].id");
    String optionAId = created.path("questions[0].options[0].id");
    org.junit.jupiter.api.Assertions.assertNotNull(pollId);

    // 5. Activate the question (admin open) → 200.
    given()
        .cookie("SP_SESSION", session)
        .cookie("XSRF-TOKEN", xsrf)
        .header("X-XSRF-TOKEN", xsrf)
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + questionId + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);

    // 6. Public view shows the active question; capture the sp_voter cookie for the vote.
    Response publicView =
        given()
            .when()
            .get("/api/polls/by-slug/" + slug)
            .then()
            .statusCode(200)
            .body("slug", equalTo(slug))
            .body("state", equalTo("ACTIVE"))
            .body("activeQuestion.id", equalTo(questionId))
            .extract()
            .response();
    String voterToken = publicView.getCookie("sp_voter");
    org.junit.jupiter.api.Assertions.assertNotNull(voterToken, "first-touch GET mints sp_voter");

    // 7. Cast a vote → 201.
    given()
        .cookie("sp_voter", voterToken)
        .contentType(ContentType.JSON)
        .body("{\"optionIds\":[\"" + optionAId + "\"]}")
        .when()
        .post("/api/polls/" + slug + "/votes")
        .then()
        .statusCode(201)
        .body("voteId", notNullValue());

    // 8. KEY NATIVE CHECK — the QR PNG (quarkus-zxing / AWT-ImageIO path) renders in the artifact.
    byte[] qr =
        given()
            .cookie("SP_SESSION", session)
            .when()
            .get("/api/admin/polls/" + pollId + "/qr.png")
            .then()
            .statusCode(200)
            .header("Content-Type", "image/png")
            .extract()
            .asByteArray();
    org.junit.jupiter.api.Assertions.assertTrue(qr.length > 0, "QR PNG body is non-empty");

    // 9a. Negative: admin listing with no session → 401 AUTH_REQUIRED (Problem envelope).
    given()
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));

    // 9b. Negative: a deck route with no token → 401 DECK_TOKEN_INVALID (Problem envelope).
    given()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + UUID.randomUUID() + "\"}")
        .when()
        .post("/api/deck/polls/" + pollId + "/activate")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }
}
