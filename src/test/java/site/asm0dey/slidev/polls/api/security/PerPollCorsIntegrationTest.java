package site.asm0dey.slidev.polls.api.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
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
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * Integration coverage for per-poll CORS against the real Vert.x {@code PerPollCorsFilter} (the
 * replacement for the deleted Spring {@code PerPollCorsConfigurationSource}). Exercised over
 * RestAssured with {@code Origin} headers and {@code OPTIONS} preflights against the three URI
 * families the filter resolves — {@code /api/polls/{slug}/...} (by slug), {@code
 * /api/deck/polls/{id}/...} (by poll id), and {@code /api/deck/auth/...} (by any-poll origin scan).
 *
 * <p>This file merges the former {@code PerPollCorsConfigurationSourceTest} (which mocked the now-
 * deleted Spring class) into the integration test: every routing branch that the unit test asserted
 * on the resolver is here re-expressed as an observable preflight/header outcome against the live
 * filter, so the unit test was deleted as redundant.
 *
 * <p>Behaviour note: the Vert.x filter answers an {@code OPTIONS} preflight from an
 * <em>unlisted</em> origin with 204 but <em>without</em> the {@code Access-Control-Allow-Origin}
 * header (rather than the old Spring 403). Either way the browser blocks the follow-on request; the
 * test asserts the absence of the allow-origin echo, which is the contract the filter actually
 * enforces.
 *
 * <ul>
 *   <li>@TS-A5-010 — preflight allowed (Allow-Origin + Allow-Credentials) for a listed origin on
 *       the slug/stream family.
 *   <li>@TS-A5-011 — preflight from an unlisted origin gets no Allow-Origin echo.
 *   <li>@TS-A5-012 — deck-activation preflight resolves CORS by pollId.
 *   <li>@TS-A5-013 — deck-login preflight resolves CORS by scanning all polls' allowedOrigins.
 *   <li>Plus: unknown slug / admin paths yield no Allow-Origin header.
 * </ul>
 */
@QuarkusTest
class PerPollCorsIntegrationTest {

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

  // @TS-A5-010 — preflight from a listed origin receives the Allow-Origin echo and
  // Allow-Credentials: true so the browser permits the cross-origin SSE subscription.
  @Test
  void preflightAllowedForListedOrigin() {
    PollFixture poll = createPoll("cors-allowed", "http://localhost:3030");
    given()
        .header("Origin", "http://localhost:3030")
        .header("Access-Control-Request-Method", "GET")
        .when()
        .options("/api/polls/" + poll.slug() + "/stream")
        .then()
        .statusCode(204)
        .header("Access-Control-Allow-Origin", equalTo("http://localhost:3030"))
        .header("Access-Control-Allow-Credentials", equalTo("true"));
  }

  // A non-preflight GET on the slug family from a listed origin also gets the allow-origin echo so
  // the actual SSE / public-view response is readable cross-origin (this is what the unit test's
  // resolvesBySlugForPublicAndStreamRoutes asserted).
  @Test
  void simpleRequestEchoesAllowOriginForListedOrigin() {
    PollFixture poll = createPoll("cors-simple", "http://localhost:3030");
    given()
        .header("Origin", "http://localhost:3030")
        .when()
        .get("/api/polls/" + poll.slug() + "/stream")
        .then()
        .header("Access-Control-Allow-Origin", equalTo("http://localhost:3030"))
        .header("Access-Control-Allow-Credentials", equalTo("true"));
  }

  // @TS-A5-011 — preflight from an unlisted origin gets no Allow-Origin echo, so the browser blocks
  // the follow-on request entirely.
  @Test
  void preflightDeniedForUnlistedOrigin() {
    PollFixture poll = createPoll("cors-denied", "http://localhost:3030");
    given()
        .header("Origin", "https://attacker.example")
        .header("Access-Control-Request-Method", "GET")
        .when()
        .options("/api/polls/" + poll.slug() + "/stream")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
  }

  // @TS-A5-012 — the deck-activation path resolves CORS by pollId; a matching origin is echoed back
  // so the Slidev deck can call activate cross-origin.
  @Test
  void deckActivationPreflightHonoursPollIdAllowlist() {
    PollFixture poll = createPoll("cors-deck-activate", "https://demo.example.com");
    given()
        .header("Origin", "https://demo.example.com")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "X-Deck-Token,Content-Type")
        .when()
        .options("/api/deck/polls/" + poll.pollId() + "/activate")
        .then()
        .statusCode(204)
        .header("Access-Control-Allow-Origin", equalTo("https://demo.example.com"));
  }

  // @TS-A5-013 — the deck-login path resolves CORS by scanning all polls whose allowedOrigins
  // contain the requesting Origin; a poll seeded with that origin unlocks the preflight.
  @Test
  void deckLoginPreflightAllowsAnyPollOrigin() {
    createPoll("cors-deck-login", "http://localhost:3030");
    given()
        .header("Origin", "http://localhost:3030")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "Content-Type")
        .when()
        .options("/api/deck/auth/login")
        .then()
        .statusCode(204)
        .header("Access-Control-Allow-Origin", equalTo("http://localhost:3030"));
  }

  // Deck-login from an origin no poll allows gets no echo (the unit test's
  // deckAuthLoginRejectsOriginNoPollAllows branch).
  @Test
  void deckLoginPreflightRejectsOriginNoPollAllows() {
    createPoll("cors-deck-reject", "http://localhost:3030");
    given()
        .header("Origin", "https://attacker.example")
        .header("Access-Control-Request-Method", "POST")
        .when()
        .options("/api/deck/auth/login")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
  }

  // Unknown slug yields no CORS config → no allow-origin header (unit test's
  // unknownSlugReturnsNoConfig branch).
  @Test
  void unknownSlugYieldsNoCorsHeaders() {
    given()
        .header("Origin", "http://localhost:3030")
        .header("Access-Control-Request-Method", "GET")
        .when()
        .options("/api/polls/ghost-poll/stream")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
  }

  // Admin paths are outside the CORS families → no allow-origin header (unit test's
  // adminPathsReturnNoConfig branch).
  @Test
  void adminPathsYieldNoCorsHeaders() {
    given()
        .header("Origin", "http://localhost:3030")
        .when()
        .get("/api/admin/polls")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
  }

  // ---------- fixtures -------------------------------------------------------

  /** Creates a poll via the admin API carrying the given allowed origin. */
  private PollFixture createPoll(String slug, String allowedOrigin) {
    Session admin = loginAsAlice();
    String body =
        String.format(
            """
            {
              "title": "CORS fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]}
              ],
              "allowedOrigins": ["%s"]
            }
            """,
            slug, allowedOrigin);
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
    String pollSlug = created.path("slug");
    return new PollFixture(pollId, pollSlug);
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

  private record PollFixture(UUID pollId, String slug) {}
}
