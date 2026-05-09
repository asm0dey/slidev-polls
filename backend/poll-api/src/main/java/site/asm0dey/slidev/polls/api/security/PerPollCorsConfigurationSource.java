package site.asm0dey.slidev.polls.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollRepository;

/**
 * Per-poll CORS resolver. Three URI families:
 *
 * <ul>
 *   <li>{@code /api/polls/{slug}/...} — the public surface and the SSE stream;
 *       resolve poll by slug, return its allowed_origins.
 *   <li>{@code /api/deck/polls/{pollId}/...} — deck-token-authenticated mutations;
 *       resolve poll by id.
 *   <li>{@code /api/deck/auth/...} — pre-auth login/me; resolve by Origin header
 *       across all polls.
 * </ul>
 *
 * Anything else (admin, static SPA shells, …) returns {@code null} which
 * disables CORS for that path; the request is treated as same-origin or
 * blocked by the browser per the user agent's policy.
 */
@Component
public class PerPollCorsConfigurationSource implements CorsConfigurationSource {

  private static final Pattern SLUG = Pattern.compile("^/api/polls/([^/]+)(?:/.*)?$");
  private static final Pattern POLL_ID =
      Pattern.compile("^/api/deck/polls/([0-9a-fA-F-]{36})(?:/.*)?$");
  private static final Pattern DECK_AUTH = Pattern.compile("^/api/deck/auth/.*$");

  private static final List<String> ALLOWED_HEADERS =
      List.of("Content-Type", "X-Deck-Token", "X-XSRF-TOKEN");
  private static final List<String> ALLOWED_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

  private final PollRepository repo;

  public PerPollCorsConfigurationSource(PollRepository repo) {
    this.repo = repo;
  }

  @Override
  public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) return null;

    Matcher slug = SLUG.matcher(uri);
    if (slug.matches()) {
      return repo.findBySlug(slug.group(1)).map(this::buildConfig).orElse(null);
    }
    Matcher pid = POLL_ID.matcher(uri);
    if (pid.matches()) {
      try {
        UUID id = UUID.fromString(pid.group(1));
        return repo.findById(id).map(this::buildConfig).orElse(null);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    if (DECK_AUTH.matcher(uri).matches()) {
      String origin = request.getHeader("Origin");
      if (origin == null || origin.isBlank()) return null;
      List<Poll> matches = repo.findAllOriginsContaining(origin);
      if (matches.isEmpty()) return null;
      CorsConfiguration cfg = baseConfig();
      cfg.setAllowedOrigins(List.of(origin));
      return cfg;
    }
    return null;
  }

  private CorsConfiguration buildConfig(Poll poll) {
    if (poll.allowedOrigins().isEmpty()) return null;
    CorsConfiguration cfg = baseConfig();
    cfg.setAllowedOrigins(poll.allowedOrigins());
    return cfg;
  }

  private CorsConfiguration baseConfig() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedMethods(ALLOWED_METHODS);
    cfg.setAllowedHeaders(ALLOWED_HEADERS);
    cfg.setAllowCredentials(true);
    cfg.setMaxAge(600L);
    return cfg;
  }
}
