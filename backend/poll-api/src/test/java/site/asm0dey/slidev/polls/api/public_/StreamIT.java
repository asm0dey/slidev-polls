package site.asm0dey.slidev.polls.api.public_;

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
import org.springframework.web.client.RestTemplate;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end test for {@code GET /api/polls/{slug}/stream}. Anchors {@code @TS-030}: a vote landing
 * against the active question produces a {@code tally} SSE event delivered to connected subscribers
 * in well under the 2-second budget.
 *
 * <p>Unlike the pure-unit coverage in {@code TallyBroadcastTest}, this IT boots the full stack
 * (Flyway-migrated Postgres via Testcontainers, Spring Security, the controller layer) so the SSE
 * connection is established over a real HTTP socket and the vote flows through the actual REST
 * pipeline. A small HTTP SSE reader thread collects events off the wire; Awaitility polls it until
 * the expected event lands.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StreamIT {

  @LocalServerPort int port;
  @Autowired ObjectMapper objectMapper;

  private RestTemplate rest;
  private ExecutorService readerPool;
  private AtomicBoolean reading;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    readerPool = Executors.newSingleThreadExecutor();
    reading = new AtomicBoolean(true);
  }

  @AfterEach
  void tearDown() {
    reading.set(false);
    readerPool.shutdownNow();
  }

  // @TS-030 — end-to-end latency: a connected subscriber receives a "tally" event within 2
  // seconds of the vote being accepted by the REST layer.
  @Test
  void subscriber_receives_tally_within_two_seconds_of_a_vote() throws Exception {
    PollFixture poll = createPollWithActiveQuestion("stream-talk");
    ConcurrentLinkedQueue<SseEvent> received = new ConcurrentLinkedQueue<>();

    // Open the stream in a background reader; the server emits the initial snapshot synchronously
    // so we can hand off to Awaitility once it has arrived.
    readerPool.submit(() -> readStream("/api/polls/stream-talk/stream", received));

    // Wait for the initial snapshot before firing the vote; this avoids a race where the vote
    // lands before the hub has the subscriber registered.
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> received.stream().anyMatch(e -> "snapshot".equals(e.name)));

    // Vote, then verify the tally arrives within the 2s budget.
    String voteBody = String.format("{\"optionId\":\"%s\"}", poll.optionAId());
    ResponseEntity<String> voteResponse =
        rest.exchange(
            "http://localhost:" + port + "/api/polls/stream-talk/votes",
            HttpMethod.POST,
            new HttpEntity<>(voteBody, jsonHeaders()),
            String.class);
    assertThat(voteResponse.getStatusCode().value()).isEqualTo(201);

    long before = System.nanoTime();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> received.stream().anyMatch(e -> "tally".equals(e.name)));
    long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
    assertThat(elapsedMs).as("tally delivered within 2s budget").isLessThan(2_000L);

    SseEvent tally =
        received.stream().filter(e -> "tally".equals(e.name)).findFirst().orElseThrow();
    JsonNode node = objectMapper.readTree(tally.data);
    assertThat(UUID.fromString(node.get("questionId").asText()))
        .as("tally questionId matches the active question")
        .isEqualTo(poll.activeQuestionId());
    assertThat(UUID.fromString(node.get("optionId").asText())).isEqualTo(poll.optionAId());
    assertThat(node.get("count").asLong()).isEqualTo(1L);
  }

  // ---------- SSE reader + parsing ----------------------------------------

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
          // ":" comment lines (heartbeats) and any other field are ignored for the assertion.
        }
      }
    } catch (Exception ex) {
      // The reader thread is best-effort; Awaitility surfaces the failure when the expected
      // event never lands.
    }
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private record SseEvent(String name, String data) {}

  // ---------- fixtures -----------------------------------------------------

  private PollFixture createPollWithActiveQuestion(String slug) throws Exception {
    // Log in as alice — the response sets JSESSIONID and XSRF-TOKEN cookies; subsequent admin
    // POSTs need both (the XSRF token is echoed as X-XSRF-TOKEN per SecurityConfig's
    // CookieCsrfTokenRepository setup).
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
              "title": "Stream fixture",
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

  /** Pull the first Set-Cookie line whose name matches and return "name=value" (no attributes). */
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
