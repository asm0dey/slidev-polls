package site.asm0dey.slidev.polls.realtime.sse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * Pins the snapshot-only SSE contract after the {@code tally} delta event was retired: every
 * accepted vote (and every retraction) re-broadcasts a fresh {@link SnapshotPayload}. No {@code
 * tally} or {@code tally-delta} event should ever appear on the wire.
 *
 * <p>Ported to {@code @QuarkusTest} + RestAssured (fixtures via the real admin login → create →
 * open flow, same as {@code VoteSubmissionIT}). The SSE stream is read off a raw {@link HttpClient}
 * against the {@code @TestHTTPResource} base URL because RestAssured does not stream {@code
 * text/event-stream}. The voter identity is the {@code sp_voter} cookie minted on the first vote;
 * it is replayed on the retract so the same row is removed.
 */
@QuarkusTest
class TallyBroadcasterSnapshotIT {

  @TestHTTPResource("/")
  URL baseUrl;

  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher encoder;

  private ExecutorService readerPool;
  private AtomicBoolean reading;
  private String voterCookie;

  @BeforeAll
  static void noCharsetOnJson() {
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  @BeforeEach
  void setUp() {
    readerPool = Executors.newSingleThreadExecutor();
    reading = new AtomicBoolean(true);
    voterCookie = null;
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  @AfterEach
  void tearDown() {
    reading.set(false);
    readerPool.shutdownNow();
  }

  @Test
  void castFiresOneSnapshotFrameAndNoTallyFrame() {
    String slug = "snap-cast";
    PollFixture poll = createPollWithActiveQuestion(slug);
    ConcurrentLinkedQueue<SseEvent> received = new ConcurrentLinkedQueue<>();
    readerPool.submit(() -> readStream("/api/polls/" + slug + "/stream", received));

    // Wait for the initial connect-time snapshot so the subscriber is registered before voting.
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> received.stream().anyMatch(e -> "snapshot".equals(e.name)));

    cast(slug, poll.optionAId());

    // Exactly one more snapshot frame should arrive (one cast → one resnapshot).
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> received.stream().filter(e -> "snapshot".equals(e.name)).count() >= 2);

    assertThat(received.stream().filter(e -> "snapshot".equals(e.name)).count())
        .as("cast triggers exactly one resnapshot beyond the connect-time snapshot")
        .isEqualTo(2L);
    assertThat(received.stream().filter(e -> "tally".equals(e.name)))
        .as("legacy tally delta event must not be emitted")
        .isEmpty();
    assertThat(received.stream().filter(e -> "tally-delta".equals(e.name)))
        .as("legacy tally-delta event must not be emitted")
        .isEmpty();
  }

  @Test
  void retractFiresAnotherSnapshotFrame() {
    String slug = "snap-retract";
    PollFixture poll = createPollWithActiveQuestion(slug);
    ConcurrentLinkedQueue<SseEvent> received = new ConcurrentLinkedQueue<>();
    readerPool.submit(() -> readStream("/api/polls/" + slug + "/stream", received));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> received.stream().anyMatch(e -> "snapshot".equals(e.name)));

    cast(slug, poll.optionAId());
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> received.stream().filter(e -> "snapshot".equals(e.name)).count() >= 2);

    retract(slug);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> received.stream().filter(e -> "snapshot".equals(e.name)).count() >= 3);

    assertThat(received.stream().filter(e -> "snapshot".equals(e.name)).count())
        .as("connect + cast + retract = three snapshot frames")
        .isEqualTo(3L);
  }

  // ---------- HTTP helpers -------------------------------------------------

  private void cast(String slug, UUID optionId) {
    var req =
        given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"optionIds\":[\"%s\"]}", optionId));
    if (voterCookie != null) {
      req = req.cookie("sp_voter", voterCookie);
    }
    Response response =
        req.when()
            .post("/api/polls/" + slug + "/votes")
            .then()
            .statusCode(201)
            .extract()
            .response();
    String minted = response.getCookie("sp_voter");
    if (minted != null) {
      voterCookie = minted;
    }
  }

  private void retract(String slug) {
    var req = given();
    if (voterCookie != null) {
      req = req.cookie("sp_voter", voterCookie);
    }
    req.when().delete("/api/polls/" + slug + "/votes").then().statusCode(204);
  }

  private void readStream(String path, ConcurrentLinkedQueue<SseEvent> sink) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl.toString().replaceAll("/+$", "") + path))
              .header("Accept", "text/event-stream")
              .GET()
              .build();
      HttpResponse<java.io.InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
        String currentName = null;
        List<String> dataLines = new ArrayList<>();
        String line;
        while (reading.get() && (line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            if (currentName != null && !dataLines.isEmpty()) {
              sink.add(new SseEvent(currentName, String.join("\n", dataLines)));
            }
            currentName = null;
            dataLines.clear();
            continue;
          }
          if (line.startsWith("event:")) {
            currentName = line.substring("event:".length()).trim();
          } else if (line.startsWith("data:")) {
            dataLines.add(line.substring("data:".length()).trim());
          }
        }
      }
    } catch (Exception ignored) {
      // Best-effort reader; failures surface through Awaitility timing out on the expected event.
    }
  }

  private record SseEvent(String name, String data) {}

  // ---------- fixture ------------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) {
    Session admin = loginAsAlice();
    String createBody =
        String.format(
            """
            {
              "title": "Snapshot fixture",
              "slug": "%s",
              "questions": [
                { "prompt": "Which JVM?", "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] }
              ]
            }
            """,
            slug);
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
