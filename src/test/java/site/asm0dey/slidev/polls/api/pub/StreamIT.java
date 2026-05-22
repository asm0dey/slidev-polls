package site.asm0dey.slidev.polls.api.pub;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * End-to-end test for {@code GET /api/polls/{slug}/stream}, anchoring @TS-030: a vote landing
 * against the active question produces a fresh {@code snapshot} SSE event delivered to connected
 * subscribers in well under the 2-second budget, and the snapshot carries the updated tally.
 *
 * <p>Ported to {@code @QuarkusTest} + RestAssured (fixtures via the real admin login → create →
 * open flow, like {@code VoteSubmissionIT}). The SSE stream is read off a raw {@link HttpClient}
 * against the {@code @TestHTTPResource} base URL because RestAssured does not stream {@code
 * text/event-stream}.
 */
@QuarkusTest
class StreamIT {

  @TestHTTPResource("/")
  URL baseUrl;

  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher encoder;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ExecutorService readerPool;
  private AtomicBoolean reading;

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
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  @AfterEach
  void tearDown() {
    reading.set(false);
    readerPool.shutdownNow();
  }

  // @TS-030 — a connected subscriber receives a fresh "snapshot" event within 2s of the vote being
  // accepted; every ballot change re-broadcasts the full snapshot (the legacy "tally" delta event
  // is gone).
  @Test
  void subscriber_receives_snapshot_within_two_seconds_of_a_vote() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("stream-talk");
    ConcurrentLinkedQueue<SseEvent> received = new ConcurrentLinkedQueue<>();

    readerPool.submit(() -> readStream("/api/polls/stream-talk/stream", received));

    // Wait for the initial snapshot so the subscriber is registered before voting.
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> received.stream().anyMatch(e -> "snapshot".equals(e.name)));

    Response voteResponse =
        given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"optionIds\":[\"%s\"]}", poll.optionAId()))
            .when()
            .post("/api/polls/stream-talk/votes")
            .then()
            .extract()
            .response();
    assertThat(voteResponse.statusCode()).isEqualTo(201);

    long before = System.nanoTime();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> received.stream().filter(e -> "snapshot".equals(e.name)).count() >= 2);
    long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
    assertThat(elapsedMs).as("snapshot delivered within 2s budget").isLessThan(2_000L);

    // The latest snapshot (post-vote) carries the updated tally for optionA.
    List<SseEvent> snapshots = received.stream().filter(e -> "snapshot".equals(e.name)).toList();
    SseEvent latest = snapshots.get(snapshots.size() - 1);
    JsonNode node = objectMapper.readTree(latest.data);
    assertThat(UUID.fromString(node.get("activeQuestion").get("id").asText()))
        .as("snapshot activeQuestion matches the open question")
        .isEqualTo(poll.activeQuestionId());
    long optionACount = 0L;
    for (JsonNode entry : node.get("tally")) {
      if (UUID.fromString(entry.get("optionId").asText()).equals(poll.optionAId())) {
        optionACount = entry.get("count").asLong();
      }
    }
    assertThat(optionACount).isEqualTo(1L);
  }

  // ---------- SSE reader + parsing ----------------------------------------

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

  // ---------- fixtures -----------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) {
    Session admin = loginAsAlice();
    String createBody =
        String.format(
            """
            {
              "title": "Stream fixture",
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
