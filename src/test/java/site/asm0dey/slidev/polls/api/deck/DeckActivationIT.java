package site.asm0dey.slidev.polls.api.deck;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

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
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.service.PollRepository;

/**
 * End-to-end coverage for the deck-token mint / activate surface, ported to {@code @QuarkusTest} +
 * RestAssured. Deck calls authenticate via {@code X-Deck-Token}; admin mint/revoke uses the real
 * login + XSRF double-submit flow.
 */
@QuarkusTest
class DeckActivationIT {

  @Inject PollRepository pollRepository;
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

  // @TS-050 — mounting activates the declared question.
  @Test
  void mounting_activates_the_declared_question() {
    PollFixture poll = createPoll("deck-mount");
    String plaintext = mintToken(poll);

    given()
        .header("X-Deck-Token", plaintext)
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + poll.q1() + "\"}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/activate")
        .then()
        .statusCode(200)
        .body("activeQuestionId", equalTo(poll.q1().toString()));

    assertThat(pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId())
        .isEqualTo(poll.q1());
  }

  // @TS-051 — navigating the deck swaps the active question.
  @Test
  void navigation_swaps_active_question() {
    PollFixture poll = createPoll("deck-nav");
    String plaintext = mintToken(poll);

    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    activateViaDeck(poll.pollId(), plaintext, poll.q2());

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isEqualTo(poll.q2());
    var q1 =
        after.questions().stream().filter(q -> q.id().equals(poll.q1())).findFirst().orElseThrow();
    assertThat(q1.status()).isEqualTo(QuestionStatus.CLOSED);
  }

  // @TS-052 — remounting the already-active slide is idempotent (activated_at stable).
  @Test
  void remount_of_active_question_is_idempotent() {
    PollFixture poll = createPoll("deck-idem");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var firstActivatedAt =
        pollRepository.findById(poll.pollId()).orElseThrow().questions().stream()
            .filter(q -> q.id().equals(poll.q1()))
            .findFirst()
            .orElseThrow()
            .activatedAt();

    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var afterSecond =
        pollRepository.findById(poll.pollId()).orElseThrow().questions().stream()
            .filter(q -> q.id().equals(poll.q1()))
            .findFirst()
            .orElseThrow()
            .activatedAt();
    assertThat(afterSecond).isEqualTo(firstActivatedAt);
  }

  // @TS-053 — anonymous caller cannot activate.
  @Test
  void anonymous_caller_is_rejected_with_deck_token_invalid() {
    PollFixture poll = createPoll("deck-anon");
    given()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + poll.q1() + "\"}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/activate")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // @TS-124 — anonymous activate → 401 DECK_TOKEN_INVALID, pointer unchanged.
  @Test
  void anonymousPostReturns401DeckTokenInvalid() {
    PollFixture poll = createPoll("deck-ts124");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());
    var before = pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId();

    given()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + poll.q2() + "\"}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/activate")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));

    var after = pollRepository.findById(poll.pollId()).orElseThrow().activeQuestionId();
    assertThat(after).isEqualTo(before);
  }

  // @TS-054 — cross-poll token rejected.
  @Test
  void cross_poll_token_is_rejected_with_poll_mismatch() {
    PollFixture pollA = createPoll("deck-cross-a");
    PollFixture pollB = createPoll("deck-cross-b");
    String plaintextForA = mintToken(pollA);

    given()
        .header("X-Deck-Token", plaintextForA)
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + pollB.q1() + "\"}")
        .when()
        .post("/api/deck/polls/" + pollB.pollId() + "/activate")
        .then()
        .statusCode(403)
        .body("code", equalTo("DECK_TOKEN_POLL_MISMATCH"));
  }

  // @TS-055 — revoked token rejected.
  @Test
  void revoked_token_is_rejected_with_deck_token_invalid() {
    PollFixture poll = createPoll("deck-revoked");
    Response minted = mintTokenRaw(poll);
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
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + poll.q1() + "\"}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/activate")
        .then()
        .statusCode(401)
        .body("code", equalTo("DECK_TOKEN_INVALID"));
  }

  // @TS-056 — minted plaintext appears exactly once; list never returns it.
  @Test
  void minted_plaintext_visible_exactly_once() {
    PollFixture poll = createPoll("deck-mint-plain");
    Session admin = loginAsAlice();
    String plaintext =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body("{\"label\":\"laptop\"}")
            .when()
            .post("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
            .then()
            .statusCode(201)
            .extract()
            .path("plaintext");
    assertThat(plaintext).as("plaintext surfaced on mint").isNotBlank();

    Response listing =
        admin
            .request()
            .when()
            .get("/api/admin/polls/" + poll.pollId() + "/deck-tokens")
            .then()
            .statusCode(200)
            .extract()
            .response();
    java.util.List<java.util.Map<String, Object>> rows = listing.jsonPath().getList("$");
    for (var row : rows) {
      assertThat(row.containsKey("plaintext"))
          .as("list rows must not carry plaintext (@TS-056)")
          .isFalse();
    }
  }

  // @TS-057 — deck token must not unlock any other admin surface.
  @Test
  void deck_token_on_admin_endpoint_is_rejected_with_auth_required() {
    PollFixture poll = createPoll("deck-scope");
    String plaintext = mintToken(poll);

    given()
        .header("X-Deck-Token", plaintext)
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }

  // @TS-060 — close after activation sets question CLOSED, null activeQuestionId.
  @Test
  void close_after_activate_sets_question_closed() {
    PollFixture poll = createPoll("deck-close-happy");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q1());

    given()
        .header("X-Deck-Token", plaintext)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200)
        .body("pollId", equalTo(poll.pollId().toString()))
        .body("activeQuestionId", nullValue());

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isNull();
    var q1 =
        after.questions().stream().filter(q -> q.id().equals(poll.q1())).findFirst().orElseThrow();
    assertThat(q1.status()).isEqualTo(QuestionStatus.CLOSED);
  }

  // @TS-061 — close when nothing active is idempotent.
  @Test
  void close_when_nothing_active_is_idempotent() {
    PollFixture poll = createPoll("deck-close-idem");
    String plaintext = mintToken(poll);

    given()
        .header("X-Deck-Token", plaintext)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200)
        .body("activeQuestionId", nullValue());
  }

  // Close scoped to a stale questionId no-ops when a different question is active.
  @Test
  void close_with_mismatched_question_id_is_no_op() {
    PollFixture poll = createPoll("deck-close-scoped");
    String plaintext = mintToken(poll);
    activateViaDeck(poll.pollId(), plaintext, poll.q2());

    given()
        .header("X-Deck-Token", plaintext)
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + poll.q1() + "\"}")
        .when()
        .post("/api/deck/polls/" + poll.pollId() + "/close")
        .then()
        .statusCode(200)
        .body("activeQuestionId", equalTo(poll.q2().toString()));

    var after = pollRepository.findById(poll.pollId()).orElseThrow();
    assertThat(after.activeQuestionId()).isEqualTo(poll.q2());
  }

  // @TS-062 — cross-poll token rejected on close.
  @Test
  void cross_poll_token_rejected_on_close() {
    PollFixture pollA = createPoll("deck-close-cross-a");
    PollFixture pollB = createPoll("deck-close-cross-b");
    String plaintextForA = mintToken(pollA);

    given()
        .header("X-Deck-Token", plaintextForA)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/deck/polls/" + pollB.pollId() + "/close")
        .then()
        .statusCode(403)
        .body("code", equalTo("DECK_TOKEN_POLL_MISMATCH"));
  }

  // ---------- fixtures -----------------------------------------------------

  private void activateViaDeck(UUID pollId, String plaintext, UUID questionId) {
    given()
        .header("X-Deck-Token", plaintext)
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + questionId + "\"}")
        .when()
        .post("/api/deck/polls/" + pollId + "/activate")
        .then()
        .statusCode(200);
  }

  private String mintToken(PollFixture poll) {
    return mintTokenRaw(poll).path("plaintext");
  }

  private Response mintTokenRaw(PollFixture poll) {
    Session admin = loginAsAlice();
    return admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{}")
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
              "title": "Deck fixture",
              "slug": "%s",
              "questions": [
                {"prompt":"Q1?","options":[{"label":"A"},{"label":"B"}]},
                {"prompt":"Q2?","options":[{"label":"X"},{"label":"Y"}]}
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
    UUID q2 = UUID.fromString(created.path("questions[1].id"));
    return new PollFixture(pollId, q1, q2);
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

  private record PollFixture(UUID pollId, UUID q1, UUID q2) {}
}
