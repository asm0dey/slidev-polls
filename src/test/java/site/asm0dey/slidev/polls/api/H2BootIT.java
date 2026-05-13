package site.asm0dey.slidev.polls.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end smoke for the H2 path. Boots the whole Spring context against an in-memory H2
 * database, then hits one HTTP endpoint that exercises the repository's multiset emulation and
 * generated-column reads. A pass here means {@code {vendor}} substitution, jOOQ dialect inference,
 * and the SQL we render on H2 all line up; a green H2MigrationIT alone does not prove that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class H2BootIT {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    String url =
        "jdbc:h2:mem:bootsmoke_"
            + System.nanoTime()
            + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    r.add("spring.datasource.url", () -> url);
    r.add("spring.datasource.username", () -> "sa");
    r.add("spring.datasource.password", () -> "");
    // Driver and dialect are auto-detected from the URL.
  }

  @LocalServerPort int port;

  @Autowired ObjectMapper objectMapper;

  // Hits PublicPollController.getBySlug → PollRepositoryImpl.findBySlug, which exercises
  // POLLS.SLUG_LOWER (generated column) plus the SELECT shape used to materialise a Poll.
  // A 404 with the standard Problem envelope (code = "NOT_FOUND") proves the SQL parses and
  // executes on H2 without runtime errors — i.e. {vendor} substitution wired Flyway to the
  // H2 baseline, jOOQ inferred H2 dialect, and the generated-column read renders correctly.
  // A 500 would indicate a multiset or generated-column regression.
  @Test
  void public_poll_endpoint_returns_not_found_for_unknown_slug_on_h2() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/polls/by-slug/no-such-slug"))
            .GET()
            .build();
    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).as("response status").isEqualTo(404);

    JsonNode body = objectMapper.readTree(resp.body());
    assertThat(body.get("code").asText())
        .as("problem code matches PublicPollViewIT contract")
        .isEqualTo("NOT_FOUND");
  }
}
