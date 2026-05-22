package site.asm0dey.slidev.polls.api.deck;

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
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * Deck-auth self-inspection ({@code /me}) and sign-in ({@code /login}) ported to
 * {@code @QuarkusTest} + RestAssured.
 *
 * <ul>
 *   <li>@TS-107 — valid deck token → 200 with tokenId, pollId, label.
 *   <li>@TS-108 — missing header → 401 DECK_TOKEN_INVALID.
 *   <li>@TS-109 — unknown bearer → 401 DECK_TOKEN_INVALID.
 *   <li>revoked bearer → 401 DECK_TOKEN_INVALID.
 *   <li>@BUG-002 — username/password login mints a usable deck token; bad credential → 401.
 * </ul>
 */
@QuarkusTest
class DeckAuthControllerTest {

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

  // @TS-107 — valid deck token returns the token's scope.
  @Test
  void returns200WithScope_whenDeckTokenValid() {
    PollFixture poll = createPoll("deck-auth-valid");
    Response minted = mintTokenRaw(poll, "Laptop");
    String plaintext = minted.path("plaintext");
    UUID tokenId = UUID.fromString(minted.path("id"));

    given()
        .header("X-Deck-Token", plaintext)
        .when()
        .get("/api/deck/auth/me")
        .then()
        .statusCode(200)
        .body("tokenId", equalTo(tokenId.toString()))
        .body("pollId", equalTo(poll.pollId().toString()))
        .body("label", equalTo("Laptop"));
  }

  // @TS-108 — missing header → 401 DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenHeaderMissing() {
    given()
        .when()
        .get("/api/deck/auth/me")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // @TS-109 — unknown bearer → 401 DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenBearerUnknown() {
    given()
        .header("X-Deck-Token", "bogus-plaintext-unknown")
        .when()
        .get("/api/deck/auth/me")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // Revoked row → 401 DECK_TOKEN_INVALID.
  @Test
  void returns401DeckTokenInvalid_whenBearerRevoked() {
    PollFixture poll = createPoll("deck-auth-revoked");
    Response minted = mintTokenRaw(poll, null);
    UUID tokenId = UUID.fromString(minted.path("id"));
    String plaintext = minted.path("plaintext");

    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .when()
        .delete("/api/admin/polls/" + poll.pollId() + "/deck-tokens/" + tokenId)
        .then()
        .statusCode(204);

    given()
        .header("X-Deck-Token", plaintext)
        .when()
        .get("/api/deck/auth/me")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // @BUG-002 — username/password login mints a usable deck token.
  @Test
  void returns200WithMintedToken_whenCredentialsValid() {
    PollFixture poll = createPoll("deck-login-ok");

    String plaintext =
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"alice\",\"password\":\"correct-horse\"}")
            .when()
            .post("/api/deck/auth/login")
            .then()
            .statusCode(200)
            .body("pollId", equalTo(poll.pollId().toString()))
            .body("label", equalTo("deck"))
            .body("token", instanceOf(String.class))
            .body("tokenId", instanceOf(String.class))
            .extract()
            .path("token");

    given()
        .header("X-Deck-Token", plaintext)
        .when()
        .get("/api/deck/auth/me")
        .then()
        .statusCode(200)
        .body("pollId", equalTo(poll.pollId().toString()));
  }

  // @BUG-002 — invalid credentials → 401. The frontend maps any 401 to FR-014 "credential not
  // recognised", so the status is the contract. Under Quarkus, SecurityProblemMappers does a
  // path-based split: an AuthenticationFailedException on a /api/deck/ route yields the
  // DECK_TOKEN_INVALID code (the Spring port asserted AUTH_REQUIRED, which was the path-agnostic
  // entry-point code; the Quarkus mapper deliberately scopes it to the deck surface).
  @Test
  void returns401_whenCredentialsInvalid() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"alice\",\"password\":\"wrong\"}")
        .when()
        .post("/api/deck/auth/login")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // ---------- fixtures -----------------------------------------------------

  private Response mintTokenRaw(PollFixture poll, String label) {
    Session admin = loginAsAlice();
    String body = label == null ? "{}" : "{\"label\":\"" + label + "\"}";
    return admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
        .then()
        .statusCode(201)
        .extract()
        .response();
  }

  private PollFixture createPoll(String slug) {
    Session admin = loginAsAlice();
    String body =
        String.format(
            """
            {
              "title": "Deck auth fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]}
              ]
            }
            """,
            slug);
    Response created =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .response();
    UUID pollId = UUID.fromString(created.path("id"));
    UUID q1 = UUID.fromString(created.path("questions[0].id"));
    return new PollFixture(pollId, q1);
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

  private record PollFixture(UUID pollId, UUID q1) {}
}
