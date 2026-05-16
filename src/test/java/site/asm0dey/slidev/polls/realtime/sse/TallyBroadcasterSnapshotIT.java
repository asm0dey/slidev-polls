package site.asm0dey.slidev.polls.realtime.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import site.asm0dey.slidev.polls.api.PollApiApplication;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the snapshot-only SSE contract after the {@code tally} delta event was retired: every
 * accepted vote (and every retraction) re-broadcasts a fresh {@link SnapshotPayload}. No {@code
 * tally} or {@code tally-delta} event should ever appear on the wire.
 *
 * <p>Boots the full Spring stack the same way {@code StreamIT} does — the public REST surface still
 * exposes the legacy single-{@code optionId} shape until Task 12 lands; the snapshot-driven SSE
 * behaviour is independent of that and is what this IT verifies.
 */
@SpringBootTest(
    classes = PollApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TallyBroadcasterSnapshotIT {

  @LocalServerPort int port;
  @Autowired ObjectMapper objectMapper;
  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;

  private RestTemplate rest;
  private ExecutorService readerPool;
  private AtomicBoolean reading;
  private String voterCookie;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
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
  void castFiresOneSnapshotFrameAndNoTallyFrame() throws Exception {
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
  void retractFiresAnotherSnapshotFrame() throws Exception {
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
    String body = String.format("{\"optionIds\":[\"%s\"]}", optionId);
    HttpHeaders headers = jsonHeaders();
    if (voterCookie != null) {
      headers.add(HttpHeaders.COOKIE, voterCookie);
    }
    ResponseEntity<String> response =
        rest.exchange(
            "http://localhost:" + port + "/api/polls/" + slug + "/votes",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    if (setCookies != null) {
      for (String header : setCookies) {
        if (header.startsWith("sp_voter=")) {
          int semi = header.indexOf(';');
          voterCookie = semi >= 0 ? header.substring(0, semi) : header;
        }
      }
    }
  }

  private void retract(String slug) {
    HttpHeaders headers = jsonHeaders();
    if (voterCookie != null) {
      headers.add(HttpHeaders.COOKIE, voterCookie);
    }
    ResponseEntity<String> response =
        rest.exchange(
            "http://localhost:" + port + "/api/polls/" + slug + "/votes",
            HttpMethod.DELETE,
            new HttpEntity<>(headers),
            String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
  }

  private void readStream(String path, ConcurrentLinkedQueue<SseEvent> sink) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
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
    } catch (Exception ex) {
      // Best-effort reader; failures surface through Awaitility timing out on the expected event.
    }
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private record SseEvent(String name, String data) {}

  // ---------- fixture ------------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) throws Exception {
    ResponseEntity<String> login =
        rest.exchange(
            "http://localhost:" + port + "/api/admin/login",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"username\":\"alice\",\"password\":\"correct-horse\"}", jsonHeaders()),
            String.class);
    List<String> setCookies = login.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookies).as("login established a session").isNotNull();
    String sessionCookie = extractCookie(setCookies, "SP_SESSION");
    String xsrfCookie = extractCookie(setCookies, "XSRF-TOKEN");
    assertThat(sessionCookie).isNotBlank();
    assertThat(xsrfCookie).isNotBlank();
    String xsrfToken = xsrfCookie.substring(xsrfCookie.indexOf('=') + 1);

    HttpHeaders authed = jsonHeaders();
    authed.add(HttpHeaders.COOKIE, sessionCookie);
    authed.add(HttpHeaders.COOKIE, xsrfCookie);
    authed.add("X-XSRF-TOKEN", xsrfToken);

    String createBody =
        String.format(
            """
            {
              "title": "Snapshot fixture",
              "slug": "%s",
              "questions": [
                {
                  "prompt": "Which JVM?",
                  "options": [{"label":"OpenJDK"},{"label":"GraalVM"}]
                }
              ]
            }
            """,
            slug);
    ResponseEntity<String> createdResponse =
        rest.exchange(
            "http://localhost:" + port + "/api/admin/polls",
            HttpMethod.POST,
            new HttpEntity<>(createBody, authed),
            String.class);
    JsonNode created = objectMapper.readTree(createdResponse.getBody());
    UUID pollId = UUID.fromString(created.get("id").asText());
    JsonNode question = created.get("questions").get(0);
    UUID questionId = UUID.fromString(question.get("id").asText());
    UUID optionA = UUID.fromString(question.get("options").get(0).get("id").asText());
    UUID optionB = UUID.fromString(question.get("options").get(1).get("id").asText());

    rest.exchange(
        "http://localhost:" + port + "/api/admin/polls/" + pollId + "/open",
        HttpMethod.POST,
        new HttpEntity<>("{\"questionId\":\"" + questionId + "\"}", authed),
        String.class);
    return new PollFixture(pollId, questionId, optionA, optionB);
  }

  private static String extractCookie(List<String> setCookieHeaders, String name) {
    for (String header : setCookieHeaders) {
      if (header.startsWith(name + "=")) {
        int semi = header.indexOf(';');
        return semi >= 0 ? header.substring(0, semi) : header;
      }
    }
    return "";
  }

  private record PollFixture(UUID pollId, UUID activeQuestionId, UUID optionAId, UUID optionBId) {}
}
