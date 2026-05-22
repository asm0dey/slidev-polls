package site.asm0dey.slidev.polls.api.web;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollRepository;

/**
 * Per-poll CORS resolver, ported from the Spring {@code PerPollCorsConfigurationSource} to a Vert.x
 * {@link RouteFilter}. Three URI families:
 *
 * <ul>
 *   <li>{@code /api/polls/{slug}/...} — the public surface and the SSE stream; resolve poll by
 *       slug, return its allowed_origins.
 *   <li>{@code /api/deck/polls/{pollId}/...} — deck-token-authenticated mutations; resolve poll by
 *       id.
 *   <li>{@code /api/deck/auth/...} — pre-auth login/me; resolve by Origin header across all polls.
 * </ul>
 *
 * <p>Anything else (admin, static SPA shells, …) gets no CORS headers — equivalent to the old
 * {@code null} return that disabled CORS for that path. The request is treated as same-origin or
 * blocked by the browser per the user agent's policy.
 *
 * <p>Runs at a high priority so it executes before the static-resource handler and the JAX-RS
 * router. On a preflight {@code OPTIONS} for an allowed origin it writes the headers, responds 204
 * and ends the response; on any other request it writes the allow-origin/credentials headers (when
 * the origin is allowed) and continues the chain via {@link RoutingContext#next()}.
 */
@ApplicationScoped
public class PerPollCorsFilter {

  private static final Pattern SLUG = Pattern.compile("^/api/polls/([^/]+)(?:/.*)?$");
  private static final Pattern POLL_ID =
      Pattern.compile("^/api/deck/polls/([0-9a-fA-F-]{36})(?:/.*)?$");
  private static final Pattern DECK_AUTH = Pattern.compile("^/api/deck/auth/.*$");

  private static final String ALLOWED_HEADERS = "Content-Type, X-Deck-Token, X-XSRF-TOKEN";
  private static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";
  private static final String MAX_AGE = "600";

  private final PollRepository repo;

  @Inject
  public PerPollCorsFilter(PollRepository repo) {
    this.repo = repo;
  }

  @RouteFilter(1000)
  void cors(RoutingContext rc) {
    String uri = rc.normalizedPath();
    String origin = rc.request().getHeader("Origin");

    List<String> allowedOrigins = resolveAllowedOrigins(uri, origin);
    boolean originAllowed =
        origin != null
            && !origin.isBlank()
            && allowedOrigins != null
            && allowedOrigins.contains(origin);

    if (originAllowed) {
      rc.response().putHeader("Access-Control-Allow-Origin", origin);
      rc.response().putHeader("Access-Control-Allow-Credentials", "true");
      rc.response().putHeader("Vary", "Origin");
    }

    if (rc.request().method() == HttpMethod.OPTIONS) {
      if (originAllowed) {
        rc.response().putHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
        rc.response().putHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
        rc.response().putHeader("Access-Control-Max-Age", MAX_AGE);
      }
      rc.response().setStatusCode(204).end();
      return;
    }

    rc.next();
  }

  /**
   * Returns the allowed-origin list for the request, or {@code null} when CORS does not apply
   * (mirrors the old {@code getCorsConfiguration} returning {@code null}). For the deck-auth family
   * the request {@code Origin} is the only candidate, so an allowed origin yields a single-element
   * list.
   */
  private List<String> resolveAllowedOrigins(String uri, String origin) {
    if (uri == null) {
      return null;
    }

    Matcher slug = SLUG.matcher(uri);
    if (slug.matches()) {
      return repo.findBySlug(slug.group(1)).map(this::originsOf).orElse(null);
    }

    Matcher pid = POLL_ID.matcher(uri);
    if (pid.matches()) {
      try {
        UUID id = UUID.fromString(pid.group(1));
        return repo.findById(id).map(this::originsOf).orElse(null);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    if (DECK_AUTH.matcher(uri).matches()) {
      if (origin == null || origin.isBlank()) {
        return null;
      }
      if (!repo.isOriginAllowedByAnyPoll(origin)) {
        return null;
      }
      return List.of(origin);
    }

    return null;
  }

  private List<String> originsOf(Poll poll) {
    return poll.allowedOrigins().isEmpty() ? null : poll.allowedOrigins();
  }
}
