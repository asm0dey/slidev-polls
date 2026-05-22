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
 * Admin user-management surface ported to {@code @QuarkusTest} + RestAssured. State-changing calls
 * use the real login flow (SP_SESSION + XSRF double-submit), mirroring {@code VoteSubmissionIT}.
 * admin_user is wiped (polls first, for the FK) and re-seeded so list-size assertions are stable.
 */
@QuarkusTest
class AdminUserManagementIT {

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
    dsl.deleteFrom(Tables.ADMIN_USER).execute();
    AdminUserTestFixtures.seedAdmin(dsl, hasher, "alice", "correct-horse-battery");
  }

  @Test
  void anonymousGetReturns401() {
    given()
        .when()
        .get("/api/admin/users")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }

  @Test
  void authenticatedPostCreatesUserAndGetListsBoth() {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{ \"username\": \"bob\", \"password\": \"another-strong-pw\" }")
        .when()
        .post("/api/admin/users")
        .then()
        .statusCode(201)
        .body("username", equalTo("bob"));

    admin
        .request()
        .when()
        .get("/api/admin/users")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("findAll { it.username == 'alice' }.size()", equalTo(1))
        .body("findAll { it.username == 'bob' }.size()", equalTo(1));
  }

  @Test
  void duplicateUsernameReturns409UsernameTaken() {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{ \"username\": \"alice\", \"password\": \"another-strong-pw\" }")
        .when()
        .post("/api/admin/users")
        .then()
        .statusCode(409)
        .body("code", equalTo("USERNAME_TAKEN"));
  }

  private Session loginAsAlice() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"alice\",\"password\":\"correct-horse-battery\"}")
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
    RequestSpecification request() {
      return given().cookie("SP_SESSION", sessionCookie);
    }

    RequestSpecification requestWithCsrf() {
      return given()
          .cookie("SP_SESSION", sessionCookie)
          .cookie("XSRF-TOKEN", xsrfCookie)
          .header("X-XSRF-TOKEN", xsrfCookie);
    }
  }
}
