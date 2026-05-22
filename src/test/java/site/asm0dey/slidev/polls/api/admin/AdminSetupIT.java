package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.persistence.jooq.Tables;

/**
 * First-run setup chain ported to {@code @QuarkusTest} + RestAssured. {@code POST /api/admin/setup}
 * and {@code GET /api/admin/setup/status} are public (no session, no CSRF). This IT exercises the
 * empty-DB → setup → locked → 409 chain, so it wipes admin_user (and polls first, for the FK)
 * before each test to establish the {@code setupRequired=true} precondition.
 */
@QuarkusTest
class AdminSetupIT {

  @Inject DSLContext dsl;

  @BeforeAll
  static void noCharsetOnJson() {
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  @BeforeEach
  void wipeAdminUser() {
    dsl.deleteFrom(Tables.POLLS).execute();
    dsl.deleteFrom(Tables.ADMIN_USER).execute();
  }

  @Test
  void emptyDbReportsSetupRequiredTrueThenRunsSetupThenLocks() {
    given()
        .when()
        .get("/api/admin/setup/status")
        .then()
        .statusCode(200)
        .body("setupRequired", equalTo(true));

    String body = "{ \"username\": \"alice\", \"password\": \"correct-horse-battery\" }";
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/setup")
        .then()
        .statusCode(201)
        .body("username", equalTo("alice"));

    given()
        .when()
        .get("/api/admin/setup/status")
        .then()
        .statusCode(200)
        .body("setupRequired", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/setup")
        .then()
        .statusCode(409)
        .body("code", equalTo("SETUP_LOCKED"));
  }

  @Test
  void rejectsShortPasswordWithValidationFailed() {
    String body = "{ \"username\": \"alice\", \"password\": \"short\" }";
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/setup")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }
}
