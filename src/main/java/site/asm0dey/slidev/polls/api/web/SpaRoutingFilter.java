package site.asm0dey.slidev.polls.api.web;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.regex.Pattern;

/**
 * Server-side routing for the two SPA shells that share the poll-api origin, ported from the Spring
 * {@code SpaForwardingConfig} to a Vert.x {@link RouteFilter}. Quarkus serves static resources from
 * {@code META-INF/resources}; reroute targets ({@code /index.html}, {@code /admin/index.html})
 * resolve there.
 *
 * <ul>
 *   <li>{@code /} and every single-segment kebab-case slug ({@code /{slug}}) reroute to {@code
 *       /index.html} — the voter SPA reads the slug client-side and hydrates the right view. {@code
 *       /index.html} itself carries a dot so the slug regex refuses to match it.
 *   <li>{@code /admin} (no trailing slash) → 302 redirect to {@code /admin/} so Vue Router's {@code
 *       /admin/} base resolves asset-relative URLs correctly.
 *   <li>{@code /admin/} and {@code /admin/**} → if the dot-less path's static file does not exist,
 *       reroute to {@code /admin/index.html} (SPA deep link); otherwise let the static handler
 *       serve the real file (or 404 a missing dotted asset).
 * </ul>
 *
 * <p>Deliberately untouched: {@code /api/**} (all backend routes, gated by security) and dotted
 * asset paths under {@code /} (multi-segment or extension-carrying URLs fall through to the static
 * handler). The voter regex matches a single kebab segment only, so a multi-segment asset URL or
 * {@code /api/polls/by-slug/{slug}} is never swallowed.
 *
 * <p>Priority is below {@link PerPollCorsFilter} so CORS headers are written first; this filter
 * only acts on non-{@code /api} {@code GET} routes.
 */
@ApplicationScoped
public class SpaRoutingFilter {

  // Mirrors the polls.slug CHECK constraint: a URL that could never be a real poll (upper-case,
  // leading hyphen, dot-containing, wrong length) falls through to the default 404 handling.
  private static final Pattern VOTER_SLUG = Pattern.compile("^/([a-z0-9-]{3,40})$");

  @RouteFilter(500)
  void route(RoutingContext rc) {
    // Only GET navigations are SPA shells; everything else (POST, etc.) passes straight through.
    if (rc.request().method() != HttpMethod.GET) {
      rc.next();
      return;
    }

    String path = rc.normalizedPath();
    if (path == null) {
      rc.next();
      return;
    }

    // Never touch backend routes.
    if (path.startsWith("/api/")) {
      rc.next();
      return;
    }

    // /admin (no trailing slash) → redirect to the slashed form.
    if (path.equals("/admin")) {
      rc.response().setStatusCode(302).putHeader("Location", "/admin/").end();
      return;
    }

    // /admin/ and /admin/** — serve the real static file when it exists, else fall back to the
    // shell for dot-less deep links, else let the static handler 404 a missing dotted asset.
    if (path.equals("/admin/") || path.startsWith("/admin/")) {
      String resourcePath = path.equals("/admin/") ? "/admin/index.html" : path;
      if (!lastSegmentHasDot(resourcePath) && !staticResourceExists(resourcePath)) {
        rc.reroute("/admin/index.html");
        return;
      }
      rc.next();
      return;
    }

    // Voter shell: "/" and single-segment kebab slugs reroute to /index.html. /index.html itself
    // carries a dot, so the regex refuses it and the reroute lands on the static handler.
    if (path.equals("/") || VOTER_SLUG.matcher(path).matches()) {
      rc.reroute("/index.html");
      return;
    }

    // Dotted asset paths under / and multi-segment URLs fall through to the static handler.
    rc.next();
  }

  /** True iff a static resource for {@code path} exists under {@code META-INF/resources}. */
  private boolean staticResourceExists(String path) {
    String classpathPath = "/META-INF/resources" + path;
    return getClass().getResource(classpathPath) != null;
  }

  private static boolean lastSegmentHasDot(String path) {
    int lastSlash = path.lastIndexOf('/');
    String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    return tail.indexOf('.') >= 0;
  }
}
