package site.asm0dey.slidev.polls.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollRepository;

class PerPollCorsConfigurationSourceTest {

  PollRepository repo = mock(PollRepository.class);
  PerPollCorsConfigurationSource src = new PerPollCorsConfigurationSource(repo);

  @Test
  void resolvesBySlugForPublicAndStreamRoutes() {
    Poll poll = pollWithOrigins(List.of("http://localhost:3030"), "demo");
    when(repo.findBySlug("demo")).thenReturn(Optional.of(poll));
    HttpServletRequest req = get("/api/polls/demo/stream");
    CorsConfiguration cfg = src.getCorsConfiguration(req);
    assertThat(cfg).isNotNull();
    assertThat(cfg.getAllowedOrigins()).containsExactly("http://localhost:3030");
    assertThat(cfg.getAllowCredentials()).isTrue();
  }

  @Test
  void resolvesByPollIdForDeckActivation() {
    UUID id = UUID.randomUUID();
    Poll poll = pollWithOrigins(List.of("https://example.github.io"), "p", id);
    when(repo.findById(id)).thenReturn(Optional.of(poll));
    HttpServletRequest req = get("/api/deck/polls/" + id + "/activate");
    CorsConfiguration cfg = src.getCorsConfiguration(req);
    assertThat(cfg).isNotNull();
    assertThat(cfg.getAllowedOrigins()).containsExactly("https://example.github.io");
  }

  @Test
  void deckAuthLoginAllowsOriginIfAnyPollAllowsIt() {
    when(repo.isOriginAllowedByAnyPoll("http://localhost:3030")).thenReturn(true);
    MockHttpServletRequest req = (MockHttpServletRequest) get("/api/deck/auth/login");
    req.addHeader("Origin", "http://localhost:3030");
    CorsConfiguration cfg = src.getCorsConfiguration(req);
    assertThat(cfg).isNotNull();
    assertThat(cfg.getAllowedOrigins()).containsExactly("http://localhost:3030");
  }

  @Test
  void deckAuthLoginRejectsOriginNoPollAllows() {
    when(repo.isOriginAllowedByAnyPoll("https://attacker.example")).thenReturn(false);
    MockHttpServletRequest req = (MockHttpServletRequest) get("/api/deck/auth/login");
    req.addHeader("Origin", "https://attacker.example");
    assertThat(src.getCorsConfiguration(req)).isNull();
  }

  @Test
  void unknownSlugReturnsNoConfig() {
    when(repo.findBySlug("ghost")).thenReturn(Optional.empty());
    assertThat(src.getCorsConfiguration(get("/api/polls/ghost/stream"))).isNull();
  }

  @Test
  void adminPathsReturnNoConfig() {
    assertThat(src.getCorsConfiguration(get("/api/admin/polls"))).isNull();
  }

  // ---- helpers ----

  private static HttpServletRequest get(String uri) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
    r.setRequestURI(uri);
    return r;
  }

  private static Poll pollWithOrigins(List<String> origins, String slug) {
    return pollWithOrigins(origins, slug, UUID.randomUUID());
  }

  private static Poll pollWithOrigins(List<String> origins, String slug, UUID id) {
    return new Poll(
        id,
        "alice",
        "T",
        slug,
        site.asm0dey.slidev.polls.core.domain.PollStatus.OPEN,
        null,
        java.util.List.of(),
        origins,
        java.time.Instant.now(),
        java.time.Instant.now());
  }
}
